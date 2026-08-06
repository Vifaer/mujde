package com.rel.mujde;

import android.util.Log;

import java.util.regex.Pattern;

/**
 * 校验 PID 是否属于给定包名（/proc/pid/cmdline）。
 */
public final class PidOwner {
    private static final String TAG = "Mujde";
    private static final Pattern SAFE_PKG = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$");

    private PidOwner() {}

    public static boolean isSafePackageName(String packageName) {
        return packageName != null && SAFE_PKG.matcher(packageName).matches();
    }

    public static boolean owns(int pid, String packageName) {
        if (pid < 1 || !isSafePackageName(packageName)) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "su", "-c", "tr '\\0' ' ' < /proc/" + pid + "/cmdline 2>/dev/null || true");
            ProcessIo.Outcome o = ProcessIo.run(pb, 5000, 4096);
            String cmd = o.output == null ? "" : o.output.trim();
            if (cmd.isEmpty()) {
                Log.w(TAG, "pid_owner empty cmdline pid=" + pid);
                return false;
            }
            // cmdline 常见：com.foo / com.foo:push / 带参数
            String first = cmd.split("\\s+")[0];
            if (first.equals(packageName) || first.startsWith(packageName + ":")) {
                return true;
            }
            // 少数 ROM 可能整行含包名
            if (cmd.startsWith(packageName + " ") || cmd.startsWith(packageName + ":")) {
                return true;
            }
            Log.w(TAG, "pid_owner_mismatch pid=" + pid + " pkg=" + packageName + " cmd=" + first);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "pid_owner fail: " + e.getMessage());
            return false;
        }
    }
}
