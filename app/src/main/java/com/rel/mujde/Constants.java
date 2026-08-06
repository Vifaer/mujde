package com.rel.mujde;

public class Constants {
    public static final String SHARED_PREF_FILE_NAME = "mujde_prefs";
    public static final String PREF_APP_SCRIPTS_MAP = "pref_app_scripts_map";
    public static final String PREF_INJECT_ONCE = "pref_inject_once_per_process";
    public static final String PREF_AUTO_SCOPE = "pref_auto_apply_lsposed_scope";
    public static final String PREF_INJECT_DELAY_MS = "pref_inject_delay_ms";
    public static final String PREF_LAST_INJECT_SUMMARY = "pref_last_inject_summary";
    public static final String PREF_SCRIPT_LOG_TAG = "pref_script_log_tag";
    public static final String PREF_ANTIFRIDA = "pref_antifrida_enable";
    public static final String PREF_ANTIFRIDA_AGGRESSIVE = "pref_antifrida_aggressive";
    public static final String PREF_CONSOLE_BRIDGE = "pref_console_bridge";

    public static final String DEFAULT_SCRIPT_LOG_TAG = "MUJDE_SCRIPT";
    public static final String CONSOLE_BRIDGE_PATH = "/data/local/tmp/mujde-console.log";

    public static final String SCRIPTS_DIRECTORY_NAME = "scripts";
    public static final String LOGS_DIRECTORY_NAME = "logs";
    public static final String SCRIPT_FILE_EXT = ".js";
    public static final String INTENT_REQUEST_PACKAGE_NAME = "package_name";
    public static final String ACTION_INJECT_REQUEST = "com.rel.mujde.INJECT_REQUEST";
    public static final int REQUEST_CODE_SELECT_SCRIPTS = 1002;
    public static final int INJECT_TIMEOUT_MS = 30000;
    public static final int LOG_RETENTION_DAYS = 7;
    public static final String NOTIFICATION_CHANNEL_ID = "mujde_inject";
    public static final int NOTIFICATION_ID_SERVICE = 23101;
}
