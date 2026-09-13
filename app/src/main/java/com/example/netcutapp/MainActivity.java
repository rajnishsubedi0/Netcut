package com.example.netcutapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MainActivity extends AppCompatActivity
        implements DeviceAdapter.OnDeviceActionListener,
        BannedDeviceAdapter.OnUnbanListener,
        NetcutService.ServiceCallback {

    private NetcutService service;
    private boolean bound = false;
    private DeviceAdapter connectedAdapter;
    private BannedDeviceAdapter bannedAdapter;
    private RecyclerView rvConnected, rvBanned;
    private TextView tvStats;
    private Button btnStart, btnScan, btnBanAll, btnRestoreAll, btnTabConnected, btnTabBanned;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((NetcutService.LocalBinder) binder).getService();
            service.setCallback(MainActivity.this);
            bound = true;
            refreshUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        if (!RootManager.isRooted()) {
            Toast.makeText(this, "Root access required", Toast.LENGTH_LONG).show();
        }

        rvConnected = findViewById(R.id.rv_connected);
        rvBanned = findViewById(R.id.rv_banned);
        tvStats = findViewById(R.id.tv_stats);
        btnStart = findViewById(R.id.btn_start);
        btnScan = findViewById(R.id.btn_scan);
        btnBanAll = findViewById(R.id.btn_ban_all);
        btnRestoreAll = findViewById(R.id.btn_restore_all);
        btnTabConnected = findViewById(R.id.btn_tab_connected);
        btnTabBanned = findViewById(R.id.btn_tab_banned);

        rvConnected.setLayoutManager(new LinearLayoutManager(this));
        rvBanned.setLayoutManager(new LinearLayoutManager(this));

        btnStart.setOnClickListener(v -> toggleService());
        btnScan.setOnClickListener(v -> manualScan());
        btnBanAll.setOnClickListener(v -> banAll());
        btnRestoreAll.setOnClickListener(v -> restoreAll());
        btnTabConnected.setOnClickListener(v -> showTab(true));
        btnTabBanned.setOnClickListener(v -> showTab(false));

        showTab(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, NetcutService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            service.setCallback(null);
            unbindService(connection);
            bound = false;
        }
    }

    private void toggleService() {
        if (bound && service.isEngineRunning()) {
            service.stopEngine();
            btnStart.setText("Start Service");
        } else {
            String gateway = NetworkScanner.getGatewayIp(this);
            String iface = NetworkScanner.getInterfaceName();
            if (gateway == null) {
                Toast.makeText(this, "Not connected to WiFi", Toast.LENGTH_SHORT).show();
                return;
            }
            if (bound) {
                service.startEngine(iface, gateway);
                btnStart.setText("Stop Service");
                Toast.makeText(this, "Gateway: " + gateway, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void manualScan() {
        if (bound) {
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show();
        }
    }

    private void banAll() {
        if (!bound) return;
        for (Device d : service.getAllDevices()) {
            if (d.isOnline()) service.banDevice(d.getMac());
        }
        refreshUI();
    }

    private void restoreAll() {
        if (!bound) return;
        for (Device d : service.getBannedDevices()) {
            service.unbanDevice(d.getMac());
        }
        refreshUI();
    }

    private void showTab(boolean connected) {
        rvConnected.setVisibility(connected ? RecyclerView.VISIBLE : RecyclerView.GONE);
        rvBanned.setVisibility(connected ? RecyclerView.GONE : RecyclerView.VISIBLE);
        refreshUI();
    }

    private void refreshUI() {
        if (!bound) return;
        List<Device> connected = service.getAllDevices();
        List<Device> banned = service.getBannedDevices();

        connectedAdapter = new DeviceAdapter(connected, this);
        bannedAdapter = new BannedDeviceAdapter(banned, this);

        rvConnected.setAdapter(connectedAdapter);
        rvBanned.setAdapter(bannedAdapter);

        long onlineCount = 0;
        for (Device d : connected) {
            if (d.isOnline()) onlineCount++;
        }
        long bannedCount = banned.size();
        tvStats.setText(String.format("Online: %d | Banned: %d", onlineCount, bannedCount));

        btnStart.setText(service.isEngineRunning() ? "Stop Service" : "Start Service");
    }

    // ---- DeviceAdapter.OnDeviceActionListener ----

    @Override
    public void onBanClick(Device device) {
        if (!bound) return;
        if (device.isBanned()) {
            service.unbanDevice(device.getMac());
        } else {
            service.banDevice(device.getMac());
        }
        refreshUI();
    }

    @Override
    public void onPingClick(Device device) {
        if (bound) service.pingBinary();
    }

    @Override
    public void onNameClick(Device device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Device Name");
        final EditText input = new EditText(this);
        String currentName = device.getName();
        input.setText(currentName.equals("Unnamed") ? "" : currentName);
        builder.setView(input);
        builder.setPositiveButton("Save", (dialog, which) -> {
            if (bound) {
                service.updateDeviceName(device.getMac(), input.getText().toString());
                refreshUI();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ---- BannedDeviceAdapter.OnUnbanListener ----

    @Override
    public void onUnbanClick(Device device) {
        if (bound) {
            service.unbanDevice(device.getMac());
            refreshUI();
        }
    }

    // ---- NetcutService.ServiceCallback ----

    @Override
    public void onDataChanged() {
        runOnUiThread(this::refreshUI);
    }

    @Override
    public void onToastMessage(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}