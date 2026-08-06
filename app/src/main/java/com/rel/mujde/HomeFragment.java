package com.rel.mujde;

import static android.content.Context.MODE_WORLD_READABLE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.Map;

public class HomeFragment extends Fragment {
    private TextView statusText;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        statusText = view.findViewById(R.id.text_status);
        MaterialButton btnRefresh = view.findViewById(R.id.btn_refresh_status);
        MaterialButton btnOpenLsp = view.findViewById(R.id.btn_open_lsposed);
        SwitchCompat swOnce = view.findViewById(R.id.switch_inject_once);
        SwitchCompat swScope = view.findViewById(R.id.switch_auto_scope);

        prefs = safePrefs();

        swOnce.setChecked(prefs.getBoolean(Constants.PREF_INJECT_ONCE, true));
        swScope.setChecked(prefs.getBoolean(Constants.PREF_AUTO_SCOPE, true));
        swOnce.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Constants.PREF_INJECT_ONCE, checked).apply());
        swScope.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Constants.PREF_AUTO_SCOPE, checked).apply());

        btnRefresh.setOnClickListener(v -> refreshStatus());
        btnOpenLsp.setOnClickListener(v -> {
            if (isAdded()) ScopeHelper.openLsposedModulePage(requireContext());
        });

        refreshStatus();
        return view;
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
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("版本 ").append(BuildConfig.VERSION_NAME)
                    .append("  ·  Frida ").append(BuildConfig.FRIDA_VERSION).append('\n');
            sb.append("包名 ").append(BuildConfig.APPLICATION_ID).append('\n');

            boolean su = RootShell.canSu();
            sb.append("Root 权限：").append(su ? "正常" : "未授权 / 不可用").append('\n');

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
            sb.append("\n提示：保持通知栏注入服务运行；绑定脚本后请冷启动目标应用。");

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
