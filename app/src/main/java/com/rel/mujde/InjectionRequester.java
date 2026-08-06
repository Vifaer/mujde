package com.rel.mujde;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class InjectionRequester implements IXposedHookLoadPackage {
    /** 本进程内已发送过注入请求的 PID（目标进程通常只有自身 PID）。 */
    private static final ConcurrentHashMap<Integer, Boolean> SENT_FOR_PID = new ConcurrentHashMap<>();

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
        intent.setComponent(new ComponentName("com.rel.mujde", "com.rel.mujde.InjectionRequestHandler"));

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
                    XSharedPreferences pref = getPreferences();
                    boolean once = true;
                    try {
                        once = pref.getBoolean(Constants.PREF_INJECT_ONCE, true);
                    } catch (Exception ignored) {
                    }

                    int pid = Process.myPid();
                    if (once) {
                        if (SENT_FOR_PID.putIfAbsent(pid, Boolean.TRUE) != null) {
                            return;
                        }
                    }

                    sendInjectionRequest((Activity) param.thisObject);
                }
            }
        );
    }
}
