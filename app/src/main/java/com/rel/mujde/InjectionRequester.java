package com.rel.mujde;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class InjectionRequester implements IXposedHookLoadPackage {
    /** 防抖：距上次发送短于该间隔则跳过（允许失败后重试，非成功占坑）。 */
    private static final long SEND_DEBOUNCE_MS = 2000L;
    private static final ConcurrentHashMap<Integer, Long> LAST_SEND_ELAPSED = new ConcurrentHashMap<>();

    private XSharedPreferences getPreferences() {
        XSharedPreferences pref = new XSharedPreferences(BuildConfig.APPLICATION_ID, Constants.SHARED_PREF_FILE_NAME);
        pref.reload();
        return pref;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }

        String packageName = lpparam.packageName;
        if (packageName.equals(BuildConfig.APPLICATION_ID)) {
            return;
        }

        XSharedPreferences pref = getPreferences();
        List<String> scripts = ScriptUtils.getScriptsForPackage(packageName, pref);

        if (scripts == null || scripts.isEmpty()) {
            return;
        }

        installHookOnActivityCreation(lpparam);
    }

    private void sendInjectionRequest(Activity activity) {
        Intent intent = new Intent();
        InjectionRequest request = new InjectionRequest(Process.myPid(), activity.getPackageName());

        request.putExtra(intent);
        intent.setAction(Constants.ACTION_INJECT_REQUEST);
        intent.setComponent(new ComponentName(
                BuildConfig.APPLICATION_ID,
                InjectionRequestHandler.class.getName()));

        XposedBridge.log("[Mujde] sending injection request " + request.toString());
        activity.sendBroadcast(intent);
    }

    private void installHookOnActivityCreation(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
            Activity.class.getName(),
            lpparam.classLoader,
            "onCreate",
            Bundle.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int pid = Process.myPid();
                    long now = SystemClock.elapsedRealtime();
                    Long prev = LAST_SEND_ELAPSED.get(pid);
                    if (prev != null && now - prev < SEND_DEBOUNCE_MS) {
                        return;
                    }
                    LAST_SEND_ELAPSED.put(pid, now);
                    sendInjectionRequest((Activity) param.thisObject);
                }
            }
        );
    }
}
