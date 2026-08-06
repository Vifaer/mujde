/**
 * Mujde / Frida — 学习骨架（局内得分 ×10）
 *
 * target : com.zcs.udpmysteryforest / 神秘元素消消乐 1.0.3 arm64
 *
 * 本机踩坑（务必看）:
 *   1) get_detectCheat 仅 8 字节 → attach/replace 都会闪退
 *   2) Java.perform + Toast → 本游戏 SIGSEGV 闪退
 *   3) 看日志: adb logcat | findstr MF_LEARN
 *      （frida console.log 有时不进 logcat；下面用 liblog 双写）
 *
 * 局内分数链路（得分产出，不是过关目标 InitScore）:
 *   GetElementLevelScore / GetSkillScore  → AddLevelScore → ZEraseGoal.levelScore
 *   UI: ZScoreProgress.AddScore（勿只改 HUD）
 *
 * Mujde → Apps → 只勾本脚本 → 冷启动
 * 改币/关检测段落全部注释，仅供对照。
 */

'use strict';

var FLAG = '__MF_LEARNING_SKELETON_V6__';
var TAG = 'MF_LEARN';
var SCORE_MUL = 10;

var RVA = {
  get_detectCheat: 0x6f65c0,
  OnCheatDetected: 0x6f6d38,
  GetCoins: 0x81b694,
  SetCoins: 0x826e44,
  ModifyCoins: 0x826d1c,
  GetGems: 0x826f00,

  // 局内得分（逻辑分；返回值 int）
  GetElementLevelScore: 0x849458, // ZTargetHelper
  GetSkillScore: 0x849654,        // ZTargetHelper
  // 对照用（默认不挂）：写入 / 流程 / HUD
  AddLevelScore_Rsp: 0x740a74,    // ZEraseMsgResponse.AddLevelScore(ele, levelScore)
  AddLevelScore_Flow: 0x8466c8,   // ZEraseFlow.AddLevelScore
  set_levelScore: 0x720e28,       // ZEraseGoal（ZObscuredInt）
  ZScoreProgress_AddScore: 0x7f3b90
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

/** 静态方法返回 int：onLeave 把返回值 × SCORE_MUL */
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
  alog('hook ' + name + ' @ +' + rva.toString(16) + ' x' + SCORE_MUL);
}

function installLearning(il2cpp) {
  alog('libil2cpp base=' + il2cpp.base);

  // ----- 局内得分 ×10（逻辑分入口；函数够长，可用 attach）-----
  hookScoreRet(il2cpp, RVA.GetElementLevelScore, 'GetElementLevelScore');
  hookScoreRet(il2cpp, RVA.GetSkillScore, 'GetSkillScore');

  // ----- 可选只读 / 备选倍增：默认关闭 -----
  /*
  // 备选：改 AddLevelScore 的 levelScore 参数（实例方法 args[2] = int）
  Interceptor.attach(il2cpp.base.add(RVA.AddLevelScore_Rsp), {
    onEnter: function (args) {
      var n = args[2].toInt32();
      if (n === 0) return;
      args[2] = ptr(n * SCORE_MUL);
      alog('AddLevelScore_Rsp ' + n + ' -> ' + n * SCORE_MUL);
    }
  });

  // 只读观测
  Interceptor.attach(il2cpp.base.add(RVA.GetCoins), {
    onLeave: function (retval) {
      alog('GetCoins => ' + retval);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.GetGems), {
    onLeave: function (retval) {
      alog('GetGems => ' + retval);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.OnCheatDetected), {
    onEnter: function () {
      alog('OnCheatDetected (readonly)');
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.ZScoreProgress_AddScore), {
    onEnter: function (args) {
      alog('ZScoreProgress.AddScore delta=' + args[1].toInt32());
    }
  });
  */

  // ----- 改币 / 关检测对照（保持注释）-----
  //
  // // ❌ 短函数 8 字节，禁止 attach/replace
  // Interceptor.replace(il2cpp.base.add(RVA.get_detectCheat),
  //   new NativeCallback(function () { return 0; }, 'bool', ['pointer']));
  //
  // // ❌ 关检测（不要启用）
  // Interceptor.replace(il2cpp.base.add(RVA.OnCheatDetected),
  //   new NativeCallback(function () { return; }, 'void', ['pointer']));
  // Interceptor.attach(il2cpp.base.add(RVA.OnCheatDetected), {
  //   onEnter: function () { this.context.pc = this.context.lr; }
  // });
  //
  // // ❌ 改币（不要启用）；返回类型是 long
  // Interceptor.attach(il2cpp.base.add(RVA.GetCoins), {
  //   onLeave: function (retval) { /* retval.replace(...); */ }
  // });
  // Interceptor.attach(il2cpp.base.add(RVA.SetCoins), {
  //   onEnter: function (args) { /* 改 args[1] */ }
  // });
  // Interceptor.attach(il2cpp.base.add(RVA.ModifyCoins), {
  //   onEnter: function (args) { /* 改 delta */ }
  // });
  // Interceptor.attach(il2cpp.base.add(RVA.GetGems), {
  //   onLeave: function (retval) { /* retval.replace(...); */ }
  // });
  //
  // // ❌ Java Toast 会闪退
  // Java.perform(function () { ... });
  //
  // // ❌ 不要动过关目标 InitScore / targetLevelScore（会变难）
  // // ❌ 不要只 hook ZScoreProgress.AddScore（HUD 与逻辑分可能不一致）
  // ---------------------------------------

  alog('learning skeleton ready (score x' + SCORE_MUL + ')');
}

function main() {
  alog('========== learning skeleton v6 start ==========');
  waitModule('libil2cpp.so', installLearning);
}

if (globalThis[FLAG]) {
  alog('already installed, skip');
} else {
  globalThis[FLAG] = true;
  setImmediate(main);
}
