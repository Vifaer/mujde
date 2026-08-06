package com.rel.mujde;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

/**
 * Root 被拒时的引导：尽量打开 KernelSU / Magisk 管理器。
 */
public final class RootGuide {
    private static final String TAG = "Mujde";

    private RootGuide() {}

    public static String deniedHint() {
        return "Root(su) 不可用。请在 KernelSU / Magisk 超级用户中永久允许 com.rel.mujde，然后回到首页点「健康检查」。";
    }

    /** @return true 若成功拉起任一管理器 */
    public static boolean openManager(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (tryLaunch(app, "me.weishu.kernelsu", null)) return true;
        if (tryLaunch(app, "com.rifsxd.ksunext", null)) return true;
        if (tryLaunch(app, "com.sukisu.ultra", null)) return true;
        if (tryLaunch(app, "com.topjohnwu.magisk", "com.topjohnwu.magisk.ui.MainActivity")) return true;
        // Magisk 部分版本用 deep link
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("magisk://home"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "magisk deep link: " + e.getMessage());
        }
        return false;
    }

    private static boolean tryLaunch(Context app, String pkg, String activity) {
        try {
            PackageManager pm = app.getPackageManager();
            Intent launch;
            if (activity != null) {
                launch = new Intent();
                launch.setComponent(new ComponentName(pkg, activity));
            } else {
                launch = pm.getLaunchIntentForPackage(pkg);
            }
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(launch);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
