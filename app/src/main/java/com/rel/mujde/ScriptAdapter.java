package com.rel.mujde;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ScriptAdapter extends RecyclerView.Adapter<ScriptAdapter.ViewHolder> {
    private final Context context;
    private final List<String> scripts;
    private final ScriptActionListener listener;

    public interface ScriptActionListener {
        void onEditScript(String scriptName);
        void onDeleteScript(String scriptName);
    }

    public ScriptAdapter(Context context, List<String> scripts, ScriptActionListener listener) {
        this.context = context;
        this.scripts = scripts;
        this.listener = listener;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView scriptNameTextView;
        ImageButton editButton;
        ImageButton deleteButton;

        ViewHolder(View view) {
            super(view);
            scriptNameTextView = view.findViewById(R.id.text_script_name);
            editButton = view.findViewById(R.id.btn_edit_script);
            deleteButton = view.findViewById(R.id.btn_delete_script);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_script, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final String scriptName = scripts.get(position);
        holder.scriptNameTextView.setText(scriptName);
        holder.editButton.setOnClickListener(v -> {
            if (listener != null) listener.onEditScript(scriptName);
        });
        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteScript(scriptName);
        });
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEditScript(scriptName);
        });
    }

    @Override
    public int getItemCount() {
        return scripts.size();
    }
}
