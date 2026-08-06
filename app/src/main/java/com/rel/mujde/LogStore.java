package com.rel.mujde;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LogStore {
    private static final String TAG = "Mujde";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private LogStore() {}

    public static File getLogsDir(Context context) {
        File dir = new File(context.getFilesDir(), Constants.LOGS_DIRECTORY_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File getTodayLogFile(Context context) {
        return new File(getLogsDir(context), "mujde-" + DAY.format(new Date()) + ".log");
    }

    public static void append(Context context, String message) {
        String line = TS.format(new Date()) + " " + message;
        Log.d(TAG, message);
        Context app = context.getApplicationContext();
        IO.execute(() -> writeLine(app, line));
    }

    /** 同步写入，供拉取 logcat 后立刻 refresh 使用 */
    public static void appendSync(Context context, String message) {
        String line = TS.format(new Date()) + " " + message;
        Log.d(TAG, message);
        writeLine(context.getApplicationContext(), line);
    }

    private static void writeLine(Context app, String line) {
        try {
            File file = getTodayLogFile(app);
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(line);
                writer.write('\n');
            }
            AccessibilityUtils.makeFileWorldReadable(file);
            pruneOld(app);
        } catch (IOException e) {
            Log.d(TAG, "LogStore write failed: " + e.getMessage());
        }
    }

    public static void pruneOld(Context context) {
        File[] files = getLogsDir(context).listFiles((dir, name) -> name.startsWith("mujde-") && name.endsWith(".log"));
        if (files == null || files.length <= Constants.LOG_RETENTION_DAYS) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int remove = files.length - Constants.LOG_RETENTION_DAYS;
        for (int i = 0; i < remove; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    public static String readRecent(Context context, int maxLines) {
        List<String> lines = new ArrayList<>();
        File[] files = getLogsDir(context).listFiles((dir, name) -> name.startsWith("mujde-") && name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return "（暂无日志）";
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
            }
        }
        if (lines.isEmpty()) {
            return "（暂无日志）";
        }
        int from = Math.max(0, lines.size() - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    public static void clearAll(Context context) {
        File[] files = getLogsDir(context).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static volatile long sBridgeOffset;
    private static final long BRIDGE_HARD_CAP = 1024L * 1024L;

    /**
     * 吸入脚本 console 桥文件（增量 + 尽量清空，防 2s 重复灌日志）。
     *
     * @return 新吸入行数
     */
    public static int ingestConsoleBridge(Context context) {
        Context app = context.getApplicationContext();
        File direct = new File(Constants.CONSOLE_BRIDGE_PATH);
        long fileLen = 0;
        byte[] raw = null;

        if (direct.canRead()) {
            try {
                fileLen = direct.length();
                if (fileLen > BRIDGE_HARD_CAP) {
                    forceClearBridge(app, "桥文件超过 1MB，已强制清理");
                    sBridgeOffset = 0;
                    return 0;
                }
                raw = readFileBytes(direct, (int) Math.min(fileLen, BRIDGE_HARD_CAP));
            } catch (IOException ignored) {
            }
        }
        if (raw == null) {
            RootShell.Result r = RootShell.exec(
                    "test -f '" + Constants.CONSOLE_BRIDGE_PATH + "' && wc -c < '"
                            + Constants.CONSOLE_BRIDGE_PATH + "' && echo '---' && cat '"
                            + Constants.CONSOLE_BRIDGE_PATH + "' || true",
                    8000);
            if (r.output != null && !r.output.trim().isEmpty()) {
                String out = r.output;
                int sep = out.indexOf("---");
                if (sep >= 0) {
                    try {
                        fileLen = Long.parseLong(out.substring(0, sep).trim().split("\\s+")[0]);
                    } catch (Exception ignored) {
                        fileLen = out.length();
                    }
                    String body = out.substring(sep + 3).trim();
                    if (fileLen > BRIDGE_HARD_CAP) {
                        forceClearBridge(app, "桥文件超过 1MB，已强制清理");
                        sBridgeOffset = 0;
                        return 0;
                    }
                    raw = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    fileLen = raw.length;
                } else {
                    raw = out.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    fileLen = raw.length;
                }
            }
        }
        if (raw == null || raw.length == 0) {
            sBridgeOffset = 0;
            return 0;
        }

        int start = (int) Math.min(Math.max(0, sBridgeOffset), raw.length);
        if (start >= raw.length) {
            // 无增量：尝试清空后复位
            if (tryClearBridge()) {
                sBridgeOffset = 0;
            }
            return 0;
        }

        String delta = new String(raw, start, raw.length - start, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = delta.split("\n");
        int n = 0;
        StringBuilder chunk = new StringBuilder();
        chunk.append("---- 脚本 console 桥 ----\n");
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            chunk.append(line).append('\n');
            n++;
            if (chunk.length() > 120_000) break;
        }

        boolean cleared = tryClearBridge();
        if (n > 0) {
            if (cleared) {
                appendSync(app, chunk.toString().trim());
                sBridgeOffset = 0;
            } else {
                // 清空失败：只更新水位，避免下一轮重复整文件灌入
                sBridgeOffset = fileLen;
                appendSync(app, chunk.toString().trim() + "\n（桥文件未能清空，后续仅吸入增量）");
            }
        } else if (cleared) {
            sBridgeOffset = 0;
        } else {
            sBridgeOffset = fileLen;
        }
        return n;
    }

    private static boolean tryClearBridge() {
        File f = new File(Constants.CONSOLE_BRIDGE_PATH);
        try {
            if (f.exists() && f.canWrite()) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                    raf.setLength(0);
                }
                return f.length() == 0;
            }
        } catch (Exception ignored) {
        }
        RootShell.Result r = RootShell.exec(
                "truncate -s 0 '" + Constants.CONSOLE_BRIDGE_PATH
                        + "' 2>/dev/null || : > '" + Constants.CONSOLE_BRIDGE_PATH + "'; echo ok",
                5000);
        return r.output != null && r.output.contains("ok");
    }

    private static void forceClearBridge(Context app, String reason) {
        LogStore.appendSync(app, "---- 脚本 console 桥 ----\n" + reason);
        RootShell.exec(
                "rm -f '" + Constants.CONSOLE_BRIDGE_PATH + "' 2>/dev/null; : > '"
                        + Constants.CONSOLE_BRIDGE_PATH + "'; chmod 666 '"
                        + Constants.CONSOLE_BRIDGE_PATH + "' 2>/dev/null; echo ok",
                5000);
    }

    private static byte[] readFileBytes(File file, int maxBytes) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[Math.max(0, maxBytes)];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) >= 0) {
                off += n;
            }
            if (off == buf.length) return buf;
            byte[] exact = new byte[off];
            System.arraycopy(buf, 0, exact, 0, off);
            return exact;
        }
    }

    private static String readFileLimited(File file, int maxBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > maxBytes) break;
            }
        }
        return sb.toString();
    }
}
