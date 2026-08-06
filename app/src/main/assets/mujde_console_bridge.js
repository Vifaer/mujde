/* Mujde console bridge v1 — 注入包最前自动附带
 * 将 console / send 写入 /data/local/tmp/mujde-console.log，并用 liblog 打 TAG。
 * TAG 由宿主替换占位符 __MUJDE_LOG_TAG__。
 */
'use strict';
(function () {
  if (globalThis.__MUJDE_CONSOLE_BRIDGE__) return;
  globalThis.__MUJDE_CONSOLE_BRIDGE__ = true;

  var TAG = '__MUJDE_LOG_TAG__';
  var PATH = '/data/local/tmp/mujde-console.log';
  var writePtr = null;
  var openPtr = null;
  var closePtr = null;

  try {
    openPtr = new NativeFunction(Module.findExportByName(null, 'open'), 'int', ['pointer', 'int', 'int']);
    writePtr = new NativeFunction(Module.findExportByName(null, 'write'), 'int', ['int', 'pointer', 'int']);
    closePtr = new NativeFunction(Module.findExportByName(null, 'close'), 'int', ['int']);
  } catch (e) {}

  function alog(msg) {
    var line = '[' + TAG + '] ' + msg;
    try {
      var fn = new NativeFunction(
        Module.findExportByName('liblog.so', '__android_log_write'),
        'int', ['int', 'pointer', 'pointer']
      );
      fn(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(line));
    } catch (e) {}
    try {
      if (openPtr && writePtr && closePtr) {
        var O_WRONLY = 1, O_CREAT = 64, O_APPEND = 1024;
        var fd = openPtr(Memory.allocUtf8String(PATH), O_WRONLY | O_CREAT | O_APPEND, 0x1b6);
        if (fd >= 0) {
          var payload = Memory.allocUtf8String(line + '\n');
          writePtr(fd, payload, line.length + 1);
          closePtr(fd);
        }
      }
    } catch (e2) {}
  }

  function stringify(args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      try {
        parts.push(typeof args[i] === 'object' ? JSON.stringify(args[i]) : String(args[i]));
      } catch (e) {
        parts.push(String(args[i]));
      }
    }
    return parts.join(' ');
  }

  var orig = {
    log: console.log,
    warn: console.warn,
    error: console.error
  };
  console.log = function () { var s = stringify(arguments); alog(s); try { orig.log.apply(console, arguments); } catch (e) {} };
  console.warn = function () { var s = 'WARN ' + stringify(arguments); alog(s); try { orig.warn.apply(console, arguments); } catch (e) {} };
  console.error = function () { var s = 'ERR ' + stringify(arguments); alog(s); try { orig.error.apply(console, arguments); } catch (e) {} };

  if (typeof send === 'function') {
    var origSend = send;
    send = function (payload, data) {
      try { alog('send ' + (typeof payload === 'string' ? payload : JSON.stringify(payload))); } catch (e) {}
      return origSend(payload, data);
    };
  }

  alog('console bridge ready tag=' + TAG);
})();
