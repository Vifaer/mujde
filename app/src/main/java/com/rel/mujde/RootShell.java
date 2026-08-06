package com.rel.mujde;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootShell {
    private RootShell() {}

    public static class Result {
        public final int exitCode;
        public final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    public static boolean canSu() {
        Result r = exec("id", 5000);
        return r.ok() && r.output.contains("uid=0");
    }

    public static Result exec(String command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) >= 0) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 512 * 1024) {
                        break;
                    }
                }
            }
            boolean finished;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                finished = true;
                process.waitFor();
            }
            if (!finished) {
                process.destroy();
                return new Result(-1, "timeout: " + sb);
            }
            return new Result(process.exitValue(), sb.toString().trim());
        } catch (Exception e) {
            return new Result(-1, e.getMessage() == null ? "su failed" : e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
