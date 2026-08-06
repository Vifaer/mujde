package com.rel.mujde;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.FileObserver;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ScriptsFragment extends Fragment {
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private final List<String> scriptsList = new ArrayList<>();
    private ScriptAdapter scriptsAdapter;
    private FileObserver observer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scripts, container, false);
        swipeRefresh = view.findViewById(R.id.swipe_scripts);
        recyclerView = view.findViewById(R.id.list_scripts);
        emptyTextView = view.findViewById(R.id.text_empty_scripts);
        FloatingActionButton fabAdd = view.findViewById(R.id.fab_add_script);
        FloatingActionButton fabImport = view.findViewById(R.id.fab_import_scripts);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        scriptsAdapter = new ScriptAdapter(requireContext(), scriptsList, new ScriptAdapter.ScriptActionListener() {
            @Override
            public void onEditScript(String scriptName) {
                if (!isAdded()) return;
                Intent i = new Intent(requireContext(), ScriptEditorActivity.class);
                i.putExtra(ScriptEditorActivity.EXTRA_SCRIPT_NAME, scriptName);
                startActivity(i);
            }

            @Override
            public void onDeleteScript(String scriptName) {
                showDeleteScriptDialog(scriptName);
            }
        });
        recyclerView.setAdapter(scriptsAdapter);

        swipeRefresh.setColorSchemeResources(R.color.accent, R.color.secondary);
        swipeRefresh.setOnRefreshListener(this::loadScripts);
        fabAdd.setOnClickListener(v -> showAddScriptDialog());
        fabImport.setOnClickListener(v -> importExamples());
        fabImport.setContentDescription(getString(R.string.import_scripts));

        loadScripts();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isVisible()) {
            loadScripts();
            startObserver();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopObserver();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            stopObserver();
        } else if (isResumed()) {
            loadScripts();
            startObserver();
        }
    }

    private void startObserver() {
        stopObserver();
        if (!isAdded()) return;
        File dir = ScriptUtils.getScriptsDirectory(requireContext());
        observer = new FileObserver(dir.getAbsolutePath(),
                FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if ((event & FileObserver.CLOSE_WRITE) != 0) return;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (isAdded() && isVisible()) loadScripts();
                });
            }
        };
        observer.startWatching();
    }

    private void stopObserver() {
        if (observer != null) {
            observer.stopWatching();
            observer = null;
        }
    }

    private void loadScripts() {
        if (!isAdded() || recyclerView == null) return;
        scriptsList.clear();
        try {
            AccessibilityUtils.ensureReadableTree(ScriptUtils.getScriptsDirectory(requireContext()));
            scriptsList.addAll(ScriptUtils.listScriptNames(requireContext()));
        } catch (Exception e) {
            if (isAdded()) {
                Toast.makeText(requireContext(),
                        getString(R.string.load_scripts_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_SHORT).show();
            }
        }

        if (scriptsList.isEmpty()) {
            emptyTextView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyTextView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
        if (scriptsAdapter != null) scriptsAdapter.notifyDataSetChanged();
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
    }

    private void importExamples() {
        if (!isAdded()) return;
        File[] candidates = new File[]{
                new File("/sdcard/Download/frida-modules/mujde/scripts/examples"),
                new File("/storage/emulated/0/Download/frida-modules/mujde/scripts/examples")
        };
        int imported = 0;
        Exception last = null;
        for (File dir : candidates) {
            try {
                imported += ScriptUtils.importFromDirectory(requireContext(), dir);
            } catch (Exception e) {
                last = e;
            }
        }
        if (imported > 0) {
            Toast.makeText(requireContext(), getString(R.string.imported_scripts, imported), Toast.LENGTH_SHORT).show();
            loadScripts();
        } else {
            Toast.makeText(requireContext(),
                    last == null ? getString(R.string.import_none) : last.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showAddScriptDialog() {
        if (!isAdded()) return;
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("script_name" + Constants.SCRIPT_FILE_EXT);
        input.setTextColor(0xFFF1F5F9);
        input.setHintTextColor(0xFF94A3B8);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.create_script)
                .setView(input)
                .setPositiveButton(R.string.create, (dialog, which) -> {
                    String fileName = input.getText().toString().trim();
                    if (fileName.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.file_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createNewScript(ScriptUtils.adjustScriptFileName(fileName));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void createNewScript(String fileName) {
        if (!isAdded()) return;
        File scriptFile = ScriptUtils.getScriptFile(requireContext(), fileName);
        if (scriptFile.exists()) {
            Toast.makeText(requireContext(), R.string.script_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!scriptFile.createNewFile()) {
                Toast.makeText(requireContext(), R.string.script_create_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(scriptFile))) {
                writer.write(ScriptUtils.DEFAULT_SCRIPT_TEMPLATE);
            }
        } catch (IOException e) {
            Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        AccessibilityUtils.makeFileWorldReadable(scriptFile);
        loadScripts();
        Intent i = new Intent(requireContext(), ScriptEditorActivity.class);
        i.putExtra(ScriptEditorActivity.EXTRA_SCRIPT_NAME, fileName);
        startActivity(i);
    }

    private void showDeleteScriptDialog(String scriptName) {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_script)
                .setMessage(getString(R.string.delete_script_confirm, scriptName))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    File f = ScriptUtils.getScriptFile(requireContext(), scriptName);
                    if (f.delete()) {
                        loadScripts();
                    } else {
                        Toast.makeText(requireContext(), R.string.script_delete_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
