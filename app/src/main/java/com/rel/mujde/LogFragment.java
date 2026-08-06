package com.rel.mujde;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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

        refresh();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!isAdded() || logText == null) return;
        String content = LogStore.readRecent(requireContext(), 800);
        logText.setText(content);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void pullLogcat() {
        if (!isAdded()) return;
        if (!RootShell.canSu()) {
            Toast.makeText(requireContext(), R.string.need_root, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), R.string.logcat_pulling, Toast.LENGTH_SHORT).show();
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            // 避免 su -c 嵌套引号弄坏 -s；多取一些再在 Java 侧过滤
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
                    || line.contains("frida-inject")
                    || line.contains("com.rel.mujde")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
