package com.rel.mujde;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CERT FIO07-J：异步排水 + 墙钟超时 + destroy/destroyForcibly。
 */
public final class ProcessIo {
    private static final String TAG = "Mujde";
    private static final ExecutorService DRAIN = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mujde-process-io");
        t.setDaemon(true);
        return t;
    });

    private ProcessIo() {}

    public static final class Outcome {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;

        public Outcome(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    public static Outcome run(ProcessBuilder pb, long timeoutMs, int maxCaptureBytes) {
        Process process = null;
        Future<?> drainFuture = null;
        AtomicReference<StringBuilder> captured = new AtomicReference<>(new StringBuilder());
        try {
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process proc = process;
            drainFuture = DRAIN.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = captured.get();
                    char[] buf = new char[4096];
                    int n;
                    int kept = 0;
                    while ((n = reader.read(buf)) >= 0) {
                        if (kept < maxCaptureBytes) {
                            int take = Math.min(n, maxCaptureBytes - kept);
                            sb.append(buf, 0, take);
                            kept += take;
                        }
                        // 超出部分继续读掉，防止管道堵死
                    }
                } catch (Exception e) {
                    Log.w(TAG, "drain: " + e.getMessage());
                }
            });

            boolean finished = waitWithTimeout(process, timeoutMs);
            if (!finished) {
                safeDestroy(process);
                // 再给 2s 优雅退出
                if (!waitWithTimeout(process, 2000)) {
                    safeDestroyForcibly(process);
                    waitWithTimeout(process, 1000);
                }
                try {
                    drainFuture.get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                return new Outcome(-1, captured.get().toString().trim(), true);
            }

            try {
                drainFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            int code;
            try {
                code = process.exitValue();
            } catch (IllegalThreadStateException e) {
                safeDestroyForcibly(process);
                code = -1;
            }
            return new Outcome(code, captured.get().toString().trim(), false);
        } catch (Exception e) {
            return new Outcome(-1, e.getMessage() == null ? "process failed" : e.getMessage(), false);
        } finally {
            if (drainFuture != null) {
                drainFuture.cancel(true);
            }
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (Exception ignored) {
                }
                try {
                    process.getOutputStream().close();
                } catch (Exception ignored) {
                }
                try {
                    process.getErrorStream().close();
                } catch (Exception ignored) {
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    if (process.isAlive()) {
                        safeDestroyForcibly(process);
                    }
                } else {
                    safeDestroy(process);
                }
            }
        }
    }

    private static boolean waitWithTimeout(Process process, long timeoutMs) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= 26) {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        }
        // API 21–25：用 Future 包住无界 waitFor
        Future<?> waiter = DRAIN.submit(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            waiter.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            waiter.cancel(true);
            return false;
        }
    }

    private static void safeDestroy(Process process) {
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
    }

    private static void safeDestroyForcibly(Process process) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        } catch (Exception ignored) {
        }
    }
}
