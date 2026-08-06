package com.rel.mujde;

import static android.content.Context.MODE_PRIVATE;
import static android.content.Context.MODE_WORLD_READABLE;

import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class ActivityMain extends AppCompatActivity {
    private BottomNavigationView bottomNavigationView;
    private Fragment currentFragment;
    private HomeFragment homeFragment;
    private ScriptsFragment scriptsFragment;
    private AppsFragment appsFragment;
    private LogFragment logFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        boolean xposedPrefs = true;
        try {
            getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_WORLD_READABLE);
        } catch (Exception e) {
            xposedPrefs = false;
            try {
                getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_PRIVATE);
            } catch (Exception ignored) {
            }
        }

        try {
            InjectionService.start(this);
        } catch (Exception ignored) {
        }

        initializeFragments();
        setupNavigation();
        setupBackPressHandling();

        if (!xposedPrefs) {
            Toast.makeText(this, R.string.prefs_fallback_warning, Toast.LENGTH_LONG).show();
            new AlertDialog.Builder(this)
                    .setMessage(R.string.prefs_fallback_warning)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void setupBackPressHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentFragment != homeFragment) {
                    bottomNavigationView.setSelectedItemId(R.id.navigation_home);
                    return;
                }
                finish();
            }
        });
    }

    private void initializeFragments() {
        homeFragment = new HomeFragment();
        scriptsFragment = new ScriptsFragment();
        appsFragment = new AppsFragment();
        logFragment = new LogFragment();
    }

    private void setupNavigation() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.navigation_home) {
                showFragment(homeFragment, R.string.title_status);
            } else if (id == R.id.navigation_scripts) {
                showFragment(scriptsFragment, R.string.title_scripts);
            } else if (id == R.id.navigation_apps) {
                showFragment(appsFragment, R.string.title_apps);
            } else if (id == R.id.navigation_logs) {
                showFragment(logFragment, R.string.title_logs);
            } else {
                return false;
            }
            return true;
        });
        bottomNavigationView.setSelectedItemId(R.id.navigation_home);
    }

    /** 用 show/hide，避免 replace 后复用同一 Fragment 实例导致闪退 */
    private void showFragment(Fragment target, int titleRes) {
        if (target == null || target == currentFragment) {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(titleRes);
            }
            return;
        }

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);

        if (currentFragment != null && currentFragment.isAdded()) {
            ft.hide(currentFragment);
        }

        if (!target.isAdded()) {
            ft.add(R.id.fragment_container, target, target.getClass().getSimpleName());
        } else {
            ft.show(target);
        }

        currentFragment = target;
        ft.commitAllowingStateLoss();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titleRes);
        }
    }
}
