package com.rel.mujde;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对目标 PID 执行一次 frida-inject。
 * 合并顺序：console 桥 → 反 Frida（可选）→ 用户脚本。
 */
public final class FridaInjector {
    private static final String TAG = "Mujde";

    private FridaInjector() {}

    public static final class Result {
        public final int exitCode;
        public final String summary;
        public final boolean ok;

        public Result(int exitCode, String summary) {
            this.exitCode = exitCode;
            this.summary = summary;
            this.ok = exitCode == 0;
        }
    }

    public static List<Integer> resolvePids(String packageName) {
        List<Integer> pids = new ArrayList<>();
        if (packageName == null || packageName.isEmpty()) return pids;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "pidof " + packageName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append(' ');
            }
            p.waitFor(5, TimeUnit.SECONDS);
            for (String tok : out.toString().trim().split("\\s+")) {
                if (tok.isEmpty()) continue;
                try {
                    pids.add(Integer.parseInt(tok));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pidof failed: " + e.getMessage());
        }
        return pids;
    }

    public static Result inject(Context app, SharedPreferences prefs, int pid, String packageName, List<String> scripts) {
        final String injector = app.getApplicationInfo().nativeLibraryDir + "/libfrida-inject.so";
        File inj = new File(injector);
        if (!inj.exists()) {
            return new Result(-1, "缺少 libfrida-inject.so");
        }
        if (scripts == null || scripts.isEmpty()) {
            return new Result(-1, "无脚本");
        }

        List<File> existing = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String script : scripts) {
            File f = ScriptUtils.getScriptFile(app, script);
            if (f.exists() && f.isFile()) {
                existing.add(f);
                names.add(script);
            } else {
                LogStore.append(app, "inject 跳过缺失脚本: " + script);
            }
        }
        if (existing.isEmpty()) {
            return new Result(-1, "脚本文件均不存在");
        }

        File bundle;
        List<String> parts = new ArrayList<>();
        try {
            bundle = buildBundle(app, prefs, existing, names, parts);
        } catch (Exception e) {
            return new Result(-1, "合并脚本失败: " + e.getMessage());
        }

        String msg = "about to frida-inject [" + String.join(", ", parts) + "] pid=" + pid + " pkg=" + packageName;
        Log.d(TAG, msg);
        LogStore.append(app, msg);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "su", "-c",
                    injector + " -e -p " + pid + " -s " + bundle.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > 64 * 1024) break;
                }
            }

            boolean finished;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                finished = process.waitFor(Constants.INJECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
                finished = true;
            }

            if (!finished) {
                process.destroy();
                String summary = "TIMEOUT pkg=" + packageName + " scripts=" + names;
                LogStore.append(app, "inject TIMEOUT " + summary);
                saveLast(prefs, summary);
                return new Result(-1, summary);
            }

            int code = process.exitValue();
            String summary = "inject exit=" + code + " scripts=" + names + " pkg=" + packageName;
            if (out.length() > 0) {
                summary += " out=" + out.toString().trim();
            }
            LogStore.append(app, summary);
            saveLast(prefs, (code == 0 ? "OK " : "FAIL ") + packageName + " exit=" + code);
            // 注入后顺便吸入 console 桥文件
            LogStore.ingestConsoleBridge(app);
            return new Result(code, summary);
        } catch (Exception e) {
            String err = "注入异常: " + e.getMessage();
            Log.d(TAG, err);
            LogStore.append(app, err);
            saveLast(prefs, "ERROR " + e.getMessage());
            return new Result(-1, err);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            bundle.delete();
        }
    }

    private static File buildBundle(
            Context app,
            SharedPreferences prefs,
            List<File> files,
            List<String> names,
            List<String> partsOut) throws Exception {
        File dir = new File(app.getCacheDir(), "inject_bundles");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File bundle = File.createTempFile("mujde_bundle_", ".js", dir);
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(bundle), StandardCharsets.UTF_8))) {
            w.write("/* Mujde 自动合并脚本包 — 请勿手改 */\n");
            w.write("'use strict';\n");

            boolean bridge = prefs == null || prefs.getBoolean(Constants.PREF_CONSOLE_BRIDGE, true);
            if (bridge) {
                String tag = prefs != null
                        ? prefs.getString(Constants.PREF_SCRIPT_LOG_TAG, Constants.DEFAULT_SCRIPT_LOG_TAG)
                        : Constants.DEFAULT_SCRIPT_LOG_TAG;
                if (tag == null || tag.trim().isEmpty()) tag = Constants.DEFAULT_SCRIPT_LOG_TAG;
                String bridgeJs = readAsset(app, "mujde_console_bridge.js")
                        .replace("__MUJDE_LOG_TAG__", tag.trim());
                w.write("\n/* ---- BEGIN console_bridge ---- */\n");
                w.write(bridgeJs);
                w.write("\n/* ---- END console_bridge ---- */\n");
                partsOut.add("console_bridge");
            }

            boolean anti = prefs != null && prefs.getBoolean(Constants.PREF_ANTIFRIDA, false);
            if (anti) {
                w.write("\n/* ---- BEGIN antifrida_generic ---- */\n");
                w.write(readAsset(app, "antifrida_generic.js"));
                w.write("\n/* ---- END antifrida_generic ---- */\n");
                partsOut.add("antifrida");
                if (prefs.getBoolean(Constants.PREF_ANTIFRIDA_AGGRESSIVE, false)) {
                    w.write("\n/* ---- BEGIN antifrida_aggressive ---- */\n");
                    w.write(readAsset(app, "antifrida_aggressive.js"));
                    w.write("\n/* ---- END antifrida_aggressive ---- */\n");
                    partsOut.add("antifrida_agg");
                }
            }

            for (int i = 0; i < files.size(); i++) {
                w.write("\n/* ---- BEGIN " + names.get(i) + " ---- */\n");
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(files.get(i)), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        w.write(line);
                        w.write('\n');
                    }
                }
                w.write("/* ---- END " + names.get(i) + " ---- */\n");
                partsOut.add(names.get(i));
            }
        }
        AccessibilityUtils.makeFileWorldReadable(bundle);
        return bundle;
    }

    private static String readAsset(Context app, String name) throws Exception {
        try (InputStream in = app.getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static void saveLast(SharedPreferences prefs, String summary) {
        if (prefs != null) {
            prefs.edit().putString(Constants.PREF_LAST_INJECT_SUMMARY, summary).apply();
        }
    }
}
