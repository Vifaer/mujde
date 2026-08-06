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
