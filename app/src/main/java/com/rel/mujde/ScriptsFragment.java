package com.rel.mujde;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileObserver;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ScriptsFragment extends Fragment {
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private final List<String> scriptsList = new ArrayList<>();
    private ScriptAdapter scriptsAdapter;
    private FileObserver observer;

    private ActivityResultLauncher<String[]> pickJsLauncher;
    private ActivityResultLauncher<String[]> pickZipLauncher;
    private ActivityResultLauncher<String> createZipLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickJsLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onJsPicked);
        pickZipLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onZipPicked);
        createZipLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                this::onZipExportTarget);
    }

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
        fabImport.setOnClickListener(v -> showImportExportMenu());
        fabImport.setContentDescription(getString(R.string.import_scripts));

        loadScripts();
        return view;
    }

    private void showImportExportMenu() {
        if (!isAdded()) return;
        CharSequence[] items = new CharSequence[]{
                getString(R.string.import_js),
                getString(R.string.import_zip),
                getString(R.string.export_zip),
                getString(R.string.import_examples),
                getString(R.string.create_from_template)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_menu_title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            pickJsLauncher.launch(new String[]{"application/javascript", "text/javascript", "text/plain", "*/*"});
                            break;
                        case 1:
                            pickZipLauncher.launch(new String[]{"application/zip", "application/x-zip-compressed", "*/*"});
                            break;
                        case 2:
                            createZipLauncher.launch("mujde-scripts.zip");
                            break;
                        case 3:
                            importExamples();
                            break;
                        case 4:
                            showTemplateWizard();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void showTemplateWizard() {
        if (!isAdded()) return;
        CharSequence[] kinds = new CharSequence[]{
                getString(R.string.template_hello),
                getString(R.string.template_skeleton),
                getString(R.string.template_skeleton_af)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.create_from_template)
                .setItems(kinds, (d, which) -> {
                    String kind = which == 1 ? "skeleton" : (which == 2 ? "skeleton_af" : "hello");
                    try {
                        String name = ScriptTemplates.create(requireContext(), kind);
                        Toast.makeText(requireContext(), getString(R.string.template_created, name), Toast.LENGTH_SHORT).show();
                        loadScripts();
                        Intent i = new Intent(requireContext(), ScriptEditorActivity.class);
                        i.putExtra(ScriptEditorActivity.EXTRA_SCRIPT_NAME, name);
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(requireContext(),
                                getString(R.string.import_failed, String.valueOf(e.getMessage())),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void onJsPicked(@Nullable Uri uri) {
        if (uri == null || !isAdded()) return;
        try {
            String name = guessName(uri, "imported.js");
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法打开文件");
                ScriptUtils.importScriptStream(requireContext(), name, in);
            }
            Toast.makeText(requireContext(), getString(R.string.imported_scripts, 1), Toast.LENGTH_SHORT).show();
            loadScripts();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.import_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onZipPicked(@Nullable Uri uri) {
        if (uri == null || !isAdded()) return;
        try {
            int n;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法打开 zip");
                n = ScriptUtils.importFromZip(requireContext(), in);
            }
            Toast.makeText(requireContext(), getString(R.string.imported_scripts, n), Toast.LENGTH_SHORT).show();
            loadScripts();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.import_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onZipExportTarget(@Nullable Uri uri) {
        if (uri == null || !isAdded()) return;
        try {
            int n;
            try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("无法创建文件");
                n = ScriptUtils.exportToZip(requireContext(), out);
            }
            Toast.makeText(requireContext(), getString(R.string.exported_scripts, n), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.export_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String guessName(Uri uri, String fallback) {
        String last = uri.getLastPathSegment();
        if (last == null || last.isEmpty()) return fallback;
        int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf(':'));
        if (slash >= 0) last = last.substring(slash + 1);
        if (!last.endsWith(Constants.SCRIPT_FILE_EXT)) {
            last = ScriptUtils.adjustScriptFileName(last);
        }
        return last;
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
