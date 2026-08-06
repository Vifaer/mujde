/**
 * Mujde / Frida — Mystery Forest cheat v11
 *
 * target : com.zcs.udpmysteryforest / Mystery Element Match3 1.0.3 arm64
 *
 * Features:
 *   - coins / gems locked to 99999999
 *   - score x10
 *   - global speed x3 (Unity Time.timeScale, keep pause=0)
 *   - element upgrade costs no fragments (table cost=0 + enough=true + no item consume)
 *   - OnCheatDetected skip (do NOT touch get_detectCheat: 8-byte fatal)
 *
 * Log rule: alog() MUST be ASCII-only (Mujde/frida-inject pipe mangles CJK/emoji to ?)
 *
 * Mujde Apps: bind this script only → force-stop game → cold start
 * logcat: adb logcat -s MF_MOD:I
 */

'use strict';

var FLAG = '__MF_UNLIMITED_COINS_GEMS_V11__';
var TAG = 'MF_MOD';
var MAX_MONEY = 99999999;
var SCORE_MUL = 10;
var TIME_SCALE = 3.0;

var RVA = {
  // anticheat (short getter 0x6f65c0 is FORBIDDEN)
  OnCheatDetected: 0x6f6d38,

  GetCoins: 0x81b694,
  SetCoins: 0x826e44,
  ModifyCoins: 0x826d1c,
  GetGems: 0x826f00,

  GetElementLevelScore: 0x849458,
  GetSkillScore: 0x849654,

  // UnityEngine.Time
  get_timeScale: 0x1175008,
  set_timeScale: 0x1175060,

  // element upgrade
  IsTargetImproveCostEnough: 0x81b418,
  get_FragmentGrey: 0xa0fc20,
  get_FragmentBlue: 0xa0fc28,
  get_FragmentPurple: 0xa0fc30,
  get_Essence: 0xa0fc38,
  get_ImproveCoin: 0xa0fc40,
  ModifyItem: 0x115af00
};

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
  }, 300);
}

function hookScoreRet(il2cpp, rva, name) {
  Interceptor.attach(il2cpp.base.add(rva), {
    onLeave: function (retval) {
      var n = retval.toInt32();
      if (n === 0) return;
      var m = n * SCORE_MUL;
      retval.replace(ptr(m));
      alog(name + ' ' + n + ' -> ' + m);
    }
  });
}

function hookCostZero(il2cpp, rva, name) {
  Interceptor.attach(il2cpp.base.add(rva), {
    onLeave: function (retval) {
      if (retval.toInt32() !== 0) {
        retval.replace(ptr(0));
      }
    }
  });
  alog('hook ' + name + ' -> 0');
}

function installSpeed(il2cpp) {
  var getTs = new NativeFunction(il2cpp.base.add(RVA.get_timeScale), 'float', []);
  var setTs = new NativeFunction(il2cpp.base.add(RVA.set_timeScale), 'void', ['float']);

  function apply() {
    try {
      var cur = getTs();
      // keep pause; otherwise force x3
      if (cur > 0.05 && Math.abs(cur - TIME_SCALE) > 0.05) {
        setTs(TIME_SCALE);
      }
    } catch (e) {}
  }

  apply();
  setInterval(apply, 400);
  alog('speed x' + TIME_SCALE + ' via Time.timeScale');
}

function installUpgradeFree(il2cpp) {
  Interceptor.attach(il2cpp.base.add(RVA.IsTargetImproveCostEnough), {
    onLeave: function (retval) {
      retval.replace(ptr(1));
    }
  });
  alog('IsTargetImproveCostEnough -> true');

  hookCostZero(il2cpp, RVA.get_FragmentGrey, 'FragmentGrey');
  hookCostZero(il2cpp, RVA.get_FragmentBlue, 'FragmentBlue');
  hookCostZero(il2cpp, RVA.get_FragmentPurple, 'FragmentPurple');
  hookCostZero(il2cpp, RVA.get_Essence, 'Essence');
  hookCostZero(il2cpp, RVA.get_ImproveCoin, 'ImproveCoin');

  // ZUserOtherItemData.ModifyItem(itemId, delta): block consume (delta < 0)
  Interceptor.attach(il2cpp.base.add(RVA.ModifyItem), {
    onEnter: function (args) {
      var delta = args[2].toInt32();
      if (delta < 0) {
        args[2] = ptr(0);
        alog('ModifyItem block consume id=' + args[1].toInt32() + ' delta=' + delta);
      }
    }
  });
  alog('ModifyItem: negative delta -> 0');
}

function installHooks(il2cpp) {
  alog('libil2cpp base=' + il2cpp.base);

  // 1) anticheat: skip handler only (never touch get_detectCheat)
  Interceptor.attach(il2cpp.base.add(RVA.OnCheatDetected), {
    onEnter: function () {
      this.context.pc = this.context.lr;
    }
  });
  alog('OnCheatDetected skipped');

  // 2) money
  Interceptor.attach(il2cpp.base.add(RVA.GetCoins), {
    onLeave: function (retval) {
      retval.replace(ptr(MAX_MONEY));
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.SetCoins), {
    onEnter: function (args) {
      args[1] = ptr(MAX_MONEY);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.ModifyCoins), {
    onEnter: function (args) {
      args[1] = ptr(0);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.GetGems), {
    onLeave: function (retval) {
      retval.replace(ptr(MAX_MONEY));
    }
  });
  alog('coins/gems locked ' + MAX_MONEY);

  // 3) score x10
  hookScoreRet(il2cpp, RVA.GetElementLevelScore, 'GetElementLevelScore');
  hookScoreRet(il2cpp, RVA.GetSkillScore, 'GetSkillScore');
  alog('score x' + SCORE_MUL);

  // 4) global x3
  installSpeed(il2cpp);

  // 5) free element upgrade
  installUpgradeFree(il2cpp);

  alog('========== v11 ready (speed x3, free upgrade) ==========');
}

function main() {
  alog('========== mysteryforest cheat v11 start ==========');
  waitModule('libil2cpp.so', installHooks);
}

if (globalThis[FLAG]) {
  alog('already installed, skip');
} else {
  globalThis[FLAG] = true;
  setImmediate(main);
}
