package com.rel.mujde;

import static android.content.Context.MODE_WORLD_READABLE;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class HomeFragment extends Fragment {
    private TextView statusText;
    private TextView delayLabel;
    private SharedPreferences prefs;
    private ActivityResultLauncher<String> exportZygiskLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exportZygiskLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/markdown"),
                this::onZygiskExportTarget);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        statusText = view.findViewById(R.id.text_status);
        delayLabel = view.findViewById(R.id.text_inject_delay_label);
        SeekBar seekDelay = view.findViewById(R.id.seek_inject_delay);
        MaterialButton btnRefresh = view.findViewById(R.id.btn_refresh_status);
        MaterialButton btnOpenLsp = view.findViewById(R.id.btn_open_lsposed);
        MaterialButton btnHealth = view.findViewById(R.id.btn_health_check);
        MaterialButton btnRoot = view.findViewById(R.id.btn_open_root);
        MaterialButton btnZygisk = view.findViewById(R.id.btn_export_zygisk);
        SwitchCompat swOnce = view.findViewById(R.id.switch_inject_once);
        SwitchCompat swScope = view.findViewById(R.id.switch_auto_scope);
        SwitchCompat swBridge = view.findViewById(R.id.switch_console_bridge);
        SwitchCompat swAnti = view.findViewById(R.id.switch_antifrida);
        SwitchCompat swAntiAgg = view.findViewById(R.id.switch_antifrida_agg);
        EditText editTag = view.findViewById(R.id.edit_script_log_tag);

        prefs = safePrefs();

        swOnce.setChecked(prefs.getBoolean(Constants.PREF_INJECT_ONCE, true));
        swScope.setChecked(prefs.getBoolean(Constants.PREF_AUTO_SCOPE, true));
        swBridge.setChecked(prefs.getBoolean(Constants.PREF_CONSOLE_BRIDGE, true));
        swAnti.setChecked(prefs.getBoolean(Constants.PREF_ANTIFRIDA, false));
        swAntiAgg.setChecked(prefs.getBoolean(Constants.PREF_ANTIFRIDA_AGGRESSIVE, false));
        swAntiAgg.setEnabled(swAnti.isChecked());
        editTag.setText(prefs.getString(Constants.PREF_SCRIPT_LOG_TAG, Constants.DEFAULT_SCRIPT_LOG_TAG));

        swOnce.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Constants.PREF_INJECT_ONCE, checked).apply());
        swScope.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Constants.PREF_AUTO_SCOPE, checked).apply());
        swBridge.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Constants.PREF_CONSOLE_BRIDGE, checked).apply());
        swAnti.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Constants.PREF_ANTIFRIDA, checked).apply();
            swAntiAgg.setEnabled(checked);
            if (!checked) {
                swAntiAgg.setChecked(false);
                prefs.edit().putBoolean(Constants.PREF_ANTIFRIDA_AGGRESSIVE, false).apply();
            }
        });
        swAntiAgg.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Constants.PREF_ANTIFRIDA_AGGRESSIVE, checked).apply());

        editTag.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String t = s == null ? "" : s.toString().trim();
                if (t.isEmpty()) t = Constants.DEFAULT_SCRIPT_LOG_TAG;
                prefs.edit().putString(Constants.PREF_SCRIPT_LOG_TAG, t).apply();
            }
        });

        int delay = prefs.getInt(Constants.PREF_INJECT_DELAY_MS, 0);
        if (delay < 0) delay = 0;
        if (delay > 10000) delay = 10000;
        seekDelay.setProgress(delay);
        updateDelayLabel(delay);
        seekDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateDelayLabel(progress);
                if (fromUser) {
                    prefs.edit().putInt(Constants.PREF_INJECT_DELAY_MS, progress).apply();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(Constants.PREF_INJECT_DELAY_MS, seekBar.getProgress()).apply();
            }
        });

        btnRefresh.setOnClickListener(v -> refreshStatus());
        btnOpenLsp.setOnClickListener(v -> {
            if (isAdded()) ScopeHelper.openLsposedModulePage(requireContext());
        });
        btnRoot.setOnClickListener(v -> {
            if (!isAdded()) return;
            if (!RootGuide.openManager(requireContext())) {
                Toast.makeText(requireContext(), R.string.root_manager_not_found, Toast.LENGTH_LONG).show();
            }
        });
        btnHealth.setOnClickListener(v -> runHealthCheck());
        btnZygisk.setOnClickListener(v -> exportZygiskLauncher.launch("mujde-zygisk-export.md"));

        refreshStatus();
        return view;
    }

    private void onZygiskExportTarget(@Nullable Uri uri) {
        if (uri == null || !isAdded()) return;
        try {
            String text = ZygiskExport.buildConfigText(prefs);
            try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("无法写入");
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(requireContext(), R.string.export_zygisk_ok, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.export_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void runHealthCheck() {
        if (!isAdded()) return;
        final Context appCtx = requireContext().getApplicationContext();
        final SharedPreferences p = prefs;
        Toast.makeText(requireContext(), R.string.health_check_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HealthCheck.Report report = HealthCheck.run(appCtx, p);
            LogStore.appendSync(appCtx, "---- 健康检查 ----\n" + report.asText());
            if (!report.allOk && !RootShell.canSu()) {
                // 引导文案已在报告内
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.health_check)
                        .setMessage(report.asText())
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.open_root_manager, (d, w) -> {
                            if (!RootGuide.openManager(requireContext())) {
                                Toast.makeText(requireContext(), R.string.root_manager_not_found, Toast.LENGTH_LONG).show();
                            }
                        })
                        .show();
                refreshStatus();
            });
        }).start();
    }

    private void updateDelayLabel(int ms) {
        if (delayLabel != null) {
            delayLabel.setText(getString(R.string.inject_delay_label, ms));
        }
    }

    private SharedPreferences safePrefs() {
        try {
            return requireActivity().getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_WORLD_READABLE);
        } catch (Exception e) {
            return requireActivity().getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (statusText != null) refreshStatus();
    }

    private void refreshStatus() {
        if (!isAdded() || statusText == null) return;
        statusText.setText(R.string.status_loading);

        final Context appCtx = requireContext().getApplicationContext();
        final SharedPreferences p = prefs;
        final String hintInject = getString(R.string.long_press_inject_hint);
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("版本 ").append(BuildConfig.VERSION_NAME)
                    .append("  ·  Frida ").append(BuildConfig.FRIDA_VERSION).append('\n');
            sb.append("包名 ").append(BuildConfig.APPLICATION_ID).append('\n');

            boolean su = RootShell.canSu();
            sb.append("Root 权限：").append(su ? "正常" : "未授权 / 不可用").append('\n');
            if (!su) sb.append(RootGuide.deniedHint()).append('\n');

            int scripts = 0;
            int bound = 0;
            try {
                scripts = ScriptUtils.listScriptNames(appCtx).size();
                if (p != null) {
                    Map<?, ?> map = ScriptUtils.getAllAppScriptMappings(p);
                    bound = map.size();
                }
            } catch (Exception ignored) {
            }
            sb.append("脚本数量：").append(scripts).append("  ·  已绑定应用：").append(bound).append('\n');

            int delayMs = p != null ? p.getInt(Constants.PREF_INJECT_DELAY_MS, 0) : 0;
            sb.append("注入延迟：").append(delayMs).append(" ms\n");
            sb.append("反 Frida：").append(p != null && p.getBoolean(Constants.PREF_ANTIFRIDA, false) ? "开" : "关").append('\n');

            String last = p != null ? p.getString(Constants.PREF_LAST_INJECT_SUMMARY, "无") : "无";
            sb.append("最近注入：").append(last).append('\n');

            if (su) {
                java.util.Set<String> scoped = ScopeHelper.readScopedPackages(appCtx);
                int apps = 0;
                for (String pkg : scoped) {
                    if (!"system".equals(pkg) && !"android".equals(pkg)) apps++;
                }
                sb.append("LSPosed 作用域应用：").append(apps)
                        .append("（含 system 共 ").append(scoped.size()).append("）\n");
            } else {
                sb.append("LSPosed 作用域：（需要 Root 才能探测）\n");
            }
            sb.append("\n提示：多脚本会合并为一次 frida-inject；启动期强对抗请导出 Zygisk 配置。");
            sb.append('\n').append(hintInject);

            final String text = sb.toString();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (isAdded() && statusText != null) {
                    statusText.setText(text);
                }
            });
        }).start();
    }
}
