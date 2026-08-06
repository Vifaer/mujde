package com.rel.mujde;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 导出 ZygiskFrida / Gadget 风格配置文本（不内置 Zygisk 模块）。
 */
public final class ZygiskExport {
    private ZygiskExport() {}

    public static String buildConfigText(SharedPreferences prefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Mujde → 早注入对接导出\n");
        sb.append("# 生成时间说明：把下列脚本拷到 ZygiskFrida/Gadget 可读路径后，按各项目文档配置。\n");
        sb.append("# strongR 等第三方工具仅作文档外链，不随 APK 分发。\n");
        sb.append("# ZygiskFrida 示例: https://github.com/Perfare/Zygisk-Il2CppDumper 等社区方案，以你选用的模块为准。\n\n");

        sb.append("## 场景矩阵（简）\n");
        sb.append("| 场景 | 建议 |\n");
        sb.append("|------|------|\n");
        sb.append("| 普通 hook / 无启动反调试 | Mujde 晚注入即可 |\n");
        sb.append("| 启动期反 Frida / 校验 | Zygisk 早注入或 Gadget |\n");
        sb.append("| 经典端口/文件名检测 | 可开 Mujde 反 Frida（仍可能偏晚） |\n");
        sb.append("| SO 晚加载 | 加大注入延迟 + 脚本内 waitModule |\n\n");

        Map<String, List<String>> map = ScriptUtils.getAllAppScriptMappings(prefs);
        try {
            JSONObject root = new JSONObject();
            root.put("generator", "mujde");
            root.put("version", BuildConfig.VERSION_NAME);
            root.put("frida", BuildConfig.FRIDA_VERSION);
            root.put("note", "paths are inside Mujde private dir; copy out for ZygiskFrida");
            JSONObject apps = new JSONObject();
            for (Map.Entry<String, List<String>> e : map.entrySet()) {
                JSONObject one = new JSONObject();
                one.put("package", e.getKey());
                JSONArray arr = new JSONArray();
                for (String s : e.getValue()) arr.put(s);
                one.put("scripts", arr);
                one.put("mujde_script_dir", "/data/data/com.rel.mujde/files/scripts/");
                one.put("suggested_copy_to", "/data/local/tmp/mujde/" + e.getKey() + "/");
                apps.put(e.getKey(), one);
            }
            root.put("apps", apps);
            sb.append("## JSON\n```json\n");
            sb.append(root.toString(2));
            sb.append("\n```\n\n");
        } catch (Exception e) {
            sb.append("JSON 生成失败: ").append(e.getMessage()).append('\n');
        }

        sb.append("## 包名 → 脚本\n");
        if (map.isEmpty()) {
            sb.append("（当前无绑定）\n");
        } else {
            for (Map.Entry<String, List<String>> e : map.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        sb.append("\n## 建议步骤\n");
        sb.append("1. 用 Scripts 页导出 zip，或 root 拷贝 scripts 目录。\n");
        sb.append("2. 按所选 ZygiskFrida/Gadget 文档挂载脚本与目标包。\n");
        sb.append("3. 关闭 Mujde 对该包的绑定，避免双重注入。\n");
        return sb.toString();
    }
}
