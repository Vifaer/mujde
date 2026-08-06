package com.rel.mujde;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static android.content.Context.MODE_WORLD_READABLE;

public class InjectionRequestHandler extends BroadcastReceiver {
    /** once 模式下仅成功后写入 */
    private static final ConcurrentHashMap<Integer, Long> INJECTED_OK = new ConcurrentHashMap<>();
    /** delay + inject 期间占用 */
    private static final ConcurrentHashMap<Integer, Long> IN_FLIGHT = new ConcurrentHashMap<>();
    private static Handler sHandler;

    private static synchronized Handler worker() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("mujde-inject");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    static SharedPreferences loadPrefs(Context app) {
        try {
            return app.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_WORLD_READABLE);
        } catch (Exception e) {
            return app.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    static void markInjectedOk(int pid) {
        if (pid > 0) {
            INJECTED_OK.put(pid, System.currentTimeMillis());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        // 尽早拉起 FGS，避免后续校验期间进程被杀
        InjectionService.start(app);

        final InjectionRequest request = InjectionRequest.fromExtra(intent);
        if (request == null || request.getPid() < 1) {
            LogStore.append(app, "inject: 无效请求");
            return;
        }

        final String pkg = request.getPackageName();
        if (!PidOwner.isSafePackageName(pkg)) {
            LogStore.append(app, "inject: 非法包名 " + pkg);
            return;
        }

        final SharedPreferences prefs = loadPrefs(app);
        final boolean once = prefs.getBoolean(Constants.PREF_INJECT_ONCE, true);
        final int delayMs = prefs.getInt(Constants.PREF_INJECT_DELAY_MS, 0);
        final List<String> scripts = ScriptUtils.getScriptsForPackage(pkg, prefs);

        if (scripts == null || scripts.isEmpty()) {
            LogStore.append(app, "inject: 包 " + pkg + " 未绑定脚本");
            return;
        }

        final int pid = request.getPid();
        if (once && INJECTED_OK.containsKey(pid)) {
            LogStore.append(app, "inject: 跳过已成功 pid=" + pid);
            return;
        }
        if (IN_FLIGHT.putIfAbsent(pid, System.currentTimeMillis()) != null) {
            LogStore.append(app, "inject: 跳过 in-flight pid=" + pid);
            return;
        }

        PidOwner.Verdict verdict = PidOwner.check(pid, pkg);
        if (verdict == PidOwner.Verdict.MISMATCH) {
            IN_FLIGHT.remove(pid);
            LogStore.append(app, "inject: pid_owner_mismatch pid=" + pid + " pkg=" + pkg);
            return;
        }
        if (verdict == PidOwner.Verdict.UNKNOWN) {
            LogStore.append(app, "inject: pid_owner_unknown 放行 pid=" + pid + " pkg=" + pkg);
        }

        // 持有 PendingResult 直到注入结束（1.2.1 早 finish 会导致部分 ROM 丢掉延迟任务）
        final PendingResult pending = goAsync();
        worker().postDelayed(() -> {
            try {
                FridaInjector.Result r = FridaInjector.inject(app, prefs, pid, pkg, scripts, true);
                if (r.ok && once) {
                    markInjectedOk(pid);
                }
            } finally {
                IN_FLIGHT.remove(pid);
                pending.finish();
            }
        }, Math.max(0, delayMs));
    }

    public static String injectNow(Context context, String packageName) {
        Context app = context.getApplicationContext();
        InjectionService.start(app);
        SharedPreferences prefs = loadPrefs(app);
        if (!PidOwner.isSafePackageName(packageName)) {
            String msg = "立即注入失败：非法包名";
            LogStore.append(app, msg);
            return msg;
        }
        List<String> scripts = ScriptUtils.getScriptsForPackage(packageName, prefs);
        if (scripts == null || scripts.isEmpty()) {
            String msg = "立即注入失败：未绑定脚本 " + packageName;
            LogStore.append(app, msg);
            return msg;
        }
        List<Integer> pids = FridaInjector.resolvePids(packageName);
        if (pids.isEmpty()) {
            String msg = "立即注入失败：进程未运行 " + packageName;
            LogStore.append(app, msg);
            return msg;
        }
        boolean once = prefs.getBoolean(Constants.PREF_INJECT_ONCE, true);
        StringBuilder sb = new StringBuilder();
        for (int pid : pids) {
            if (IN_FLIGHT.putIfAbsent(pid, System.currentTimeMillis()) != null) {
                sb.append("pid=").append(pid).append(" SKIP in-flight\n");
                continue;
            }
            try {
                FridaInjector.Result r = FridaInjector.inject(app, prefs, pid, packageName, scripts, true);
                if (r.ok && once) {
                    markInjectedOk(pid);
                }
                sb.append("pid=").append(pid).append(' ').append(r.ok ? "OK" : "FAIL")
                        .append(" exit=").append(r.exitCode).append('\n');
            } finally {
                IN_FLIGHT.remove(pid);
            }
        }
        return sb.toString().trim();
    }
}
