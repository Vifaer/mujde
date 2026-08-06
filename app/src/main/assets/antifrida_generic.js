/* Mujde antifrida_generic.js v1.0.0
 * 边界说明（请先读）：
 * - 仅针对「端口 / 文件名 / 进程名 / maps 关键字 / 常见 Java 读文件」等经典启发式。
 * - 默认不启用；需首页开关打开后才会并入同一次 frida-inject。
 * - Activity.onCreate 之后注入，无法覆盖启动早期检测；强对抗请用 ZygiskFrida / Gadget。
 * - 不承诺绕过商业 RASP / 自研高强度反调试。仅供安全研究与自有应用测试。
 * - 激进档（Runtime.exec 等）由宿主再拼一段 antifrida_aggressive.js，默认关。
 */
'use strict';
(function () {
  if (globalThis.__MUJDE_ANTIFRIDA_BASIC__) return;
  globalThis.__MUJDE_ANTIFRIDA_BASIC__ = true;

  var TAG = 'MUJDE_ANTIFRIDA';
  function alog(msg) {
    var line = '[' + TAG + '] ' + msg;
    console.log(line);
    try {
      var fn = new NativeFunction(
        Module.findExportByName('liblog.so', '__android_log_write'),
        'int', ['int', 'pointer', 'pointer']
      );
      fn(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(line));
    } catch (e) {}
  }

  var BAD_SUBSTR = [
    'frida', 'FRIDA', 'gum-js', 'gmain', 'gdbus', 'linjector',
    'frida-agent', 'frida-server', 'frida-helper', 'hluda', 'libfrida'
  ];
  var BAD_PORTS = { 27042: 1, 27043: 1 };

  function looksBad(s) {
    if (!s) return false;
    var t = String(s).toLowerCase();
    for (var i = 0; i < BAD_SUBSTR.length; i++) {
      if (t.indexOf(BAD_SUBSTR[i].toLowerCase()) >= 0) return true;
    }
    return false;
  }

  // libc: open / openat — 拦截常见 frida 路径探测
  function hookOpen(name) {
    try {
      var p = Module.findExportByName(null, name);
      if (!p) return;
      Interceptor.attach(p, {
        onEnter: function (args) {
          try {
            var path = name.indexOf('openat') >= 0 ? args[1].readUtf8String() : args[0].readUtf8String();
            this.block = looksBad(path);
            if (this.block) alog('block ' + name + ' ' + path);
          } catch (e) { this.block = false; }
        },
        onLeave: function (retval) {
          if (this.block) retval.replace(ptr(-1));
        }
      });
    } catch (e) { alog('hook ' + name + ' fail: ' + e); }
  }
  hookOpen('open');
  hookOpen('openat');

  // connect — 挡默认 frida 端口
  try {
    var connectPtr = Module.findExportByName(null, 'connect');
    if (connectPtr) {
      Interceptor.attach(connectPtr, {
        onEnter: function (args) {
          this.block = false;
          try {
            var sa = args[1];
            var family = sa.readU16();
            if (family === 2) { // AF_INET
              var port = (sa.add(2).readU8() << 8) | sa.add(3).readU8();
              if (BAD_PORTS[port]) {
                this.block = true;
                alog('block connect port ' + port);
              }
            }
          } catch (e) {}
        },
        onLeave: function (retval) {
          if (this.block) retval.replace(ptr(-1));
        }
      });
    }
  } catch (e) { alog('hook connect fail: ' + e); }

  // Java 层：读文件 / 读 maps 字符串过滤
  function installJava() {
    if (!Java.available) return;
    Java.perform(function () {
      try {
        var File = Java.use('java.io.File');
        var fileExists = File.exists;
        File.exists.implementation = function () {
          var n = this.getAbsolutePath();
          if (looksBad(n)) {
            alog('File.exists fake false: ' + n);
            return false;
          }
          return fileExists.call(this);
        };
      } catch (e) {}

      try {
        var BufferedReader = Java.use('java.io.BufferedReader');
        var readLine0 = BufferedReader.readLine.overload();
        readLine0.implementation = function () {
          var line = readLine0.call(this);
          if (line !== null && looksBad(line)) {
            alog('BufferedReader skip bad line');
            return readLine0.call(this);
          }
          return line;
        };
      } catch (e) {}
    });
  }

  if (Java.available) {
    installJava();
  } else {
    setTimeout(installJava, 300);
  }

  alog('basic antifrida enabled');
})();
