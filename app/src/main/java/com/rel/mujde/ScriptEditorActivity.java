package com.rel.mujde;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ScriptEditorActivity extends AppCompatActivity {
    public static final String EXTRA_SCRIPT_NAME = "script_name";

    private String scriptName;
    private EditText editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_editor);

        scriptName = getIntent().getStringExtra(EXTRA_SCRIPT_NAME);
        if (scriptName == null || scriptName.isEmpty()) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar_editor);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(scriptName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        editor = findViewById(R.id.edit_script_body);
        FloatingActionButton fabSave = findViewById(R.id.fab_save_script);
        fabSave.setOnClickListener(v -> save());

        load();
    }

    private void load() {
        File file = ScriptUtils.getScriptFile(this, scriptName);
        if (!file.exists()) {
            Toast.makeText(this, R.string.script_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            editor.setText(sb.toString());
        } catch (IOException ioe) {
            Toast.makeText(this, ioe.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void save() {
        File file = ScriptUtils.getScriptFile(this, scriptName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(editor.getText().toString());
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        AccessibilityUtils.makeFileWorldReadable(file);
        Toast.makeText(this, R.string.script_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
    }
}
