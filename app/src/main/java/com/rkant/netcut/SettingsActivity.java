package com.rkant.netcut;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {
    private MaterialSwitch switchUnknownAlerts;
    private MaterialSwitch switchAutoBan;
    private EditText etScanInterval;
    private MaterialButtonToggleGroup themeToggle;
    private MaterialButton btnThemeSystem, btnThemeLight, btnThemeDark;
    private int appliedThemeMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        appliedThemeMode = ThemeManager.getSavedMode(this);

        switchUnknownAlerts = findViewById(R.id.switch_unknown_alerts);
        switchAutoBan = findViewById(R.id.switch_auto_ban);
        etScanInterval = findViewById(R.id.et_scan_interval);
        Button btnSave = findViewById(R.id.btn_save_settings);

        themeToggle = findViewById(R.id.theme_toggle_group);
        btnThemeSystem = findViewById(R.id.btn_theme_system);
        btnThemeLight = findViewById(R.id.btn_theme_light);
        btnThemeDark = findViewById(R.id.btn_theme_dark);
        setupThemeSelector();

        loadSettings();

        switchAutoBan.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && switchUnknownAlerts.isChecked()) {
                switchUnknownAlerts.setChecked(false);
            }
        });
        switchUnknownAlerts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && switchAutoBan.isChecked()) {
                switchAutoBan.setChecked(false);
            }
        });
        btnSave.setOnClickListener(v -> saveSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ThemeManager.getSavedMode(this) != appliedThemeMode) {
            recreate();
        }
    }

    // ──────────────────────────────────────────────
    //  THEME SELECTOR
    // ──────────────────────────────────────────────
    private void setupThemeSelector() {
        int saved = ThemeManager.getSavedMode(this);
        themeToggle.check(saved == ThemeManager.MODE_LIGHT ? R.id.btn_theme_light
                : saved == ThemeManager.MODE_DARK ? R.id.btn_theme_dark
                  : R.id.btn_theme_system);
        applyThemeTabStyles(saved);

        themeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            int mode;
            if (checkedId == R.id.btn_theme_light) mode = ThemeManager.MODE_LIGHT;
            else if (checkedId == R.id.btn_theme_dark) mode = ThemeManager.MODE_DARK;
            else mode = ThemeManager.MODE_SYSTEM;

            if (mode == ThemeManager.getSavedMode(this)) return;

            ThemeManager.setThemeMode(this, mode);
            appliedThemeMode = mode;
            applyThemeTabStyles(mode);
            recreate(); // relaunch settings with new theme
        });
    }

    private void applyThemeTabStyles(int mode) {
        styleThemeButton(btnThemeSystem, mode == ThemeManager.MODE_SYSTEM);
        styleThemeButton(btnThemeLight, mode == ThemeManager.MODE_LIGHT);
        styleThemeButton(btnThemeDark, mode == ThemeManager.MODE_DARK);
    }

    private void styleThemeButton(MaterialButton button, boolean selected) {
        if (button == null) return;
        if (selected) {
            button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tab_selected_bg)));
            button.setTextColor(getColor(R.color.tab_selected_text));
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(android.R.color.transparent));
            button.setTextColor(getColor(R.color.tab_unselected_text));
        }
    }

    // ──────────────────────────────────────────────
    //  EXISTING SETTINGS LOGIC (unchanged)
    // ──────────────────────────────────────────────
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(NetcutService.PREFS_NAME, MODE_PRIVATE);
        boolean autoBan = prefs.getBoolean(NetcutService.KEY_AUTO_BAN_NEW_DEVICES, false);
        boolean alerts = prefs.getBoolean(NetcutService.KEY_UNKNOWN_ALERTS, true);
        int interval = prefs.getInt(NetcutService.KEY_SCAN_INTERVAL, 15);
        if (autoBan) alerts = false;
        switchAutoBan.setChecked(autoBan);
        switchUnknownAlerts.setChecked(alerts);
        etScanInterval.setText(String.valueOf(interval));
    }

    private void saveSettings() {
        int interval = 15;
        try {
            interval = Integer.parseInt(etScanInterval.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (interval < 5) interval = 5;
        if (interval > 300) interval = 300;

        boolean autoBan = switchAutoBan.isChecked();
        boolean alerts = switchUnknownAlerts.isChecked();
        if (autoBan) alerts = false;
        if (alerts) autoBan = false;

        SharedPreferences prefs = getSharedPreferences(NetcutService.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(NetcutService.KEY_UNKNOWN_ALERTS, alerts)
                .putBoolean(NetcutService.KEY_AUTO_BAN_NEW_DEVICES, autoBan)
                .putInt(NetcutService.KEY_SCAN_INTERVAL, interval)
                .apply();
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        try {
            Intent intent = new Intent(this, NetcutService.class);
            intent.setAction(NetcutService.ACTION_APPLY_SETTINGS);
            startService(intent);
        } catch (Exception ignored) {
        }
        finish();
    }
}