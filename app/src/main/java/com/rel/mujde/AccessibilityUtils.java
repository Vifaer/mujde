package com.rel.mujde;

import java.io.File;
import java.io.IOException;

public class AccessibilityUtils {
    public static void makeFileWorldReadable(File file) {
        try {
            Process p = new ProcessBuilder("chmod", "644", file.getAbsolutePath()).start();
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void makeDirWorldReadable(File dir) {
        try {
            Process p = new ProcessBuilder("chmod", "755", dir.getAbsolutePath()).start();
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void ensureReadableTree(File dir) {
        if (dir == null || !dir.exists()) return;
        makeDirWorldReadable(dir);
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                ensureReadableTree(f);
            } else {
                makeFileWorldReadable(f);
            }
        }
    }
}
