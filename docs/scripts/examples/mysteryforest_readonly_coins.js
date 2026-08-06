/**
 * Mujde / Frida — 神秘元素消消乐 货币相关只读观测
 * target: com.zcs.udpmysteryforest
 * game: 1.0.3 arm64 (Il2CppDumper RVA)
 * mode: READ-ONLY（不改参、不改返回值）
 * mujde_flag: __MF_READONLY_COINS_V1__
 *
 * 放入 /data/data/com.rel.mujde/files/scripts/ 后，在 Mujde Apps 中勾选本脚本。
 * logcat: adb logcat | grep READONLY
 */

'use strict';

const FLAG = '__MF_READONLY_COINS_V1__';

const RVA = {
  GetCoins: 0x81B694,
  ModifyCoins: 0x826D1C,
  SetCoins: 0x826E44,
  GetGems: 0x826F00,
  SetGems: 0x826F9C,
  ModifyGems: 0x827058,
  GetOwlCoin: 0x827180,
  ZObscuredLong_Encrypt: 0x8B8610,
  ZObscuredLong_Decrypt: 0x8B89CC,
};

function modIl2cpp() {
  const m = Process.findModuleByName('libil2cpp.so');
  if (!m) throw new Error('libil2cpp.so not loaded');
  return m;
}

function hookRva(name, rva, callbacks) {
  const base = modIl2cpp().base;
  const addr = base.add(rva);
  Interceptor.attach(addr, callbacks);
  console.log('[READONLY][+] ' + name + ' @ ' + addr);
}

function waitAndInstall() {
  try {
    modIl2cpp();
  } catch (e) {
    setTimeout(waitAndInstall, 500);
    return;
  }

  console.log('[READONLY] coins hooks installing');

  hookRva('ZObscuredLong.Encrypt', RVA.ZObscuredLong_Encrypt, {
    onEnter(args) {
      this.v = args[0];
    },
    onLeave(retval) {
      console.log('[READONLY][Encrypt] in=' + this.v + ' out=' + retval);
    },
  });

  hookRva('ZObscuredLong.Decrypt', RVA.ZObscuredLong_Decrypt, {
    onEnter(args) {
      this.v = args[0];
    },
    onLeave(retval) {
      console.log('[READONLY][Decrypt] in=' + this.v + ' out=' + retval);
    },
  });

  hookRva('GetCoins', RVA.GetCoins, {
    onLeave(retval) {
      console.log('[READONLY][GetCoins] => ' + retval);
    },
  });

  hookRva('GetGems', RVA.GetGems, {
    onLeave(retval) {
      console.log('[READONLY][GetGems] => ' + retval);
    },
  });

  hookRva('GetOwlCoin', RVA.GetOwlCoin, {
    onLeave(retval) {
      console.log('[READONLY][GetOwlCoin] => ' + retval.toInt32());
    },
  });

  hookRva('ModifyCoins', RVA.ModifyCoins, {
    onEnter(args) {
      console.log('[READONLY][ModifyCoins] this=' + args[0] + ' delta=' + args[1]);
    },
  });

  hookRva('ModifyGems', RVA.ModifyGems, {
    onEnter(args) {
      console.log('[READONLY][ModifyGems] this=' + args[0] + ' delta=' + args[1]);
    },
  });

  console.log('[READONLY] coins hooks ready');
}

if (globalThis[FLAG]) {
  console.log('[READONLY] coins script already installed, skip');
} else {
  globalThis[FLAG] = true;
  setImmediate(waitAndInstall);
}
