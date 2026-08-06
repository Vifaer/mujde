/**
 * Mujde / Frida — 神秘元素消消乐 反作弊/存档只读观测
 * target: com.zcs.udpmysteryforest
 * game: 1.0.3 arm64
 * mode: READ-ONLY
 * mujde_flag: __MF_READONLY_AC_V1__
 *
 * 观察: OnCheatDetected / userCheatCount / SaveToLocal / ZXOR
 * 不 hook InternalDecrypt（过高频）；需要时自行加 RVA 0x8B8B84
 */

'use strict';

const FLAG = '__MF_READONLY_AC_V1__';

const RVA = {
  OnCheatDetected: 0x6f6d38,
  get_userCheatCount: 0x825eb4,
  set_userCheatCount: 0x825ebc,
  get_detectCheat: 0x6f65c0,
  SaveToLocal: 0x82943c,
  LoadFromLocal: 0x828fa0,
  EncryptDecrypt: 0x115dfe0,
  GetCoins: 0x81b694,
  b20_DataChanged: 0x115908c,
};

function modIl2cpp() {
  const m = Process.findModuleByName('libil2cpp.so');
  if (!m) throw new Error('libil2cpp.so not loaded');
  return m;
}

function hookRva(name, rva, callbacks) {
  const addr = modIl2cpp().base.add(rva);
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

  console.log('[READONLY] anticheat hooks installing');

  hookRva('OnCheatDetected', RVA.OnCheatDetected, {
    onEnter() {
      console.log('[READONLY][!] OnCheatDetected');
      console.log(
        Thread.backtrace(this.context, Backtracer.ACCURATE)
          .map(DebugSymbol.fromAddress)
          .slice(0, 8)
          .join('\n')
      );
    },
  });

  hookRva('set_userCheatCount', RVA.set_userCheatCount, {
    onEnter(args) {
      console.log('[READONLY][set_userCheatCount] this=' + args[0] + ' value=' + args[1].toInt32());
    },
  });

  hookRva('get_userCheatCount', RVA.get_userCheatCount, {
    onLeave(retval) {
      const n = retval.toInt32();
      if (n !== 0) console.log('[READONLY][get_userCheatCount] => ' + n);
    },
  });

  let detectLogged = false;
  hookRva('get_detectCheat', RVA.get_detectCheat, {
    onLeave(retval) {
      if (!detectLogged) {
        detectLogged = true;
        console.log('[READONLY][detectCheat] => ' + retval.toInt32());
      }
    },
  });

  hookRva('DataChanged/b__20', RVA.b20_DataChanged, {
    onEnter() {
      console.log('[READONLY][DataChanged] dirty flags path');
    },
  });

  hookRva('SaveToLocal', RVA.SaveToLocal, {
    onEnter() {
      console.log('[READONLY][SaveToLocal] enter');
    },
    onLeave(retval) {
      console.log('[READONLY][SaveToLocal] leave ret=' + retval);
    },
  });

  hookRva('LoadFromLocal', RVA.LoadFromLocal, {
    onEnter() {
      console.log('[READONLY][LoadFromLocal] enter');
    },
  });

  hookRva('ZXOR.EncryptDecrypt', RVA.EncryptDecrypt, {
    onEnter(args) {
      try {
        const s = args[0];
        if (!s.isNull()) {
          const len = s.add(0x10).readS32();
          const preview = len > 0 ? s.add(0x14).readUtf16String(Math.min(len, 80)) : '';
          console.log('[READONLY][XOR] len=' + len + ' preview=' + JSON.stringify(preview));
        }
      } catch (e) {
        console.log('[READONLY][XOR] parse fail ' + e);
      }
    },
  });

  hookRva('GetCoins', RVA.GetCoins, {
    onLeave(retval) {
      console.log('[READONLY][GetCoins] => ' + retval);
    },
  });

  console.log('[READONLY] anticheat hooks ready');
}

if (globalThis[FLAG]) {
  console.log('[READONLY] anticheat script already installed, skip');
} else {
  globalThis[FLAG] = true;
  setImmediate(waitAndInstall);
}
