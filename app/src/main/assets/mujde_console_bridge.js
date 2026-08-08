/* Mujde console bridge v1.1 — 注入包最前自动附带
 * 将 console 写入 /data/local/tmp/mujde-console.log，并用 liblog 打 TAG。
 * 注意：Frida 的 send 为只读，禁止重写（否则整包脚本加载失败）。
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
          var bytes = Memory.allocUtf8String(line + '\n');
          var n = 0;
          try { n = bytes.readUtf8String().length; } catch (e3) { n = line.length + 1; }
          writePtr(fd, bytes, n);
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

  try {
    var origLog = console.log;
    var origWarn = console.warn;
    var origError = console.error;
    console.log = function () {
      alog(stringify(arguments));
      try { return origLog.apply(console, arguments); } catch (e) {}
    };
    console.warn = function () {
      alog('WARN ' + stringify(arguments));
      try { return origWarn.apply(console, arguments); } catch (e) {}
    };
    console.error = function () {
      alog('ERR ' + stringify(arguments));
      try { return origError.apply(console, arguments); } catch (e) {}
    };
  } catch (e) {
    alog('console wrap skipped: ' + e);
  }

  // 不重写全局 send（Frida 17+ 为 read-only，赋值会炸整包）

  alog('console bridge ready tag=' + TAG);
})();
