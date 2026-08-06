package com.rel.mujde;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一键健康检查：su / so / 脚本 / scope / 上次注入。 */
public final class HealthCheck {
    private HealthCheck() {}

    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        public boolean allOk = true;

        void ok(String s) { lines.add("[OK] " + s); }
        void fail(String s) { allOk = false; lines.add("[FAIL] " + s); }
        void info(String s) { lines.add("[--] " + s); }

        public String asText() {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            sb.append(allOk ? "\n结论：基本健康" : "\n结论：存在问题，请按 FAIL 项处理");
            return sb.toString().trim();
        }
    }

    public static Report run(Context context, SharedPreferences prefs) {
        Context app = context.getApplicationContext();
        Report r = new Report();

        r.info("版本 " + BuildConfig.VERSION_NAME + "  Frida " + BuildConfig.FRIDA_VERSION);

        boolean su = RootShell.canSu();
        if (su) r.ok("Root su 可用");
        else {
            r.fail("Root su 不可用 — " + RootGuide.deniedHint());
        }

        File inj = new File(app.getApplicationInfo().nativeLibraryDir + "/libfrida-inject.so");
        if (inj.exists() && inj.length() > 1_000_000) {
            r.ok("libfrida-inject.so 存在 (" + (inj.length() / 1024) + " KB)");
        } else {
            r.fail("缺少或异常的 libfrida-inject.so: " + inj.getAbsolutePath());
        }

        List<String> scripts = ScriptUtils.listScriptNames(app);
        if (scripts.isEmpty()) r.fail("脚本库为空");
        else r.ok("脚本数量 " + scripts.size());

        Map<String, List<String>> map = ScriptUtils.getAllAppScriptMappings(prefs);
        r.info("已绑定应用 " + map.size());

        if (su) {
            Set<String> scoped = ScopeHelper.readScopedPackages(app);
            int apps = 0;
            for (String p : scoped) {
                if (!"system".equals(p) && !"android".equals(p)) apps++;
            }
            r.ok("LSPosed 作用域应用数 " + apps);
            for (String pkg : map.keySet()) {
                if (!scoped.contains(pkg)) {
                    r.fail("已绑定但未在作用域: " + pkg);
                }
            }
        } else {
            r.info("跳过 scope 探测（无 su）");
        }

        String last = prefs.getString(Constants.PREF_LAST_INJECT_SUMMARY, "无");
        if (last != null && last.startsWith("OK")) r.ok("最近注入: " + last);
        else if (last == null || "无".equals(last)) r.info("最近注入: 无");
        else r.fail("最近注入异常: " + last);

        boolean bridge = prefs.getBoolean(Constants.PREF_CONSOLE_BRIDGE, true);
        r.info("console 桥: " + (bridge ? "开" : "关")
                + "  TAG=" + prefs.getString(Constants.PREF_SCRIPT_LOG_TAG, Constants.DEFAULT_SCRIPT_LOG_TAG));
        r.info("反 Frida: " + (prefs.getBoolean(Constants.PREF_ANTIFRIDA, false) ? "开" : "关")
                + "  激进档: " + (prefs.getBoolean(Constants.PREF_ANTIFRIDA_AGGRESSIVE, false) ? "开" : "关"));

        File svcHint = new File(app.getFilesDir(), "scripts");
        r.info("脚本目录 " + svcHint.getAbsolutePath());

        return r;
    }
}
