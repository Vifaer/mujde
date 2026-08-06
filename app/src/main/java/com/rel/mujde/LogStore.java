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

    /**
     * 吸入脚本 console 桥文件（/data/local/tmp/mujde-console.log）。
     * 优先直接读；失败再用 su。
     *
     * @return 新吸入行数
     */
    public static int ingestConsoleBridge(Context context) {
        Context app = context.getApplicationContext();
        String content = null;
        File direct = new File(Constants.CONSOLE_BRIDGE_PATH);
        if (direct.canRead()) {
            try {
                content = readFileLimited(direct, 256 * 1024);
            } catch (IOException ignored) {
            }
        }
        if (content == null || content.isEmpty()) {
            RootShell.Result r = RootShell.exec(
                    "test -f '" + Constants.CONSOLE_BRIDGE_PATH + "' && cat '" + Constants.CONSOLE_BRIDGE_PATH + "' || true",
                    8000);
            if (r.output != null && !r.output.trim().isEmpty()) {
                content = r.output;
            }
        }
        if (content == null || content.trim().isEmpty()) {
            return 0;
        }
        String[] lines = content.split("\n");
        int n = 0;
        StringBuilder chunk = new StringBuilder();
        chunk.append("---- 脚本 console 桥 ----\n");
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            chunk.append(line).append('\n');
            n++;
            if (chunk.length() > 120_000) break;
        }
        if (n > 0) {
            appendSync(app, chunk.toString().trim());
            // 清空桥文件，避免重复吸入
            RootShell.exec("truncate -s 0 '" + Constants.CONSOLE_BRIDGE_PATH + "' 2>/dev/null || : > '"
                    + Constants.CONSOLE_BRIDGE_PATH + "'", 5000);
        }
        return n;
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
