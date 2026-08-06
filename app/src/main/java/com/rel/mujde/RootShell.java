package com.rel.mujde;

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
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
            ProcessIo.Outcome o = ProcessIo.run(pb, timeoutMs, 512 * 1024);
            if (o.timedOut) {
                return new Result(-1, "timeout: " + o.output);
            }
            return new Result(o.exitCode, o.output);
        } catch (Exception e) {
            return new Result(-1, e.getMessage() == null ? "su failed" : e.getMessage());
        }
    }
}
