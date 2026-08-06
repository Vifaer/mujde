package com.rel.mujde;

import android.util.Log;

import java.util.regex.Pattern;

/**
 * 校验 PID 是否属于给定包名。
 * 策略：pidof 命中 → 通过；cmdline 明确不匹配 → 拒绝；读失败/空 → 放行（兼容 ROM，避免误杀）。
 */
public final class PidOwner {
    private static final String TAG = "Mujde";
    private static final Pattern SAFE_PKG = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$");

    public enum Verdict {
        OWNED,
        MISMATCH,
        UNKNOWN
    }

    private PidOwner() {}

    public static boolean isSafePackageName(String packageName) {
        return packageName != null && SAFE_PKG.matcher(packageName).matches();
    }

    /** 是否允许注入：OWNED/UNKNOWN 允许，MISMATCH 拒绝。 */
    public static boolean allowInject(int pid, String packageName) {
        Verdict v = check(pid, packageName);
        return v != Verdict.MISMATCH;
    }

    public static Verdict check(int pid, String packageName) {
        if (pid < 1 || !isSafePackageName(packageName)) {
            return Verdict.MISMATCH;
        }

        // 1) pidof 列表（与 injectNow 同源，最稳）
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "pidof " + packageName);
            ProcessIo.Outcome o = ProcessIo.run(pb, 5000, 4096);
            String out = o.output == null ? "" : o.output.trim();
            for (String tok : out.split("\\s+")) {
                if (tok.isEmpty()) continue;
                try {
                    if (Integer.parseInt(tok) == pid) {
                        return Verdict.OWNED;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pidof check: " + e.getMessage());
        }

        // 2) cmdline
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "su", "-c", "tr '\\0' ' ' < /proc/" + pid + "/cmdline 2>/dev/null || true");
            ProcessIo.Outcome o = ProcessIo.run(pb, 5000, 4096);
            String cmd = o.output == null ? "" : o.output.trim();
            if (cmd.isEmpty() || o.timedOut) {
                Log.w(TAG, "pid_owner unknown (empty/timeout) pid=" + pid + " pkg=" + packageName);
                return Verdict.UNKNOWN;
            }
            String first = cmd.split("\\s+")[0];
            if (first.equals(packageName)
                    || first.startsWith(packageName + ":")
                    || cmd.startsWith(packageName + " ")
                    || cmd.startsWith(packageName + ":")
                    || cmd.contains(packageName)) {
                return Verdict.OWNED;
            }
            Log.w(TAG, "pid_owner_mismatch pid=" + pid + " pkg=" + packageName + " cmd=" + first);
            return Verdict.MISMATCH;
        } catch (Exception e) {
            Log.w(TAG, "pid_owner fail-open: " + e.getMessage());
            return Verdict.UNKNOWN;
        }
    }

    /** @deprecated 使用 {@link #allowInject} */
    public static boolean owns(int pid, String packageName) {
        return allowInject(pid, packageName);
    }
}
