/**
 * Mujde / Frida — 最小连通性测试
 * target: 任意已在 Mujde Apps 中勾选本脚本的包
 * mode: READ-ONLY（无 hook）
 * mujde_flag: __MUJDE_HELLO_V1__
 *
 * 成功标志：logcat 出现 [MujdeExample] hello
 */

'use strict';

const FLAG = '__MUJDE_HELLO_V1__';

if (globalThis[FLAG]) {
  console.log('[MujdeExample] hello already ran, skip');
} else {
  globalThis[FLAG] = true;
  setImmediate(function () {
    var pid = Process.id;
    var arch = Process.arch;
    console.log('[MujdeExample] hello pid=' + pid + ' arch=' + arch);
    try {
      var il2 = Process.findModuleByName('libil2cpp.so');
      console.log('[MujdeExample] libil2cpp=' + (il2 ? il2.base : 'not-loaded-yet'));
    } catch (e) {
      console.log('[MujdeExample] module check: ' + e);
    }
  });
}
