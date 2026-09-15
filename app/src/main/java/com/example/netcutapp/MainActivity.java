package com.example.netcutapp;

import android.os.Build;
import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;public class MainActivity extends AppCompatActivity
        implements DeviceAdapter.OnDeviceActionListener,
        BannedDeviceAdapter.OnUnbanListener,
        NetcutService.ServiceCallback {


    private static final int NOTIFICATION_PERMISSION_CODE = 101;
    private static final String PREFS_NAME = "netcut_prefs";
    private static final String KEY_OEM_HINT_SHOWN = "oem_hint_shown";

    // ✅ Resets to false when the app process is killed and restarted (= each app boot).
    // This ensures the battery warning re-appears on EVERY fresh launch until granted.
    private static boolean batteryWarningShownThisSession = false;

    private NetcutService service;
    private boolean bound = false;
    private DeviceAdapter connectedAdapter;
    private BannedDeviceAdapter bannedAdapter;
    private RecyclerView rvConnected, rvBanned;
    private TextView tvStats;
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
        checkRootAccess();
        checkNotificationPermission();
        checkArchitectureSupport();
        showOemBatteryHint(); // one-time guidance for aggressive OEMs

        rvConnected = findViewById(R.id.rv_connected);
        rvBanned = findViewById(R.id.rv_banned);
        tvStats = findViewById(R.id.tv_stats);
        btnStart = findViewById(R.id.btn_start);
        btnBanAll = findViewById(R.id.btn_ban_all);
        btnRestoreAll = findViewById(R.id.btn_restore_all);
        btnTabConnected = findViewById(R.id.btn_tab_connected);
        btnTabBanned = findViewById(R.id.btn_tab_banned);

        // ✅ SwipeRefreshLayout
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setDistanceToTriggerSync(550);
        swipeRefresh.setOnRefreshListener(() -> {
            if (bound && service != null && service.isEngineRunning()) {
                Toast.makeText(this, "Scanning network...", Toast.LENGTH_SHORT).show();
                service.forceScan();
            } else {
                Toast.makeText(this, "Service is not running. Start it first.", Toast.LENGTH_SHORT).show();
                swipeRefresh.setRefreshing(false);
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
    protected void onResume() {
        super.onResume();
        // ✅ Battery optimization check runs on every resume (and every app boot).
        // Placed in onResume so it fires after returning from the settings screen too.
        checkBatteryOptimization();
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

    // ========================================================================
    // ✅ PERMISSION 1: ROOT ACCESS (blocking)
    // ========================================================================
    private void checkRootAccess() {
        if (!RootManager.isRooted()) {
            new AlertDialog.Builder(this)
                    .setTitle("⚠ Root Access Required")
                    .setMessage("This app REQUIRES root to function. It uses root for:\n\n" +
                            "• Sending ARP packets (raw sockets)\n" +
                            "• Reading the network device list\n" +
                            "• Running the netcut engine\n\n" +
                            "Please grant root (Magisk/SuperSU) to this app, then retry.")
                    .setCancelable(false)
                    .setPositiveButton("Retry Check", (d, w) -> checkRootAccess())
                    .setNegativeButton("Exit App", (d, w) -> finish())
                    .show();
        }
    }

    // ========================================================================
    // ✅ PERMISSION 2: NOTIFICATIONS (Android 13+ runtime permission)
    // ========================================================================
    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted ✔", Toast.LENGTH_SHORT).show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Notifications Disabled")
                        .setMessage("Without notification permission, Android may kill the background service aggressively. " +
                                "Banned devices could regain internet access when the app is in the background.\n\n" +
                                "It is strongly recommended to allow notifications.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        }
    }

    // ========================================================================
    // ✅ PERMISSION 3: BATTERY OPTIMIZATION (mandatory, re-asked every boot)
    // ========================================================================
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;

            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                if (!batteryWarningShownThisSession) {
                    // First time this app boot → show the full explanatory dialog
                    showBatteryOptimizationDialog();
                } else {
                    // Already asked this session but user returned without granting → subtle reminder
                    Toast.makeText(this,
                            "⚠ Battery optimization is still ON. Background service may be killed.",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void showBatteryOptimizationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("🔋 Disable Battery Optimization")
                .setMessage("This app must run continuously in the background to keep devices banned.\n\n" +
                        "If Android optimizes this app's battery, the OS will KILL the service when the screen turns off, " +
                        "and all banned devices will instantly regain internet access.\n\n" +
                        "Tap 'Allow Now'. A system popup will appear — just tap ALLOW.")
                .setCancelable(false)
                .setPositiveButton("Allow Now", (d, w) -> {
                    batteryWarningShownThisSession = true;
                    requestIgnoreBatteryOptimizations();
                })
                .setNegativeButton("Not Now", (d, w) -> {
                    batteryWarningShownThisSession = true;
                    Toast.makeText(this,
                            "Warning: service may stop when the app is in background.",
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    /**
     * Opens Android's native "Ignore battery optimizations" popup.
     * Falls back gracefully if the direct popup is blocked (some OEMs restrict it).
     */
    private void requestIgnoreBatteryOptimizations() {
        try {
            // ✅ BEST: Direct system popup (one-tap "Allow" for the user)
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                // Fallback 1: Battery optimization list screen
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
                Toast.makeText(this,
                        "Find this app in the list → choose 'Don't optimize'",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                try {
                    // Fallback 2: General settings
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                    Toast.makeText(this,
                            "Go to Battery settings and exclude this app manually",
                            Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ========================================================================
    // ✅ OEM-SPECIFIC BATTERY SAVER GUIDANCE (shown once ever)
    // ========================================================================
    private void showOemBatteryHint() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_OEM_HINT_SHOWN, false)) return;

        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String hint = null;

        if (manufacturer.contains("samsung")) {
            hint = "Samsung detected: Also go to Settings → Battery → Background usage limits → add this app to 'Never sleeping apps'.";
        } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            hint = "Xiaomi detected: Also go to Settings → Apps → this app → Battery saver → select 'No restrictions'.";
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            hint = "Huawei detected: Also go to Settings → Battery → App launch → this app → enable 'Manage manually'.";
        } else if (manufacturer.contains("oneplus")) {
            hint = "OnePlus detected: Also go to Settings → Battery → App optimization → this app → 'Don't optimize'.";
        } else if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            hint = "Oppo/Realme detected: Also allow this app in 'Auto-start' and set battery to 'Allow background activity'.";
        }

        if (hint != null) {
            String finalHint = hint;
            new AlertDialog.Builder(this)
                    .setTitle("Device-Specific Setup")
                    .setMessage(finalHint + "\n\nThis extra step prevents your device manufacturer from killing the app.")
                    .setPositiveButton("Got it", (d, w) ->
                            prefs.edit().putBoolean(KEY_OEM_HINT_SHOWN, true).apply())
                    .setCancelable(false)
                    .show();
        } else {
            prefs.edit().putBoolean(KEY_OEM_HINT_SHOWN, true).apply();
        }
    }

    // ========================================================================
    // Existing app logic (unchanged)
    // ========================================================================

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
            btnTabConnected.setBackgroundColor(0xFF2196F3);
            btnTabConnected.setTextColor(0xFFFFFFFF);
            btnTabBanned.setBackgroundColor(0xFFEEEEEE);
            btnTabBanned.setTextColor(0xFF000000);
        } else {
            btnTabBanned.setBackgroundColor(0xFF2196F3);
            btnTabBanned.setTextColor(0xFFFFFFFF);
            btnTabConnected.setBackgroundColor(0xFFEEEEEE);
            btnTabConnected.setTextColor(0xFF000000);
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

    // DeviceAdapter Callbacks
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
            String msg = (output != null && output.contains("time="))
                    ? ip + " is reachable — " + output.substring(output.indexOf("time=") + 5).trim().split(" ")[0] + " ms"
                    : ip + " is unreachable";
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
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    // BannedDeviceAdapter Callbacks
    @Override
    public void onUnbanClick(Device device) {
        if (bound) {
            service.unbanDevice(device.getMac());
            refreshUI();
        }
    }
    private void checkArchitectureSupport() {
        BinaryManager bm = new BinaryManager(this);
        String arch = bm.detectArchitecture();

        if (arch == null) {
            new AlertDialog.Builder(this)
                    .setTitle("❌ Unsupported Architecture")
                    .setMessage("This device uses an unsupported CPU architecture.\n\n" +
                            "Detected ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS) + "\n\n" +
                            "This app only supports:\n• arm64-v8a (64-bit ARM)\n• armeabi-v7a (32-bit ARM)\n\n" +
                            "The app cannot function on this device.")
                    .setCancelable(false)
                    .setPositiveButton("Exit", (d, w) -> finish())
                    .show();
        } else {
            Log.d("MainActivity", "Architecture supported: " + arch +
                    " | ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        }
    }
    // Service Callbacks
    @Override
    public void onDataChanged() {
        runOnUiThread(() -> {
            refreshUI();
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        });
    }

    @Override
    public void onToastMessage(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}