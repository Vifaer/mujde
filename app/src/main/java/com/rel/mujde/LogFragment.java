package com.rel.mujde;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

public class LogFragment extends Fragment {
    private TextView logText;
    private ScrollView scrollView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || !isVisible()) return;
            softRefresh();
            uiHandler.postDelayed(this, 2000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);
        logText = view.findViewById(R.id.text_logs);
        scrollView = view.findViewById(R.id.scroll_logs);
        MaterialButton btnRefresh = view.findViewById(R.id.btn_refresh_logs);
        MaterialButton btnClear = view.findViewById(R.id.btn_clear_logs);
        MaterialButton btnShare = view.findViewById(R.id.btn_share_logs);
        MaterialButton btnLogcat = view.findViewById(R.id.btn_pull_logcat);
        MaterialButton btnBridge = view.findViewById(R.id.btn_pull_console);

        btnRefresh.setOnClickListener(v -> refresh());
        btnClear.setOnClickListener(v -> {
            LogStore.clearAll(requireContext());
            refresh();
        });
        btnShare.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, logText.getText().toString());
            startActivity(Intent.createChooser(share, getString(R.string.share_logs)));
        });
        btnLogcat.setOnClickListener(v -> pullLogcat());
        if (btnBridge != null) {
            btnBridge.setOnClickListener(v -> pullConsoleBridge());
        }

        refresh();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        uiHandler.removeCallbacks(autoRefresh);
        uiHandler.postDelayed(autoRefresh, 2000);
    }

    @Override
    public void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(autoRefresh);
    }

    private void softRefresh() {
        if (!isAdded() || logText == null) return;
        // 后台吸入桥文件再刷新，避免阻塞 UI
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            LogStore.ingestConsoleBridge(appCtx);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(this::refresh);
        }).start();
    }

    private void refresh() {
        if (!isAdded() || logText == null) return;
        String content = LogStore.readRecent(requireContext(), 800);
        logText.setText(content);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void pullConsoleBridge() {
        if (!isAdded()) return;
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            int n = LogStore.ingestConsoleBridge(appCtx);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(),
                        getString(R.string.console_bridge_pulled, n),
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        }).start();
    }

    private void pullLogcat() {
        if (!isAdded()) return;
        if (!RootShell.canSu()) {
            Toast.makeText(requireContext(), RootGuide.deniedHint(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), R.string.logcat_pulling, Toast.LENGTH_SHORT).show();
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            RootShell.Result r = RootShell.exec("logcat -d -t 3000", 25000);
            String filtered = filterLogcat(r.output);
            if (!r.ok() && (r.output == null || r.output.isEmpty())) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            getString(R.string.logcat_failed, r.output),
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            if (filtered.isEmpty()) {
                LogStore.appendSync(appCtx, "---- logcat 拉取 ----\n（最近 3000 行中无 Mujde/脚本相关日志）");
            } else {
                LogStore.appendSync(appCtx, "---- logcat 拉取 ----\n" + filtered);
            }
            LogStore.ingestConsoleBridge(appCtx);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(),
                        filtered.isEmpty() ? R.string.logcat_empty : R.string.logcat_ok,
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        }).start();
    }

    private static String filterLogcat(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (line.contains("Mujde")
                    || line.contains("[Mujde]")
                    || line.contains("WL_BOOST")
                    || line.contains("MUJDE")
                    || line.contains("MF_LEARN")
                    || line.contains("MUJDE_ANTIFRIDA")
                    || line.contains("frida-inject")
                    || line.contains("com.rel.mujde")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
