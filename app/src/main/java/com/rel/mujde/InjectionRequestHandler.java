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
    private static final ConcurrentHashMap<Integer, Long> INJECTED_PIDS = new ConcurrentHashMap<>();
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

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        InjectionService.start(app);

        final InjectionRequest request = InjectionRequest.fromExtra(intent);
        if (request == null) {
            LogStore.append(app, "inject: 无效请求");
            return;
        }

        final SharedPreferences prefs = loadPrefs(app);
        final boolean once = prefs.getBoolean(Constants.PREF_INJECT_ONCE, true);
        final int delayMs = prefs.getInt(Constants.PREF_INJECT_DELAY_MS, 0);
        final List<String> scripts = ScriptUtils.getScriptsForPackage(request.getPackageName(), prefs);

        if (scripts == null || scripts.isEmpty()) {
            LogStore.append(app, "inject: 包 " + request.getPackageName() + " 未绑定脚本");
            return;
        }

        if (once) {
            Long prev = INJECTED_PIDS.putIfAbsent(request.getPid(), System.currentTimeMillis());
            if (prev != null) {
                LogStore.append(app, "inject: 跳过重复 pid=" + request.getPid());
                return;
            }
        }

        final PendingResult pending = goAsync();
        worker().postDelayed(() -> {
            try {
                FridaInjector.inject(app, prefs, request.getPid(), request.getPackageName(), scripts);
            } finally {
                pending.finish();
            }
        }, Math.max(0, delayMs));
    }

    /**
     * 立即注入：忽略「每进程一次」去重，对包名下所有 PID 各执行一次合并注入。
     *
     * @return 人类可读结果摘要
     */
    public static String injectNow(Context context, String packageName) {
        Context app = context.getApplicationContext();
        InjectionService.start(app);
        SharedPreferences prefs = loadPrefs(app);
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
        StringBuilder sb = new StringBuilder();
        for (int pid : pids) {
            FridaInjector.Result r = FridaInjector.inject(app, prefs, pid, packageName, scripts);
            sb.append("pid=").append(pid).append(' ').append(r.ok ? "OK" : "FAIL")
                    .append(" exit=").append(r.exitCode).append('\n');
        }
        return sb.toString().trim();
    }
}
