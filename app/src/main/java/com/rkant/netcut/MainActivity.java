package com.rkant.netcut;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements DeviceAdapter.OnDeviceActionListener,
        BannedDeviceAdapter.OnUnbanListener,
        NetcutService.ServiceCallback {

    private static final int NOTIFICATION_PERMISSION_CODE = 101;
    private static final String PREFS_NAME = "netcut_prefs";
    private static final String KEY_OEM_HINT_SHOWN = "oem_hint_shown";
    private static boolean batteryWarningShownThisSession = false;

    private NetcutService service;
    private boolean bound = false;
    private DeviceAdapter connectedAdapter;
    private BannedDeviceAdapter bannedAdapter;
    private RecyclerView rvConnected, rvBanned;
    private TextView tvStats;
    private Button btnStart, btnBanAll, btnRestoreAll, btnTabConnected, btnTabBanned;
    private SwipeRefreshLayout swipeRefresh;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((NetcutService.LocalBinder) binder).getService();
            service.setCallback(MainActivity.this);
            bound = true;
            refreshUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) { bound = false; }
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
        checkRootAccessAsync();
        checkNotificationPermission(); // ✅ FIX 3: Request notification permission
        checkArchitectureSupport();
        showOemBatteryHint();

        rvConnected = findViewById(R.id.rv_connected);
        rvBanned = findViewById(R.id.rv_banned);
        tvStats = findViewById(R.id.tv_stats);
        btnStart = findViewById(R.id.btn_start);
        btnBanAll = findViewById(R.id.btn_ban_all);
        btnRestoreAll = findViewById(R.id.btn_restore_all);
        btnTabConnected = findViewById(R.id.btn_tab_connected);
        btnTabBanned = findViewById(R.id.btn_tab_banned);
        swipeRefresh = findViewById(R.id.swipe_refresh);

        swipeRefresh.setDistanceToTriggerSync(550);
        swipeRefresh.setOnRefreshListener(() -> {
            if (bound && service != null && service.isEngineRunning()) {
                Toast.makeText(this, "Scanning network...", Toast.LENGTH_SHORT).show();
                service.forceScan();
            } else if (bound && service != null && service.isManualModeActive()) {
                Toast.makeText(this, "Waiting for WiFi...", Toast.LENGTH_SHORT).show();
                swipeRefresh.setRefreshing(false);
            } else {
                Toast.makeText(this, "Service is not running. Start it first.", Toast.LENGTH_SHORT).show();
                swipeRefresh.setRefreshing(false);
            }
        });

        rvConnected.setLayoutManager(new LinearLayoutManager(this));
        rvBanned.setLayoutManager(new LinearLayoutManager(this));

        connectedAdapter = new DeviceAdapter(new ArrayList<>(), this);
        bannedAdapter = new BannedDeviceAdapter(new ArrayList<>(), this);
        rvConnected.setAdapter(connectedAdapter);
        rvBanned.setAdapter(bannedAdapter);

        btnStart.setOnClickListener(v -> toggleService());
        btnBanAll.setOnClickListener(v -> confirmBanAll());
        btnRestoreAll.setOnClickListener(v -> restoreAll());
        btnTabConnected.setOnClickListener(v -> showTab(true));
        btnTabBanned.setOnClickListener(v -> showTab(false));
        showTab(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, NetcutService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    // ========================================================================
    // ✅ FIX 3: NOTIFICATION PERMISSION
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
            }
        }
    }

    // ========================================================================
    // ROOT CHECK (ASYNC)
    // ========================================================================

    private void checkRootAccessAsync() {
        ioExecutor.execute(() -> {
            boolean rooted = RootManager.isRooted();
            mainHandler.post(() -> {
                if (!rooted && !isFinishing()) {
                    new AlertDialog.Builder(this)
                            .setTitle("⚠ Root Access Required")
                            .setMessage("This app REQUIRES root to function.\n\n" +
                                    "Please grant root (Magisk/SuperSU), then retry.")
                            .setCancelable(false)
                            .setPositiveButton("Retry Check", (d, w) -> checkRootAccessAsync())
                            .setNegativeButton("Exit App", (d, w) -> finish())
                            .show();
                }
            });
        });
    }

    // ========================================================================
    // BATTERY OPTIMIZATION
    // ========================================================================

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                if (!batteryWarningShownThisSession) {
                    showBatteryOptimizationDialog();
                } else {
                    Toast.makeText(this, "⚠ Battery optimization is still ON.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void showBatteryOptimizationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("🔋 Disable Battery Optimization")
                .setMessage("This app must run continuously to keep devices banned.\n\n" +
                        "Tap 'Allow Now' to exclude this app from battery optimization.")
                .setCancelable(false)
                .setPositiveButton("Allow Now", (d, w) -> {
                    batteryWarningShownThisSession = true;
                    requestIgnoreBatteryOptimizations();
                })
                .setNegativeButton("Not Now", (d, w) -> {
                    batteryWarningShownThisSession = true;
                })
                .show();
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    // ========================================================================
    // OEM HINTS
    // ========================================================================

    private void showOemBatteryHint() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_OEM_HINT_SHOWN, false)) return;

        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String hint = null;
        if (manufacturer.contains("samsung")) {
            hint = "Samsung: Add this app to 'Never sleeping apps' in Battery settings.";
        } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            hint = "Xiaomi: Set battery saver to 'No restrictions' for this app.";
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            hint = "Huawei: Enable 'Manage manually' in App launch settings.";
        } else if (manufacturer.contains("oneplus")) {
            hint = "OnePlus: Set battery optimization to 'Don't optimize'.";
        } else if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            hint = "Oppo/Realme: Allow auto-start and background activity.";
        }

        if (hint != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Device-Specific Setup")
                    .setMessage(hint)
                    .setPositiveButton("Got it", (d, w) ->
                            prefs.edit().putBoolean(KEY_OEM_HINT_SHOWN, true).apply())
                    .setCancelable(false)
                    .show();
        } else {
            prefs.edit().putBoolean(KEY_OEM_HINT_SHOWN, true).apply();
        }
    }

    // ========================================================================
    // ✅ FIX 1: SERVICE CONTROL WITH RAPID TAP PROTECTION
    // ========================================================================

    private void toggleService() {
        if (!bound) return;

        // ✅ Block rapid taps
        if (service.isTransitioning()) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (service.isManualModeActive()) {
            service.manualStop();
            btnStart.setText("Start Service");
        } else {
            startService(new Intent(this, NetcutService.class));
            service.manualStart();
            btnStart.setText("Stop Service");
        }
    }

    private void confirmBanAll() {
        if (!bound) return;
        new AlertDialog.Builder(this)
                .setTitle("Ban All Devices?")
                .setMessage("This will disconnect ALL online devices from the network.\n\nAre you sure?")
                .setPositiveButton("Ban All", (d, w) -> banAll())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void banAll() {
        if (!bound) return;

        // ✅ Add confirmation dialog to prevent accidental network drops
        new AlertDialog.Builder(this)
                .setTitle("Ban All Devices?")
                .setMessage("This will disconnect ALL online devices from the network.\n\nAre you sure?")
                .setPositiveButton("Ban All", (d, w) -> {
                    List<Device> toBan = new ArrayList<>();
                    for (Device dd : service.getConnectedDevices()) {
                        if (dd.isOnline()) toBan.add(dd);
                    }
                    if (!toBan.isEmpty()) {
                        service.banDevices(toBan); // ✅ Uses batch method
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreAll() {
        if (!bound) return;
        service.unbanAllDevices(); // ✅ Uses batch method (clears DB and sends 1 sync command)
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
        if (!bound || isFinishing()) return;

        List<Device> connected = service.getConnectedDevices();
        List<Device> banned = service.getBannedDevices();

        connectedAdapter.updateDevices(connected);
        bannedAdapter.updateDevices(banned);

        int onlineCount = 0;
        for (Device d : connected) { if (d.isOnline()) onlineCount++; }

        tvStats.setText(String.format("Online: %d | Banned: %d", onlineCount, banned.size()));

        if (service.isManualModeActive()) {
            if (!service.isEngineRunning() && service.isWaitingForWifi()) {
                btnStart.setText("Waiting...");
            } else {
                btnStart.setText("Stop Service");
            }
        } else {
            btnStart.setText("Start Service");
        }
    }

    // ========================================================================
    // ADAPTER CALLBACKS
    // ========================================================================

    @Override
    public void onBanClick(Device device, int position) {
        boolean newBannedState = !device.isBanned();
        device.setBanned(newBannedState);
        if (connectedAdapter != null) connectedAdapter.notifyItemChanged(position);

        ioExecutor.execute(() -> {
            if (newBannedState) service.banDevice(device.getMac(), device.getIp());
            else service.unbanDevice(device.getMac());
        });
    }

    @Override
    public void onPingClick(Device device) {
        String ip = device.getIp();
        if (!Device.isValidIpv4(ip)) return;
        Toast.makeText(this, "Pinging " + ip + "...", Toast.LENGTH_SHORT).show();

        ioExecutor.execute(() -> {
            // ✅ FIX: Removed -W 1 (fails on some Android toybox versions)
            // Increased timeout slightly to 4000ms
            String output = RootManager.execute("ping -c 1 " + ip, 4000);

            // ✅ FIX: Check for multiple success indicators and ensure it didn't timeout
            boolean reachable = output != null &&
                    !output.contains("TIMEOUT") &&
                    (output.contains("time=") || output.contains("bytes from") || output.contains("1 received"));

            String msg = reachable ? ip + " is reachable ✔" : ip + " is unreachable ✖";

            mainHandler.post(() -> {
                if (!isFinishing()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        });
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

    @Override
    public void onUnbanClick(Device device) {
        if (bound) {
            ioExecutor.execute(() -> service.unbanDevice(device.getMac()));
            mainHandler.postDelayed(this::refreshUI, 500);
        }
    }

    private void checkArchitectureSupport() {
        BinaryManager bm = new BinaryManager(this);
        String arch = bm.detectArchitecture();
        if (arch == null) {
            new AlertDialog.Builder(this)
                    .setTitle("❌ Unsupported Architecture")
                    .setMessage("This device uses an unsupported CPU architecture.\n\n" +
                            "Detected ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS))
                    .setCancelable(false)
                    .setPositiveButton("Exit", (d, w) -> finish())
                    .show();
        }
    }

    // ========================================================================
    // SERVICE CALLBACKS
    // ========================================================================

    @Override
    public void onDataChanged() {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                refreshUI();
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        });
    }

    @Override
    public void onToastMessage(String msg) {
        runOnUiThread(() -> {
            if (!isFinishing()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }
}