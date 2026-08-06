package com.rel.mujde;

import static android.content.Context.MODE_WORLD_READABLE;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ScriptSelectionActivity extends AppCompatActivity {
    private String packageName;
    private ScriptCheckboxAdapter adapter;
    private final List<String> selectedScripts = new ArrayList<>();
    private final List<String> availableScripts = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_selection);

        TextView appNameText = findViewById(R.id.app_name_text);
        RecyclerView scriptsRecyclerView = findViewById(R.id.scripts_recycler_view);

        packageName = getIntent().getStringExtra(Constants.INTENT_REQUEST_PACKAGE_NAME);
        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }

        try {
            prefs = getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_WORLD_READABLE);
        } catch (Exception e) {
            prefs = getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_PRIVATE);
        }

        List<String> currentScripts = ScriptUtils.getAllAppScriptMappings(prefs).get(packageName);
        if (currentScripts != null) {
            selectedScripts.addAll(currentScripts);
        }

        try {
            PackageManager packageManager = getPackageManager();
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            appNameText.setText(appInfo.loadLabel(packageManager));
        } catch (PackageManager.NameNotFoundException e) {
            appNameText.setText(packageName);
        }

        loadAvailableScripts();
        scriptsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScriptCheckboxAdapter(availableScripts, selectedScripts);
        scriptsRecyclerView.setAdapter(adapter);

        findViewById(R.id.cancel_button).setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        findViewById(R.id.save_button).setOnClickListener(v -> saveAndMaybeScope());
    }

    private void saveAndMaybeScope() {
        List<String> validScripts = filterValidScripts(adapter.getSelectedScripts());
        ScriptUtils.saveAppScriptMappings(prefs, packageName, validScripts);
        AccessibilityUtils.makeDirWorldReadable(getFilesDir());

        boolean autoScope = prefs.getBoolean(Constants.PREF_AUTO_SCOPE, true);
        // su + 复制 DB 放到后台，避免主线程 ANR
        new Thread(() -> {
            if (validScripts.isEmpty()) {
                RootShell.Result r = ScopeHelper.removeScope(ScriptSelectionActivity.this, packageName);
                runOnUiThread(() -> {
                    if (r.ok()) {
                        Toast.makeText(this, R.string.scope_removed, Toast.LENGTH_SHORT).show();
                    } else if (autoScope) {
                        Toast.makeText(this,
                                getString(R.string.scope_remove_failed, r.output),
                                Toast.LENGTH_LONG).show();
                    }
                    finishOk();
                });
                return;
            }
            if (autoScope) {
                RootShell.Result r = ScopeHelper.applyScope(ScriptSelectionActivity.this, packageName);
                runOnUiThread(() -> {
                    if (r.ok()) {
                        Toast.makeText(this, R.string.scope_applied, Toast.LENGTH_SHORT).show();
                        finishOk();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.scope_apply_title)
                                .setMessage(getString(R.string.scope_apply_failed, r.output))
                                .setPositiveButton(R.string.open_lsposed, (d, w) -> {
                                    ScopeHelper.openLsposedModulePage(this);
                                    finishOk();
                                })
                                .setNegativeButton(R.string.continue_anyway, (d, w) -> finishOk())
                                .show();
                    }
                });
                return;
            }
            boolean inScope = ScopeHelper.isPackageInScope(ScriptSelectionActivity.this, packageName);
            runOnUiThread(() -> {
                if (!inScope) {
                    View root = findViewById(android.R.id.content);
                    Snackbar.make(root, R.string.scope_warning, Snackbar.LENGTH_LONG)
                            .setAction(R.string.open_lsposed, v -> ScopeHelper.openLsposedModulePage(this))
                            .addCallback(new Snackbar.Callback() {
                                @Override
                                public void onDismissed(Snackbar transientBottomBar, int event) {
                                    finishOk();
                                }
                            })
                            .show();
                } else {
                    finishOk();
                }
            });
        }).start();
    }

    private void finishOk() {
        setResult(Activity.RESULT_OK);
        finish();
    }

    private List<String> filterValidScripts(List<String> scriptNames) {
        List<String> validScripts = new ArrayList<>();
        for (String scriptName : scriptNames) {
            File scriptFile = ScriptUtils.getScriptFile(this, scriptName);
            if (scriptFile.exists() && scriptFile.isFile()) {
                validScripts.add(scriptName);
            }
        }
        return validScripts;
    }

    private void loadAvailableScripts() {
        availableScripts.clear();
        List<String> validSelected = filterValidScripts(selectedScripts);
        selectedScripts.clear();
        selectedScripts.addAll(validSelected);
        availableScripts.addAll(ScriptUtils.listScriptNames(this));
    }
}
