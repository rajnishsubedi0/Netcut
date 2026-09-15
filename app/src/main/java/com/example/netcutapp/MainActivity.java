package com.example.netcutapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
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

    // ✅ Removed btn_scan, added swipeRefresh
    private Button btnStart, btnBanAll, btnRestoreAll, btnTabConnected, btnTabBanned;
    private SwipeRefreshLayout swipeRefresh;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((NetcutService.LocalBinder) binder).getService();
            service.setCallback(MainActivity.this);
            bound = true;

            // Auto-start service and scan on app launch if rooted
            if (RootManager.isRooted() && !service.isEngineRunning()) {
                String gateway = NetworkScanner.getGatewayIp(MainActivity.this);
                String iface = NetworkScanner.getInterfaceName();
                if (gateway != null) {
                    service.startEngine(iface, gateway);
                    btnStart.setText("Stop Service");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> service.forceScan(), 1000);
                }
            }
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
        btnBanAll = findViewById(R.id.btn_ban_all);
        btnRestoreAll = findViewById(R.id.btn_restore_all);
        btnTabConnected = findViewById(R.id.btn_tab_connected);
        btnTabBanned = findViewById(R.id.btn_tab_banned);

        // ✅ Initialize SwipeRefreshLayout
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setDistanceToTriggerSync(550);
        swipeRefresh.setOnRefreshListener(() -> {
            if (bound && service.isEngineRunning()) {
                Toast.makeText(this, "Scanning network...", Toast.LENGTH_SHORT).show();
                service.forceScan();
            } else {
                Toast.makeText(this, "Service is not running. Start it first.", Toast.LENGTH_SHORT).show();
                swipeRefresh.setRefreshing(false); // Stop spinner if service isn't running
            }
        });

        rvConnected.setLayoutManager(new LinearLayoutManager(this));
        rvBanned.setLayoutManager(new LinearLayoutManager(this));

        // Initialize adapters ONCE to prevent jerky UI movements
        connectedAdapter = new DeviceAdapter(new ArrayList<>(), this);
        bannedAdapter = new BannedDeviceAdapter(new ArrayList<>(), this);
        rvConnected.setAdapter(connectedAdapter);
        rvBanned.setAdapter(bannedAdapter);

        btnStart.setOnClickListener(v -> toggleService());
        btnBanAll.setOnClickListener(v -> banAll());
        btnRestoreAll.setOnClickListener(v -> restoreAll());
        btnTabConnected.setOnClickListener(v -> showTab(true));
        btnTabBanned.setOnClickListener(v -> showTab(false));

        showTab(true); // Default to Connected tab
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

    private void banAll() {
        if (!bound) return;
        for (Device d : service.getConnectedDevices()) {
            if (d.isOnline()) {
                d.setBanned(true);
                service.banDevice(d.getMac(), d.getIp());
            }
        }
        if (connectedAdapter != null) connectedAdapter.notifyDataSetChanged();
    }

    private void restoreAll() {
        if (!bound) return;
        for (Device d : service.getConnectedDevices()) {
            d.setBanned(false);
        }
        if (connectedAdapter != null) connectedAdapter.notifyDataSetChanged();

        new Thread(() -> {
            for (Device d : service.getBannedDevices()) {
                service.unbanDevice(d.getMac());
            }
        }).start();
    }

    private void showTab(boolean connected) {
        rvConnected.setVisibility(connected ? RecyclerView.VISIBLE : RecyclerView.GONE);
        rvBanned.setVisibility(connected ? RecyclerView.GONE : RecyclerView.VISIBLE);

        if (connected) {
            btnTabConnected.setBackgroundColor(0xFF2196F3); btnTabConnected.setTextColor(0xFFFFFFFF);
            btnTabBanned.setBackgroundColor(0xFFEEEEEE); btnTabBanned.setTextColor(0xFF000000);
        } else {
            btnTabBanned.setBackgroundColor(0xFF2196F3); btnTabBanned.setTextColor(0xFFFFFFFF);
            btnTabConnected.setBackgroundColor(0xFFEEEEEE); btnTabConnected.setTextColor(0xFF000000);
        }
    }

    private void refreshUI() {
        if (!bound) return;
        List<Device> connected = service.getConnectedDevices();
        List<Device> banned = service.getBannedDevices();

        connectedAdapter.updateDevices(connected);
        bannedAdapter.updateDevices(banned);

        long onlineCount = connected.stream().filter(Device::isOnline).count();
        long bannedCount = banned.size();
        tvStats.setText(String.format("Online: %d | Banned: %d", onlineCount, bannedCount));
        btnStart.setText(service.isEngineRunning() ? "Stop Service" : "Start Service");
    }

    @Override
    public void onBanClick(Device device, int position) {
        boolean newBannedState = !device.isBanned();
        device.setBanned(newBannedState);
        if (connectedAdapter != null) connectedAdapter.notifyItemChanged(position);

        new Thread(() -> {
            if (newBannedState) service.banDevice(device.getMac(), device.getIp());
            else service.unbanDevice(device.getMac());
        }).start();
    }

    @Override
    public void onPingClick(Device device) {
        Toast.makeText(this, "Pinging " + device.getIp() + "...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String ip = device.getIp();
            String output = RootManager.execute("ping -c 1 -W 1 " + ip);
            String msg = (output != null && output.contains("time=")) ?
                    ip + " is reachable — " + output.substring(output.indexOf("time=") + 5).trim().split(" ")[0] + " ms" :
                    ip + " is unreachable";
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }).start();
    }

    @Override
    public void onNameClick(Device device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Device Name");
        final EditText input = new EditText(this);
        input.setText(device.getName().equals("Unnamed") ? "" : device.getName());
        builder.setView(input);
        builder.setPositiveButton("Save", (dialog, which) -> {
            if (bound) {
                service.updateDeviceName(device.getMac(), device.getIp(), input.getText().toString());
                refreshUI();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public void onUnbanClick(Device device) {
        if (bound) {
            service.unbanDevice(device.getMac());
            refreshUI();
        }
    }

    // ✅ Service Callbacks
    @Override
    public void onDataChanged() {
        runOnUiThread(() -> {
            refreshUI();
            // ✅ Stop the swipe refresh spinner when new data arrives
            if (swipeRefresh != null) {
                swipeRefresh.setRefreshing(false);
            }
        });
    }

    @Override
    public void onToastMessage(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}