package com.rkant.netcut;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {

    private MaterialSwitch switchUnknownAlerts;
    private MaterialSwitch switchAutoBan;
    private EditText etScanInterval;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        switchUnknownAlerts = findViewById(R.id.switch_unknown_alerts);
        switchAutoBan = findViewById(R.id.switch_auto_ban);
        etScanInterval = findViewById(R.id.et_scan_interval);

        Button btnSave = findViewById(R.id.btn_save_settings);
        switchUnknownAlerts = findViewById(R.id.switch_unknown_alerts);

        loadSettings();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });


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

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(NetcutService.PREFS_NAME, MODE_PRIVATE);

        boolean autoBan = prefs.getBoolean(NetcutService.KEY_AUTO_BAN_NEW_DEVICES, false);
        boolean alerts = prefs.getBoolean(NetcutService.KEY_UNKNOWN_ALERTS, true);

        int interval = prefs.getInt(NetcutService.KEY_SCAN_INTERVAL, 15);

        // Enforce mutual exclusion
        if (autoBan) {
            alerts = false;
        }

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

        // Enforce mutual exclusion again before saving
        if (autoBan) {
            alerts = false;
        }

        if (alerts) {
            autoBan = false;
        }

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