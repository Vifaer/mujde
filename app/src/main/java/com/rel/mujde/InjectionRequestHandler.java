package com.rel.mujde;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static android.content.Context.MODE_WORLD_READABLE;

public class InjectionRequestHandler extends BroadcastReceiver {
    private static final String TAG = "Mujde";
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

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        InjectionService.start(app);

        final InjectionRequest request = InjectionRequest.fromExtra(intent);
        if (request == null) {
            LogStore.append(app, "inject: invalid request extras");
            return;
        }

        final SharedPreferences prefs;
        SharedPreferences loaded;
        try {
            loaded = app.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_WORLD_READABLE);
        } catch (Exception e) {
            loaded = app.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE);
        }
        prefs = loaded;

        final boolean once = prefs.getBoolean(Constants.PREF_INJECT_ONCE, true);
        final int delayMs = prefs.getInt(Constants.PREF_INJECT_DELAY_MS, 0);
        final List<String> scripts = ScriptUtils.getScriptsForPackage(request.getPackageName(), prefs);

        if (scripts == null || scripts.isEmpty()) {
            LogStore.append(app, "inject: no scripts for " + request.getPackageName());
            return;
        }

        if (once) {
            Long prev = INJECTED_PIDS.putIfAbsent(request.getPid(), System.currentTimeMillis());
            if (prev != null) {
                LogStore.append(app, "inject: skip duplicate pid=" + request.getPid());
                return;
            }
        }

        final PendingResult pending = goAsync();
        worker().postDelayed(() -> {
            try {
                runInjection(app, prefs, request, scripts);
            } finally {
                pending.finish();
            }
        }, Math.max(0, delayMs));
    }

    private void runInjection(Context app, SharedPreferences prefs, InjectionRequest request, List<String> scripts) {
        final String injector = app.getApplicationInfo().nativeLibraryDir + "/libfrida-inject.so";
        File inj = new File(injector);
        if (!inj.exists()) {
            LogStore.append(app, "inject ERROR: missing " + injector);
            return;
        }

        for (String script : scripts) {
            File scriptFile = ScriptUtils.getScriptFile(app, script);
            if (!scriptFile.exists()) {
                LogStore.append(app, "inject ERROR: script missing " + script);
                continue;
            }

            String msg = "about to frida-inject " + script + " into " + request;
            Log.d(TAG, msg);
            LogStore.append(app, msg);

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "su", "-c",
                        injector + " -e -p " + request.getPid() + " -s " + scriptFile.getAbsolutePath()
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
                    LogStore.append(app, "inject TIMEOUT " + script + " pid=" + request.getPid());
                    saveLast(prefs, "TIMEOUT " + request.getPackageName() + " " + script);
                    continue;
                }

                int code = process.exitValue();
                String summary = "inject exit=" + code + " script=" + script + " pkg=" + request.getPackageName();
                if (out.length() > 0) {
                    summary += " out=" + out.toString().trim();
                }
                LogStore.append(app, summary);
                saveLast(prefs, (code == 0 ? "OK " : "FAIL ") + request.getPackageName() + " " + script + " exit=" + code);
            } catch (Exception e) {
                String err = "Error during frida injection: " + e.getMessage();
                Log.d(TAG, err);
                LogStore.append(app, err);
                saveLast(prefs, "ERROR " + e.getMessage());
            }
        }
    }

    private void saveLast(SharedPreferences prefs, String summary) {
        prefs.edit().putString(Constants.PREF_LAST_INJECT_SUMMARY, summary).apply();
    }
}
