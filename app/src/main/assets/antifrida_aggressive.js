/* Mujde antifrida_aggressive.js v1.0.0 — 默认关
 * 更宽的 Runtime.exec / ProcessBuilder 过滤，可能误伤正常功能。
 */
'use strict';
(function () {
  if (globalThis.__MUJDE_ANTIFRIDA_AGG__) return;
  globalThis.__MUJDE_ANTIFRIDA_AGG__ = true;

  function looksBad(s) {
    if (!s) return false;
    var t = String(s).toLowerCase();
    return t.indexOf('frida') >= 0 || t.indexOf('hluda') >= 0 || t.indexOf('gum-js') >= 0;
  }

  function install() {
    if (!Java.available) return;
    Java.perform(function () {
      try {
        var Runtime = Java.use('java.lang.Runtime');
        var execArr = Runtime.exec.overload('[Ljava.lang.String;');
        var execStr = Runtime.exec.overload('java.lang.String');
        execArr.implementation = function (cmd) {
          var joined = '';
          try { joined = Java.use('java.util.Arrays').toString(cmd); } catch (e) {}
          if (looksBad(joined)) {
            console.log('[MUJDE_ANTIFRIDA] block Runtime.exec ' + joined);
            throw Java.use('java.io.IOException').$new('Permission denied');
          }
          return execArr.call(this, cmd);
        };
        execStr.implementation = function (cmd) {
          if (looksBad(cmd)) {
            console.log('[MUJDE_ANTIFRIDA] block Runtime.exec ' + cmd);
            throw Java.use('java.io.IOException').$new('Permission denied');
          }
          return execStr.call(this, cmd);
        };
      } catch (e) {
        console.log('[MUJDE_ANTIFRIDA] aggressive Runtime hook fail: ' + e);
      }
      try {
        var PB = Java.use('java.lang.ProcessBuilder');
        var start0 = PB.start;
        PB.start.implementation = function () {
          var cmd = this.command();
          if (looksBad(String(cmd))) {
            console.log('[MUJDE_ANTIFRIDA] block ProcessBuilder ' + cmd);
            throw Java.use('java.io.IOException').$new('Permission denied');
          }
          return start0.call(this);
        };
      } catch (e2) {}
    });
  }

  if (Java.available) install();
  else setTimeout(install, 400);
  console.log('[MUJDE_ANTIFRIDA] aggressive enabled');
})();
