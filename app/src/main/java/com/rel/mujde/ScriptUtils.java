package com.rel.mujde;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ScriptUtils {
    public static final String DEFAULT_SCRIPT_TEMPLATE =
            "/**\n"
                    + " * Mujde Frida script template\n"
                    + " * Use liblog for device logcat: adb logcat -s YOUR_TAG:I\n"
                    + " */\n"
                    + "'use strict';\n\n"
                    + "var FLAG = '__MUJDE_SCRIPT_FLAG__';\n"
                    + "var TAG = 'MUJDE_SCRIPT';\n\n"
                    + "function alog(msg) {\n"
                    + "  var line = '[' + TAG + '] ' + msg;\n"
                    + "  console.log(line);\n"
                    + "  try {\n"
                    + "    var fn = new NativeFunction(\n"
                    + "      Module.findExportByName('liblog.so', '__android_log_write'),\n"
                    + "      'int', ['int', 'pointer', 'pointer']\n"
                    + "    );\n"
                    + "    fn(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(line));\n"
                    + "  } catch (e) {}\n"
                    + "}\n\n"
                    + "if (globalThis[FLAG]) {\n"
                    + "  alog('already installed, skip');\n"
                    + "} else {\n"
                    + "  globalThis[FLAG] = true;\n"
                    + "  setImmediate(function () { alog('script loaded'); });\n"
                    + "}\n";

    public static void saveAppScriptMappings(SharedPreferences prefs, String packageName, List<String> scripts) {
        try {
            String jsonString = prefs.getString(Constants.PREF_APP_SCRIPTS_MAP, "{}");
            JSONObject jsonObject = new JSONObject(jsonString);
            jsonObject.remove(packageName);

            JSONArray scriptsArray = new JSONArray();
            for (String script : scripts) {
                scriptsArray.put(script);
            }

            if (!scripts.isEmpty()) {
                jsonObject.put(packageName, scriptsArray);
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(Constants.PREF_APP_SCRIPTS_MAP, jsonObject.toString());
            editor.apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** 从所有应用绑定中移除指定脚本名（删文件后调用）。 */
    public static void removeScriptFromAllMappings(SharedPreferences prefs, String scriptName) {
        if (prefs == null || scriptName == null || scriptName.isEmpty()) return;
        try {
            String jsonString = prefs.getString(Constants.PREF_APP_SCRIPTS_MAP, "{}");
            JSONObject jsonObject = new JSONObject(jsonString);
            Iterator<String> keys = jsonObject.keys();
            JSONObject updated = new JSONObject();
            while (keys.hasNext()) {
                String pkg = keys.next();
                JSONArray arr = jsonObject.getJSONArray(pkg);
                JSONArray kept = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.getString(i);
                    if (!scriptName.equals(s)) {
                        kept.put(s);
                    }
                }
                if (kept.length() > 0) {
                    updated.put(pkg, kept);
                }
            }
            prefs.edit().putString(Constants.PREF_APP_SCRIPTS_MAP, updated.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public static Map<String, List<String>> getAllAppScriptMappings(SharedPreferences prefs) {
        Map<String, List<String>> appScriptMappings = new HashMap<>();
        try {
            String jsonString = prefs.getString(Constants.PREF_APP_SCRIPTS_MAP, "{}");
            JSONObject jsonObject = new JSONObject(jsonString);
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String packageName = keys.next();
                JSONArray scriptsArray = jsonObject.getJSONArray(packageName);
                List<String> scripts = new ArrayList<>();
                for (int i = 0; i < scriptsArray.length(); i++) {
                    scripts.add(scriptsArray.getString(i));
                }
                appScriptMappings.put(packageName, scripts);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return appScriptMappings;
    }

    public static File getScriptsDirectory(Context context) {
        File scriptsDir = new File(context.getFilesDir(), Constants.SCRIPTS_DIRECTORY_NAME);
        if (!scriptsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            scriptsDir.mkdirs();
        }
        return scriptsDir;
    }

    /** Flat list of relative script paths (supports one-level subdirs). */
    public static List<String> listScriptNames(Context context) {
        File root = getScriptsDirectory(context);
        AccessibilityUtils.ensureReadableTree(root);
        List<String> names = new ArrayList<>();
        collectScripts(root, "", names);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static void collectScripts(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : files) {
            if (f.isHidden()) continue;
            if (f.isFile() && f.getName().endsWith(Constants.SCRIPT_FILE_EXT)) {
                out.add(prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName());
            } else if (f.isDirectory() && prefix.isEmpty()) {
                collectScripts(f, f.getName(), out);
            }
        }
    }

    public static File[] getScripts(Context context) {
        List<String> names = listScriptNames(context);
        File[] files = new File[names.size()];
        for (int i = 0; i < names.size(); i++) {
            files[i] = getScriptFile(context, names.get(i));
        }
        return files;
    }

    public static File getScriptFile(Context context, String scriptName) {
        return new File(getScriptsDirectory(context), scriptName);
    }

    public static String getScriptsDirectoryPath(Context context) {
        return getScriptsDirectory(context).getAbsolutePath();
    }

    public static String adjustScriptFileName(String scriptName) {
        if (scriptName.endsWith(Constants.SCRIPT_FILE_EXT)) {
            return scriptName;
        }
        return scriptName + Constants.SCRIPT_FILE_EXT;
    }

    public static List<String> getScriptsForPackage(String packageName, SharedPreferences prefs) {
        List<String> scripts = new ArrayList<>();
        try {
            String jsonString = prefs.getString(Constants.PREF_APP_SCRIPTS_MAP, "{}");
            JSONObject jsonObject = new JSONObject(jsonString);
            if (jsonObject.has(packageName)) {
                JSONArray scriptsArray = jsonObject.getJSONArray(packageName);
                for (int i = 0; i < scriptsArray.length(); i++) {
                    scripts.add(scriptsArray.getString(i));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return scripts;
    }

    public static int importFromDirectory(Context context, File sourceDir) throws IOException {
        if (sourceDir == null || !sourceDir.isDirectory()) return 0;
        int count = 0;
        File destRoot = getScriptsDirectory(context);
        File[] files = sourceDir.listFiles((dir, name) -> name.endsWith(Constants.SCRIPT_FILE_EXT));
        if (files == null) return 0;
        for (File src : files) {
            File dest = new File(destRoot, src.getName());
            copyFile(src, dest);
            AccessibilityUtils.makeFileWorldReadable(dest);
            count++;
        }
        return count;
    }

    /** 从 InputStream 写入单个 .js（fileName 需已含扩展名）。 */
    public static void importScriptStream(Context context, String fileName, java.io.InputStream in)
            throws IOException {
        String name = adjustScriptFileName(fileName);
        File dest = getScriptFile(context, name);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        AccessibilityUtils.makeFileWorldReadable(dest);
    }

    /** 从 zip 导入所有顶层/任意路径的 .js（按文件名落盘，同名覆盖）。 */
    public static int importFromZip(Context context, java.io.InputStream zipIn) throws IOException {
        int count = 0;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(zipIn)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                if (slash >= 0) name = name.substring(slash + 1);
                if (!name.endsWith(Constants.SCRIPT_FILE_EXT) || name.isEmpty()) continue;
                importScriptStream(context, name, zis);
                count++;
                zis.closeEntry();
            }
        }
        return count;
    }

    /** 将脚本目录打包为 zip 写到 OutputStream。 */
    public static int exportToZip(Context context, java.io.OutputStream out) throws IOException {
        List<String> names = listScriptNames(context);
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(out)) {
            byte[] buf = new byte[8192];
            for (String name : names) {
                File f = getScriptFile(context, name);
                if (!f.isFile()) continue;
                zos.putNextEntry(new java.util.zip.ZipEntry(name));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
        return names.size();
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
