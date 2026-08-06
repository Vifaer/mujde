package com.rel.mujde;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LSPosed scope helper — 用 Android SQLite 读写（设备上通常没有 sqlite3/python）。
 */
public final class ScopeHelper {
    private static final String TAG = "Mujde";
    private static final String MODULE = "com.rel.mujde";
    private static final String DB_REMOTE = "/data/adb/lspd/config/modules_config.db";

    private ScopeHelper() {}

    public static boolean openLsposedModulePage(Context context) {
        // Zygisk LSPosed 寄生管理器（本机实测）
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory("org.lsposed.manager.LAUNCH_MANAGER");
            intent.setComponent(new ComponentName("com.android.shell", "com.android.shell.BugreportWarningActivity"));
            intent.setData(Uri.parse("module://" + MODULE + "/0"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open module page failed: " + e.getMessage());
        }

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory("org.lsposed.manager.LAUNCH_MANAGER");
            intent.setComponent(new ComponentName("com.android.shell", "com.android.shell.BugreportWarningActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open parasitic manager failed: " + e.getMessage());
        }

        // 独立 Manager / 其它宿主兜底
        String[] managers = new String[]{
                "org.lsposed.manager",
                "io.github.lsposed.manager",
                "org.lsposed.manager.companion"
        };
        for (String pkg : managers) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(launch);
                    return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    public static boolean isPackageInScope(Context context, String targetPkg) {
        return readScopedPackages(context).contains(targetPkg);
    }

    /** 不含 system/android 的作用域应用数（状态页展示用） */
    public static int countScopedApps(Context context) {
        int n = 0;
        for (String p : readScopedPackages(context)) {
            if (!"system".equals(p) && !"android".equals(p)) n++;
        }
        return n;
    }

    public static Set<String> readScopedPackages(Context context) {
        Set<String> result = new HashSet<>();
        try {
            File localDb = pullDbToCache(context);
            if (localDb == null) return result;
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    localDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = db.rawQuery(
                    "SELECT app_pkg_name FROM scope WHERE module_pkg_name=?",
                    new String[]{MODULE})) {
                while (c.moveToNext()) {
                    String pkg = c.getString(0);
                    if (pkg != null && !pkg.isEmpty()) result.add(pkg);
                }
            } finally {
                db.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "readScopedPackages: " + e.getMessage());
        }
        return result;
    }

    public static RootShell.Result applyScope(Context context, String targetPkg) {
        return mutateScope(context, targetPkg, true);
    }

    /** 从本模块作用域中移除目标包（不解绑 system/android）。 */
    public static RootShell.Result removeScope(Context context, String targetPkg) {
        if ("system".equals(targetPkg) || "android".equals(targetPkg)) {
            return new RootShell.Result(-1, "拒绝移除系统作用域项");
        }
        return mutateScope(context, targetPkg, false);
    }

    private static RootShell.Result mutateScope(Context context, String targetPkg, boolean add) {
        if (targetPkg == null || targetPkg.isEmpty()) {
            return new RootShell.Result(-1, "包名为空");
        }
        if (!RootShell.canSu()) {
            return new RootShell.Result(-1, "无 Root 权限");
        }

        try {
            // 备份远程 DB
            RootShell.exec(
                    "cp -f '" + DB_REMOTE + "' '" + DB_REMOTE + ".mujde.bak' 2>/dev/null; echo ok",
                    8000);

            File localDb = pullDbToCache(context);
            if (localDb == null) {
                return new RootShell.Result(-1, "无法复制 modules_config.db（路径: " + DB_REMOTE + "）");
            }

            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    localDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {
                if (add) {
                    db.execSQL(
                            "INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES(?,?,?)",
                            new Object[]{MODULE, targetPkg, 0});
                } else {
                    db.execSQL(
                            "DELETE FROM scope WHERE module_pkg_name=? AND app_pkg_name=? AND user_id=?",
                            new Object[]{MODULE, targetPkg, 0});
                }
                try {
                    db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close();
                } catch (Exception ignored) {
                }
            } finally {
                db.close();
            }

            String local = localDb.getAbsolutePath();
            String cmd =
                    "rm -f '" + DB_REMOTE + "-wal' '" + DB_REMOTE + "-shm' && "
                            + "cp -f '" + local + "' '" + DB_REMOTE + "' && "
                            + "chown system:system '" + DB_REMOTE + "' && "
                            + "chmod 660 '" + DB_REMOTE + "' && "
                            + "echo ok";
            RootShell.Result push = RootShell.exec(cmd, 15000);
            if (!push.ok() && !push.output.contains("ok")) {
                return new RootShell.Result(-1, "写回失败: " + push.output);
            }

            Set<String> now = readScopedPackages(context);
            if (add) {
                if (now.contains(targetPkg)) {
                    return new RootShell.Result(0, "已加入作用域: " + targetPkg);
                }
                return new RootShell.Result(-1, "写回后校验未命中，请重启 LSPosed/设备后再查");
            } else {
                if (!now.contains(targetPkg)) {
                    return new RootShell.Result(0, "已移出作用域: " + targetPkg);
                }
                return new RootShell.Result(-1, "删除后校验仍存在，请重启 LSPosed/设备后再查");
            }
        } catch (Exception e) {
            return new RootShell.Result(-1, e.getMessage() == null ? "scope 写入异常" : e.getMessage());
        }
    }

    /** 把远程 DB(+WAL) 拷到应用可读缓存。 */
    private static File pullDbToCache(Context context) {
        if (!RootShell.canSu()) return null;
        if (context == null) return null;

        File dir = new File(context.getApplicationContext().getCacheDir(), "lspd");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        File localDb = new File(dir, "modules_config.db");
        String dst = localDb.getAbsolutePath();
        String cmd =
                "mkdir -p '" + dir.getAbsolutePath() + "' && "
                        + "cp -f '" + DB_REMOTE + "' '" + dst + "' && "
                        + "cp -f '" + DB_REMOTE + "-wal' '" + dst + "-wal' 2>/dev/null; "
                        + "cp -f '" + DB_REMOTE + "-shm' '" + dst + "-shm' 2>/dev/null; "
                        + "chmod 666 '" + dst + "' '" + dst + "-wal' '" + dst + "-shm' 2>/dev/null; "
                        + "test -f '" + dst + "' && echo ok";
        RootShell.Result r = RootShell.exec(cmd, 12000);
        if (!r.ok() && !r.output.contains("ok")) {
            Log.w(TAG, "pullDb failed: " + r.output);
            return null;
        }
        if (!localDb.exists() || localDb.length() < 100) {
            Log.w(TAG, "pullDb missing/empty: " + localDb);
            return null;
        }
        return localDb;
    }

    public static List<String> describeScopeStatus(Context context) {
        List<String> lines = new ArrayList<>();
        boolean parasitic = false;
        try {
            context.getPackageManager().getPackageInfo("com.android.shell", 0);
            parasitic = true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        lines.add("LSPosed 管理器: " + (parasitic ? "寄生模式 (Shell)" : "未检测到"));
        Set<String> scoped = readScopedPackages(context);
        int apps = 0;
        for (String p : scoped) {
            if (!"system".equals(p) && !"android".equals(p)) apps++;
        }
        lines.add("作用域应用数: " + apps + "（含 system 共 " + scoped.size() + "）");
        return lines;
    }
}
