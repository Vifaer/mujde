package com.rel.mujde;

import static android.content.Context.MODE_WORLD_READABLE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AppsFragment extends Fragment implements SearchView.OnQueryTextListener {
    private SharedPreferences pref;
    private RecyclerView appListRecyclerView;
    private SearchView searchView;
    private ProgressBar loadingProgress;
    private final List<ApplicationInfo> enabledApps = new ArrayList<>();
    private Map<String, List<String>> appScriptMappings = new HashMap<>();
    private Set<String> scopedPackages = new HashSet<>();
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGen = new AtomicInteger();
    private boolean loadedOnce = false;
    private ActivityResultLauncher<Intent> scriptSelectLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pref = safePrefs();
        scriptSelectLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        loadAppScriptMappings();
                        refreshScopeAndMappings();
                    }
                });
    }

    private SharedPreferences safePrefs() {
        try {
            return requireActivity().getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_WORLD_READABLE);
        } catch (Exception e) {
            return requireActivity().getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_apps, container, false);
        appListRecyclerView = view.findViewById(R.id.app_list);
        searchView = view.findViewById(R.id.search_view);
        loadingProgress = view.findViewById(R.id.loading_progress);
        appListRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        if (pref == null) pref = safePrefs();
        setupSearch();
        loadAppScriptMappings();
        loadEnabledApps();
        loadedOnce = true;
        return view;
    }

    private void setupSearch() {
        searchView.setSubmitButtonEnabled(false);
        searchView.setIconifiedByDefault(false);
        searchView.clearFocus();
        searchView.setQueryHint(getString(R.string.search_apps));
        EditText searchText = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
        if (searchText != null) {
            searchText.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface));
            searchText.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_muted));
        }
        searchView.setOnQueryTextListener(this);
    }

    private void loadAppScriptMappings() {
        if (pref == null) {
            appScriptMappings = new HashMap<>();
            return;
        }
        appScriptMappings = new HashMap<>(ScriptUtils.getAllAppScriptMappings(pref));
    }

    private void openScriptSelection(String packageName) {
        Intent intent = new Intent(requireContext(), ScriptSelectionActivity.class);
        intent.putExtra(Constants.INTENT_REQUEST_PACKAGE_NAME, packageName);
        scriptSelectLauncher.launch(intent);
    }

    private void injectNow(String packageName) {
        if (!isAdded() || packageName == null) return;
        Toast.makeText(requireContext(), R.string.inject_now_running, Toast.LENGTH_SHORT).show();
        final Context appCtx = requireContext().getApplicationContext();
        executor.execute(() -> {
            String result = InjectionRequestHandler.injectNow(appCtx, packageName);
            Activity activity = getActivity();
            if (activity == null || !isAdded()) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.inject_now)
                        .setMessage(getString(R.string.inject_now_done, result))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private void loadEnabledApps() {
        if (!isAdded() || loadingProgress == null) return;
        final int gen = loadGen.incrementAndGet();
        loadingProgress.setVisibility(View.VISIBLE);
        appListRecyclerView.setVisibility(View.GONE);

        final Context appCtx = requireContext().getApplicationContext();
        final Map<String, List<String>> mappingsSnapshot = new HashMap<>(appScriptMappings);

        executor.execute(() -> {
            Set<String> scoped = new HashSet<>();
            try {
                scoped = ScopeHelper.readScopedPackages(appCtx);
            } catch (Exception ignored) {
            }

            PackageManager pm = appCtx.getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            List<ApplicationInfo> filtered = new ArrayList<>();
            String self = appCtx.getPackageName();
            for (ApplicationInfo app : installed) {
                if (app.enabled && !app.packageName.equals(self)) {
                    filtered.add(app);
                }
            }
            filtered.sort((a, b) -> {
                boolean aBound = mappingsSnapshot.containsKey(a.packageName);
                boolean bBound = mappingsSnapshot.containsKey(b.packageName);
                if (aBound != bBound) return aBound ? -1 : 1;
                boolean aUser = (a.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                boolean bUser = (b.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                if (aUser != bUser) return aUser ? -1 : 1;
                String n1 = pm.getApplicationLabel(a).toString().toLowerCase();
                String n2 = pm.getApplicationLabel(b).toString().toLowerCase();
                return n1.compareTo(n2);
            });

            final Set<String> scopedFinal = scoped;
            final List<ApplicationInfo> listFinal = filtered;
            Activity activity = getActivity();
            if (activity == null || !isAdded() || gen != loadGen.get()) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || gen != loadGen.get()) return;
                enabledApps.clear();
                enabledApps.addAll(listFinal);
                scopedPackages = scopedFinal;
                AppListAdapter adapter = new AppListAdapter(
                        requireContext(),
                        enabledApps,
                        appScriptMappings,
                        scopedPackages,
                        new AppListAdapter.OnAppClickListener() {
                            @Override
                            public void onAppClick(String packageName) {
                                openScriptSelection(packageName);
                            }

                            @Override
                            public void onAppLongClick(String packageName) {
                                injectNow(packageName);
                            }
                        }
                );
                appListRecyclerView.setAdapter(adapter);
                loadingProgress.setVisibility(View.GONE);
                appListRecyclerView.setVisibility(View.VISIBLE);
            });
        });
    }

    /** 从脚本选择页返回后：重读 prefs + scope，刷新徽章（不重扫应用列表） */
    private void refreshScopeAndMappings() {
        if (!isAdded() || appListRecyclerView == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        final Map<String, List<String>> mappingsSnapshot = new HashMap<>(appScriptMappings);
        executor.execute(() -> {
            Set<String> scoped = new HashSet<>();
            try {
                scoped = ScopeHelper.readScopedPackages(appCtx);
            } catch (Exception ignored) {
            }
            final Set<String> scopedFinal = scoped;
            Activity activity = getActivity();
            if (activity == null || !isAdded()) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                scopedPackages = scopedFinal;
                AppListAdapter adapter = (AppListAdapter) appListRecyclerView.getAdapter();
                if (adapter != null) {
                    adapter.updateMappings(mappingsSnapshot);
                    adapter.updateScope(scopedFinal);
                } else {
                    loadEnabledApps();
                }
            });
        });
    }

    private void filterApps(String query) {
        AppListAdapter adapter = (AppListAdapter) appListRecyclerView.getAdapter();
        if (adapter == null || !isAdded()) return;
        if (TextUtils.isEmpty(query)) {
            adapter.updateList(enabledApps);
            return;
        }
        List<ApplicationInfo> filtered = new ArrayList<>();
        String q = query.toLowerCase();
        PackageManager pm = requireContext().getPackageManager();
        for (ApplicationInfo appInfo : enabledApps) {
            String appName = pm.getApplicationLabel(appInfo).toString().toLowerCase();
            if (appName.contains(q) || appInfo.packageName.toLowerCase().contains(q)) {
                filtered.add(appInfo);
            }
        }
        adapter.updateList(filtered);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        filterApps(query);
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        filterApps(newText);
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (loadedOnce && isVisible()) {
            loadAppScriptMappings();
            refreshScopeAndMappings();
        }
    }
}
