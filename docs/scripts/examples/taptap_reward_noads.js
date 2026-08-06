/**
 * Mujde / Frida — TapTap SCE 激励广告直接发奖
 *
 * target : com.taptap / com.taptap:urhox_sce_runtime*
 * flag   : __TAPTAP_REWARD_NOADS_V3__
 *
 * urhox 进程无 Java 桥 → hook libUrhoXRuntime.so：
 *   替换 SDKLuaAPI::ShowRewardVideoAd，注册回调后直接
 *   CallRewardVideoAdCallback(success=true)。
 *
 * Mujde → Apps → com.taptap 勾选本脚本 → 强停 TapTap → 进游戏点广告领奖
 * adb logcat -s TAPAD_NOADS:I
 */

'use strict';

var FLAG = '__TAPTAP_REWARD_NOADS_V3__';
var TAG = 'TAPAD_NOADS';

var RVA = {
  ShowRewardVideoAd_Lua: 0x17aa868,
  CallRewardVideoAdCallback: 0x17ae9ec,
  CanShowRewardVideoAd: 0x1a47cc0,
  lua_type: 0x5cb210,
  lua_pushvalue: 0x5df860, // mov w1,#2; bl — push callback
  push_cb_helper: 0x5cca40, // alternate store helper in original
  lua_setfield: 0x5f60e0,
  lua_pushboolean: 0x5f3660,
  CallbackName: 0x1eb65f7, // "__SDK_REWARD_VIDEO_AD_CALLBACK__"
  EmptyStringSlot: 0x23224b8,
  JniPltGot: 0x231af18,
  JniCallReturn: 0x17ab5ec,
  DebounceDouble: 0x24075f0
};

// LUA_REGISTRYINDEX as encoded in binary: mov w1,#0xb9d8; movk w1,#0xfff0,lsl#16
var LUA_REGISTRYINDEX = -1001000;

if (globalThis[FLAG]) {
  // already
} else {
  globalThis[FLAG] = true;
  setImmediate(main);
}

function alog(msg) {
  var line = '[' + TAG + '] ' + msg;
  console.log(line);
  try {
    var fn = new NativeFunction(
      Module.findExportByName('liblog.so', '__android_log_write'),
      'int',
      ['int', 'pointer', 'pointer']
    );
    fn(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(line));
  } catch (e) {}
}

function waitModule(name, cb) {
  var m = Process.findModuleByName(name);
  if (m) {
    cb(m);
    return;
  }
  setTimeout(function () {
    waitModule(name, cb);
  }, 400);
}

function installNative(mod) {
  alog('UrhoX base=' + mod.base);

  var showAddr = mod.base.add(RVA.ShowRewardVideoAd_Lua);
  var callCb = new NativeFunction(
    mod.base.add(RVA.CallRewardVideoAdCallback),
    'void',
    ['pointer', 'int', 'pointer', 'pointer']
  );
  var lua_type = new NativeFunction(mod.base.add(RVA.lua_type), 'int', [
    'pointer',
    'int'
  ]);
  var lua_pushvalue = new NativeFunction(
    mod.base.add(RVA.lua_pushvalue),
    'void',
    ['pointer', 'int']
  );
  var push_cb_helper = new NativeFunction(
    mod.base.add(RVA.push_cb_helper),
    'void',
    ['pointer']
  );
  var lua_setfield = new NativeFunction(
    mod.base.add(RVA.lua_setfield),
    'void',
    ['pointer', 'int', 'pointer']
  );
  var lua_pushboolean = new NativeFunction(
    mod.base.add(RVA.lua_pushboolean),
    'void',
    ['pointer', 'int']
  );
  var cbName = mod.base.add(RVA.CallbackName);

  function emptyString() {
    try {
      var p = mod.base.add(RVA.EmptyStringSlot).readPointer();
      return p.isNull() ? ptr(0) : p;
    } catch (e) {
      return ptr(0);
    }
  }

  function storeCallback(L) {
    var t2 = lua_type(L, 2);
    var t1 = lua_type(L, 1);
    alog('lua_type1=' + t1 + ' type2=' + t2);
    if (t2 === 6) {
      lua_pushvalue(L, 2);
      lua_setfield(L, LUA_REGISTRYINDEX, cbName);
      return true;
    }
    if (t1 === 6) {
      lua_pushvalue(L, 1);
      lua_setfield(L, LUA_REGISTRYINDEX, cbName);
      return true;
    }
    try {
      push_cb_helper(L);
      lua_setfield(L, LUA_REGISTRYINDEX, cbName);
      return true;
    } catch (e) {
      alog('store helper fail: ' + e);
      return false;
    }
  }

  try {
    Interceptor.replace(
      mod.base.add(RVA.CanShowRewardVideoAd),
      new NativeCallback(
        function () {
          return 1;
        },
        'int',
        ['pointer']
      )
    );
    alog('CanShow => true');
  } catch (e) {
    alog('CanShow fail: ' + e);
  }

  try {
    Interceptor.replace(
      showAddr,
      new NativeCallback(
        function (L) {
          alog('ShowRewardVideoAd replaced');
          try {
            mod.base.add(RVA.DebounceDouble).writeDouble(0);
          } catch (e0) {}

          try {
            if (storeCallback(L)) alog('callback stored');
          } catch (e1) {
            alog('store cb: ' + e1);
          }

          try {
            var es = emptyString();
            callCb(L, 1, es, es);
            alog('CallReward success');
          } catch (e2) {
            alog('CallReward err: ' + e2);
          }

          try {
            lua_pushboolean(L, 1);
          } catch (e3) {}
          return 1;
        },
        'int',
        ['pointer']
      )
    );
    alog('ShowRewardVideoAd replaced OK');
  } catch (e) {
    alog('Show replace fail: ' + e);
  }
}

function main() {
  alog('boot pid=' + Process.id);
  if (typeof Java !== 'undefined' && Java.available) {
    Java.perform(function () {
      alog('Java available — optional launcher hooks');
      [
        'xd.urhox.game.EmbedUrhoXGameLauncher',
        'xd.sce.game.EmbedSCEGameLauncher'
      ].forEach(function (cn) {
        try {
          var C = Java.use(cn);
          C.showRewardVideoAd.overloads.forEach(function (ov) {
            ov.implementation = function () {
              alog(cn + ' java postReward true');
              try {
                this.postRewardVideoResult(true, '');
              } catch (e1) {
                try {
                  this.postRewardVideoResult(true, 0, '', '');
                } catch (e2) {
                  try {
                    this.postRewardVideoResult(true, 0, '', 0, '');
                  } catch (e3) {}
                }
              }
            };
          });
        } catch (e) {}
      });
    });
  } else {
    alog('no Java bridge — native path');
  }

  waitModule('libUrhoXRuntime.so', function (mod) {
    try {
      installNative(mod);
      alog('ready');
    } catch (e) {
      alog('install err: ' + e);
    }
  });
}
