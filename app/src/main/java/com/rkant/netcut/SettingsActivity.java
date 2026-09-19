package com.rkant.netcut;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private Switch switchUnknownAlerts;
    private EditText etScanInterval;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        switchUnknownAlerts = findViewById(R.id.switch_unknown_alerts);
        etScanInterval = findViewById(R.id.et_scan_interval);
        Button btnSave = findViewById(R.id.btn_save_settings);

        loadSettings();

        btnSave.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(NetcutService.PREFS_NAME, MODE_PRIVATE);

        boolean alerts = prefs.getBoolean(NetcutService.KEY_UNKNOWN_ALERTS, true);
        int interval = prefs.getInt(NetcutService.KEY_SCAN_INTERVAL, 15);

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

        SharedPreferences prefs = getSharedPreferences(NetcutService.PREFS_NAME, MODE_PRIVATE);

        prefs.edit()
                .putBoolean(NetcutService.KEY_UNKNOWN_ALERTS, switchUnknownAlerts.isChecked())
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