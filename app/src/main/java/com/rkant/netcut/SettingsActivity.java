package com.rkant.netcut;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private MaterialSwitch switchUnknownAlerts;
    private MaterialSwitch switchAutoBan;
    private EditText etScanInterval;
    private EditText etWakeLockTime;
    private MaterialButtonToggleGroup themeToggle;
    private MaterialButton btnThemeSystem, btnThemeLight, btnThemeDark;
    private MaterialButton btnRepairBinary;
    private ProgressBar progressRepair;
    private int appliedThemeMode;

    private boolean isRepairing = false;
    private final ExecutorService repairExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        appliedThemeMode = ThemeManager.getSavedMode(this);

        switchUnknownAlerts = findViewById(R.id.switch_unknown_alerts);
        switchAutoBan = findViewById(R.id.switch_auto_ban);
        etScanInterval = findViewById(R.id.et_scan_interval);
        etWakeLockTime = findViewById(R.id.et_wakelock_time);
        Button btnSave = findViewById(R.id.btn_save_settings);
        themeToggle = findViewById(R.id.theme_toggle_group);
        btnThemeSystem = findViewById(R.id.btn_theme_system);
        btnThemeLight = findViewById(R.id.btn_theme_light);
        btnThemeDark = findViewById(R.id.btn_theme_dark);
        btnRepairBinary = findViewById(R.id.btn_repair_binary);
        progressRepair = findViewById(R.id.progress_repair);

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
        btnRepairBinary.setOnClickListener(v -> confirmRepairBinary());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ThemeManager.getSavedMode(this) != appliedThemeMode) {
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isRepairing) {
            repairExecutor.shutdownNow();
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ──────────────────────────────────────────────
    //  BINARY REPAIR (Troubleshooting)
    // ──────────────────────────────────────────────

    private void confirmRepairBinary() {
        if (isRepairing) return;
        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle("Repair Engine Binary?")
                .setMessage("This will:\n\n" +
                        "• Stop the Netcut service\n" +
                        "• Delete the engine binary from /data/local/tmp\n" +
                        "• Re-extract a fresh binary and reset its permissions\n\n" +
                        "Use this if Netcut fails to start or stops banning devices.")
                .setPositiveButton("Repair", (d, w) -> startRepairBinary())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startRepairBinary() {
        if (isRepairing) return;
        setRepairingState(true);

        // Stop the foreground service first so the engine releases the
        // binary before we delete and replace it.
        try {
            Intent stopIntent = new Intent(this, NetcutService.class);
            stopIntent.setAction(NetcutService.ACTION_STOP);
            startService(stopIntent);
        } catch (Exception ignored) {
        }

        repairExecutor.execute(() -> {
            // Give the service a moment to stop the engine gracefully.
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }

            BinaryManager manager = new BinaryManager(getApplicationContext());
            BinaryManager.RepairResult result = manager.repairBinary();

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                setRepairingState(false);
                showRepairResult(result);
            });
        });
    }

    private void setRepairingState(boolean repairing) {
        isRepairing = repairing;
        if (btnRepairBinary == null) return;
        btnRepairBinary.setEnabled(!repairing);
        btnRepairBinary.setText(repairing ? "Repairing…" : "Repair Now");
        if (progressRepair != null) {
            progressRepair.setVisibility(repairing ? View.VISIBLE : View.GONE);
        }
    }

    private void showRepairResult(BinaryManager.RepairResult result) {
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle(result.success ? "Repair Complete" : "Repair Failed")
                .setMessage(result.message + (result.success
                        ? "\n\nYou can now start the service again from the home screen."
                        : "\n\nMake sure root (Magisk / KernelSU / APatch) is granted to Netcut, then try again."))
                .setPositiveButton("OK", null)
                .show();
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
            button.setBackgroundTintList(ColorStateList.valueOf(getColor(android.R.color.transparent)));
            button.setTextColor(getColor(R.color.tab_unselected_text));
        }
    }


    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(NetcutService.PREFS_NAME, MODE_PRIVATE);
        boolean autoBan = prefs.getBoolean(NetcutService.KEY_AUTO_BAN_NEW_DEVICES, false);
        boolean alerts = prefs.getBoolean(NetcutService.KEY_UNKNOWN_ALERTS, true);
        int interval = prefs.getInt(NetcutService.KEY_SCAN_INTERVAL, 15);
        int wakeLockMinutes = prefs.getInt(NetcutService.KEY_WAKELOCK_MINUTES, 60);
        if (autoBan) alerts = false;
        switchAutoBan.setChecked(autoBan);
        switchUnknownAlerts.setChecked(alerts);
        etScanInterval.setText(String.valueOf(interval));
        etWakeLockTime.setText(String.valueOf(wakeLockMinutes));
    }

    private void saveSettings() {
        int interval = 15;
        try {
            interval = Integer.parseInt(etScanInterval.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (interval < 5) interval = 5;
        if (interval > 300) interval = 300;

        int wakeLockMinutes = 60;
        try {
            wakeLockMinutes = Integer.parseInt(etWakeLockTime.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (wakeLockMinutes < 0) wakeLockMinutes = 0;
        if (wakeLockMinutes > 1440) wakeLockMinutes = 1440;

        boolean autoBan = switchAutoBan.isChecked();
        boolean alerts = switchUnknownAlerts.isChecked();
        if (autoBan) alerts = false;

        SharedPreferences prefs = getSharedPreferences(NetcutService.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(NetcutService.KEY_UNKNOWN_ALERTS, alerts)
                .putBoolean(NetcutService.KEY_AUTO_BAN_NEW_DEVICES, autoBan)
                .putInt(NetcutService.KEY_SCAN_INTERVAL, interval)
                .putInt(NetcutService.KEY_WAKELOCK_MINUTES, wakeLockMinutes)
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