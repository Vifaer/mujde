package com.rel.mujde;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** 脚本模板向导：hello / skeleton / skeleton+antifrida 提示。 */
public final class ScriptTemplates {
    private ScriptTemplates() {}

    public static final String HELLO = "mujde_hello.js";
    public static final String SKELETON = "mujde_skeleton.js";
    public static final String SKELETON_AF = "mujde_skeleton_antifrida_hint.js";

    public static String create(Context context, String kind) throws IOException {
        String name;
        String body;
        switch (kind) {
            case "skeleton":
                name = SKELETON;
                body = skeletonBody("MUJDE_SKELETON", "__MUJDE_SKELETON_V1__");
                break;
            case "skeleton_af":
                name = SKELETON_AF;
                body = "// 提示：首页打开「自动附带反 Frida」即可在同一次 inject 前置 bypass，\n"
                        + "// 无需把 antifrida 复制进本文件。本模板仅作骨架 + 说明。\n"
                        + skeletonBody("MUJDE_SKELETON_AF", "__MUJDE_SKELETON_AF_V1__");
                break;
            case "hello":
            default:
                name = HELLO;
                body = helloBody();
                break;
        }
        File dest = ScriptUtils.getScriptFile(context, name);
        if (dest.exists()) {
            // 同名则加后缀
            String base = name.substring(0, name.length() - 3);
            int i = 2;
            while (true) {
                File alt = ScriptUtils.getScriptFile(context, base + "_" + i + ".js");
                if (!alt.exists()) {
                    dest = alt;
                    name = alt.getName();
                    break;
                }
                i++;
            }
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(dest))) {
            w.write(body);
        }
        AccessibilityUtils.makeFileWorldReadable(dest);
        return name;
    }

    private static String helloBody() {
        return "/** Mujde hello 模板 */\n"
                + "'use strict';\n"
                + "var FLAG = '__MUJDE_HELLO_V1__';\n"
                + "var TAG = 'MUJDE_HELLO';\n"
                + "function alog(msg) {\n"
                + "  var line = '[' + TAG + '] ' + msg;\n"
                + "  console.log(line);\n"
                + "  try {\n"
                + "    var fn = new NativeFunction(Module.findExportByName('liblog.so', '__android_log_write'),\n"
                + "      'int', ['int', 'pointer', 'pointer']);\n"
                + "    fn(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(line));\n"
                + "  } catch (e) {}\n"
                + "}\n"
                + "if (globalThis[FLAG]) { alog('skip'); } else {\n"
                + "  globalThis[FLAG] = true;\n"
                + "  setImmediate(function () { alog('hello from Mujde'); });\n"
                + "}\n";
    }

    private static String skeletonBody(String tag, String flag) {
        return "/** Mujde skeleton 模板 — 无 hook，仅验证注入存活 */\n"
                + "'use strict';\n"
                + "var FLAG = '" + flag + "';\n"
                + "var TAG = '" + tag + "';\n"
                + "function alog(msg) {\n"
                + "  var line = '[' + TAG + '] ' + msg;\n"
                + "  console.log(line);\n"
                + "  try {\n"
                + "    var fn = new NativeFunction(Module.findExportByName('liblog.so', '__android_log_write'),\n"
                + "      'int', ['int', 'pointer', 'pointer']);\n"
                + "    fn(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(line));\n"
                + "  } catch (e) {}\n"
                + "}\n"
                + "function waitModule(name, cb) {\n"
                + "  var m = Process.findModuleByName(name);\n"
                + "  if (m) { cb(m); return; }\n"
                + "  setTimeout(function () { waitModule(name, cb); }, 300);\n"
                + "}\n"
                + "if (globalThis[FLAG]) { alog('skip re-inject'); } else {\n"
                + "  globalThis[FLAG] = true;\n"
                + "  setImmediate(function () {\n"
                + "    alog('skeleton loaded pid=' + Process.id);\n"
                + "    // waitModule('libil2cpp.so', function (m) { alog('il2cpp @ ' + m.base); });\n"
                + "  });\n"
                + "}\n";
    }
}
