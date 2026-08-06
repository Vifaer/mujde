package com.rel.mujde;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
    private final Context context;
    private List<ApplicationInfo> appList;
    private final Map<String, List<String>> mappings;
    private Set<String> scoped;
    private final OnAppClickListener onAppClickListener;

    public AppListAdapter(
            Context context,
            List<ApplicationInfo> appList,
            Map<String, List<String>> mappings,
            Set<String> scoped,
            OnAppClickListener onAppClickListener) {
        this.context = context;
        this.appList = appList;
        this.mappings = mappings;
        this.scoped = scoped;
        this.onAppClickListener = onAppClickListener;
    }

    public interface OnAppClickListener {
        void onAppClick(String packageName);

        /** 长按：立即注入（可为空操作）。 */
        default void onAppLongClick(String packageName) {
        }
    }

    public void updateScope(Set<String> newScoped) {
        this.scoped = newScoped != null ? newScoped : java.util.Collections.emptySet();
        notifyDataSetChanged();
    }

    public void updateMappings(Map<String, List<String>> newMappings) {
        this.mappings.clear();
        if (newMappings != null) this.mappings.putAll(newMappings);
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName;
        TextView packageName;
        TextView badge;

        ViewHolder(View view) {
            super(view);
            appIcon = view.findViewById(R.id.app_icon);
            appName = view.findViewById(R.id.app_name);
            packageName = view.findViewById(R.id.package_name);
            badge = view.findViewById(R.id.app_badge);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final ApplicationInfo app = appList.get(position);
        PackageManager pm = context.getPackageManager();
        holder.appIcon.setImageDrawable(app.loadIcon(pm));
        holder.appName.setText(app.loadLabel(pm));
        holder.packageName.setText(app.packageName);

        List<String> scripts = mappings.get(app.packageName);
        int count = scripts == null ? 0 : scripts.size();
        boolean inScope = scoped.contains(app.packageName);
        String badge = (count > 0
                ? context.getString(R.string.badge_scripts, count)
                : "—")
                + "  ·  "
                + context.getString(inScope ? R.string.badge_scope_yes : R.string.badge_scope_no);
        holder.badge.setText(badge);
        int color;
        if (count > 0 && inScope) color = R.color.success;
        else if (count > 0) color = R.color.accent;
        else if (inScope) color = R.color.on_surface_muted;
        else color = R.color.on_surface_muted;
        holder.badge.setTextColor(ContextCompat.getColor(context, color));

        holder.itemView.setOnClickListener(v -> {
            if (onAppClickListener != null) {
                onAppClickListener.onAppClick(app.packageName);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (onAppClickListener != null) {
                onAppClickListener.onAppLongClick(app.packageName);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    public void updateList(List<ApplicationInfo> newList) {
        this.appList = newList;
        notifyDataSetChanged();
    }
}
