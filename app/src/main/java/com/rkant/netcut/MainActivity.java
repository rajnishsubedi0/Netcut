package com.rkant.netcut;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements DeviceAdapter.OnDeviceActionListener,
        BannedDeviceAdapter.OnUnbanListener,
        NetcutService.ServiceCallback {

    private static final int NOTIFICATION_PERMISSION_CODE = 101;

    private static final String PREFS_NAME = NetcutService.PREFS_NAME;
    private static final String KEY_OEM_HINT_SHOWN = "oem_hint_shown";
    private static final String KEY_BATTERY_OPT_DONT_ASK = "battery_opt_dont_ask";
    private static final String KEY_BATTERY_PROMPT_LAST_TIME = "battery_prompt_last_time";
    private static final long BATTERY_PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    private static boolean batteryWarningShownThisSession = false;
    private OnBackPressedCallback selectionBackCallback;

    private NetcutService service;
    private boolean bound = false;

    private DeviceAdapter connectedAdapter;
    private BannedDeviceAdapter bannedAdapter;

    private RecyclerView rvConnected, rvBanned;
    private TextView tvStats, deviceStats, serviceStatus;
    private EditText etSearch;
    private MaterialButton btnStart;
    private MaterialButton btnBanAll;
    private MaterialButton btnRestoreAll;
    private MaterialButton btnTabConnected;
    private MaterialButton btnTabBanned;
    private MaterialButton btnSettings;
    private View greenRed;
    private LinearLayout llSelectionActions;
    private MaterialCardView bgRunningNotrunningLayout;
    private MaterialButton btnBanSelected;
    private MaterialButton btnUnbanSelected;
    private MaterialButton btnClearSelection;
    private SwipeRefreshLayout swipeRefresh;

    // NEW: header loader + scan progress line
    private ProgressBar progressLoading;
    private ProgressBar scanProgress;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        checkRootAccessAsync();
        checkNotificationPermission();
        checkArchitectureSupport();
        showOemBatteryHint();

        rvConnected = findViewById(R.id.rv_connected);
        rvBanned = findViewById(R.id.rv_banned);
        tvStats = findViewById(R.id.tv_stats);
        etSearch = findViewById(R.id.et_search);
        deviceStats = findViewById(R.id.device_stats);
        serviceStatus = findViewById(R.id.service_status);
        greenRed = findViewById(R.id.green_red);
        btnStart = findViewById(R.id.btn_start);
        btnBanAll = findViewById(R.id.btn_ban_all);
        btnRestoreAll = findViewById(R.id.btn_restore_all);
        btnTabConnected = findViewById(R.id.btn_tab_connected);
        btnTabBanned = findViewById(R.id.btn_tab_banned);
        btnSettings = findViewById(R.id.btn_settings);
        llSelectionActions = findViewById(R.id.ll_selection_actions);
        btnBanSelected = findViewById(R.id.btn_ban_selected);
        btnUnbanSelected = findViewById(R.id.btn_unban_selected);
        btnClearSelection = findViewById(R.id.btn_clear_selection);
        bgRunningNotrunningLayout = findViewById(R.id.bg_running_notrunning_layout);
        swipeRefresh = findViewById(R.id.swipe_refresh);

        // NEW: loader widgets
        progressLoading = findViewById(R.id.progress_loading);
        scanProgress = findViewById(R.id.scan_progress);

        swipeRefresh.setDistanceToTriggerSync(550);
        swipeRefresh.setColorSchemeColors(getColor(R.color.primary));
        swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.surface));
        swipeRefresh.setOnRefreshListener(() -> triggerScan(false));

        rvConnected.setLayoutManager(new LinearLayoutManager(this));
        rvBanned.setLayoutManager(new LinearLayoutManager(this));

        connectedAdapter = new DeviceAdapter(new ArrayList<>(), this);
        bannedAdapter = new BannedDeviceAdapter(new ArrayList<>(), this);
        rvConnected.setAdapter(connectedAdapter);
        rvBanned.setAdapter(bannedAdapter);

        selectionBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (connectedAdapter != null && connectedAdapter.isSelectionActive()) {
                    connectedAdapter.clearSelection();
                    refreshUI();
                }
                setEnabled(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, selectionBackCallback);

        btnStart.setOnClickListener(v -> toggleService());
        btnBanAll.setOnClickListener(v -> confirmBanAll());
        btnRestoreAll.setOnClickListener(v -> confirmRestoreAll());
        btnTabConnected.setOnClickListener(v -> showTab(true));
        btnTabBanned.setOnClickListener(v -> showTab(false));

        // NEW: three-dot overflow menu instead of direct Settings launch
        btnSettings.setOnClickListener(v -> showOverflowMenu(v));

        btnBanSelected.setOnClickListener(v -> {
            if (!bound || service == null) return;
            List<Device> selected = connectedAdapter.getSelectedDevices();
            if (selected.isEmpty()) {
                Toast.makeText(this, "No devices selected", Toast.LENGTH_SHORT).show();
                return;
            }
            service.banDevices(selected);
            connectedAdapter.clearSelection();
            refreshUI();
        });

        btnUnbanSelected.setOnClickListener(v -> {
            if (!bound || service == null) return;
            List<Device> selected = connectedAdapter.getSelectedDevices();
            if (selected.isEmpty()) {
                Toast.makeText(this, "No devices selected", Toast.LENGTH_SHORT).show();
                return;
            }
            service.unbanDevices(selected);
            connectedAdapter.clearSelection();
            refreshUI();
        });

        btnClearSelection.setOnClickListener(v -> {
            connectedAdapter.clearSelection();
            refreshUI();
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString();
                connectedAdapter.setFilter(q);
                bannedAdapter.setFilter(q);
            }
        });

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
        mainHandler.postDelayed(() -> {
            if (!isFinishing()) checkBatteryOptimization();
        }, 400);
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

    // ──────────────────────────────────────────────
    //  NEW: SCAN TRIGGER + OVERFLOW MENU + LOADER
    // ──────────────────────────────────────────────
    private void triggerScan(boolean showScanToast) {
        if (bound && service != null && service.isEngineRunning()) {
            if (showScanToast) {
                Toast.makeText(this, "Scanning network...", Toast.LENGTH_SHORT).show();
            }

            if (scanProgress != null) {
                scanProgress.setVisibility(View.VISIBLE);
            }

            service.forceScan();
        } else if (bound && service != null && service.isManualModeActive()) {
            if (showScanToast) {
                Toast.makeText(this, "Waiting for WiFi...", Toast.LENGTH_SHORT).show();
            }

            if (scanProgress != null) {
                scanProgress.setVisibility(View.GONE);
            }

            swipeRefresh.setRefreshing(false);
        } else {
            if (showScanToast) {
                Toast.makeText(this, "Service is not running. Start it first.", Toast.LENGTH_SHORT).show();
            }

            if (scanProgress != null) {
                scanProgress.setVisibility(View.GONE);
            }

            swipeRefresh.setRefreshing(false);
        }
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_main, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            } else if (id == R.id.menu_scan) {
                triggerScan(true);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showLoader() {
        if (progressLoading != null) progressLoading.setVisibility(View.VISIBLE);
    }

    private void hideLoader() {
        if (progressLoading != null) progressLoading.setVisibility(View.GONE);
    }

    // ──────────────────────────────────────────────
    //  PERMISSIONS & CHECKS
    // ──────────────────────────────────────────────
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

    // ──────────────────────────────────────────────
    //  BATTERY OPTIMIZATION
    // ──────────────────────────────────────────────
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (isFinishing()) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_BATTERY_OPT_DONT_ASK, false)) return;
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (batteryWarningShownThisSession) return;

        long lastPromptTime = prefs.getLong(KEY_BATTERY_PROMPT_LAST_TIME, 0L);
        long now = System.currentTimeMillis();
        if (lastPromptTime != 0 && now - lastPromptTime < BATTERY_PROMPT_COOLDOWN_MS) return;

        showBatteryOptimizationDialog();
    }

    private void showBatteryOptimizationDialog() {
        if (isFinishing()) return;
        batteryWarningShownThisSession = true;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putLong(KEY_BATTERY_PROMPT_LAST_TIME, System.currentTimeMillis()).apply();

        new AlertDialog.Builder(this)
                .setTitle("🔋 Disable Battery Optimization")
                .setMessage("This app must run continuously to keep devices banned.\n\n" +
                        "Tap 'Allow Now' to exclude this app from battery optimization.")
                .setCancelable(false)
                .setPositiveButton("Allow Now", (d, w) -> requestIgnoreBatteryOptimizations())
                .setNegativeButton("Not Now", (d, w) -> {})
                .setNeutralButton("Don't ask again", (d, w) ->
                        prefs.edit().putBoolean(KEY_BATTERY_OPT_DONT_ASK, true).apply())
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
            String finalHint = hint;
            new AlertDialog.Builder(this)
                    .setTitle("Device-Specific Setup")
                    .setMessage(finalHint)
                    .setPositiveButton("Got it", (d, w) ->
                            prefs.edit().putBoolean(KEY_OEM_HINT_SHOWN, true).apply())
                    .setCancelable(false)
                    .show();
        } else {
            prefs.edit().putBoolean(KEY_OEM_HINT_SHOWN, true).apply();
        }
    }

    // ──────────────────────────────────────────────
    //  SERVICE TOGGLE  (play/pause icon button)
    // ──────────────────────────────────────────────
    private void toggleService() {
        if (!bound || service == null) return;

        if (service.isTransitioning()) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (service.isManualModeActive()) {
            service.manualStop();
            applyStoppedUI();
        } else {
            startService(new Intent(this, NetcutService.class));
            service.manualStart();
            applyRunningUI();
            showLoader(); // spinner until engine reports ready
        }
    }

    /** Running look: round PAUSE button (tap to stop). */
    private void applyRunningUI() {
        btnStart.setIconResource(R.drawable.ic_pause);
        btnStart.setIconTintResource(R.color.btn_stop_text);
        btnStart.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.btn_stop_bg)));
        btnStart.setContentDescription("Stop service");
        hideLoader();

        serviceStatus.setText("Service is running");
        serviceStatus.setTextColor(getColor(R.color.status_running));
        bgRunningNotrunningLayout.setCardBackgroundColor(getColor(R.color.status_running_bg));
        bgRunningNotrunningLayout.setStrokeColor(getColor(R.color.success));
        setDotColor(greenRed, getColor(R.color.status_running));
    }

    /** Stopped look: round PLAY button (tap to start). */
    private void applyStoppedUI() {
        btnStart.setIconResource(R.drawable.ic_play);
        btnStart.setIconTintResource(R.color.btn_start_text);
        btnStart.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.btn_start_bg)));
        btnStart.setContentDescription("Start service");
        hideLoader();

        serviceStatus.setText("Service is not running");
        serviceStatus.setTextColor(getColor(R.color.text_secondary));
        bgRunningNotrunningLayout.setCardBackgroundColor(getColor(R.color.status_stopped_bg));
        bgRunningNotrunningLayout.setStrokeColor(getColor(R.color.border));
        setDotColor(greenRed, getColor(R.color.status_stopped));
    }

    /** Draws a perfect circle on a plain View — no drawable file needed. */
    private void setDotColor(View dotView, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dotView.setBackground(dot);
    }

    // ──────────────────────────────────────────────
    //  BAN ALL / RESTORE ALL
    // ──────────────────────────────────────────────
    private void confirmRestoreAll() {
        if (!bound || service == null) return;
        int bannedCount = service.getBannedDevices().size();
        new AlertDialog.Builder(this)
                .setTitle("Restore All Devices?")
                .setMessage("This will unban " + bannedCount +
                        " banned device(s) and restore network access.\n\nAre you sure?")
                .setPositiveButton("Restore All", (d, w) -> restoreAll())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreAll() {
        if (!bound || service == null) return;
        service.unbanAllDevices();
        Toast.makeText(this, "Restoring all devices...", Toast.LENGTH_SHORT).show();
    }

    private void confirmBanAll() {
        if (!bound || service == null) return;
        int online = 0;
        int protect = 0;
        for (Device d : service.getConnectedDevices()) {
            if (d.isOnline()) {
                if (d.isProtected()) protect++;
                else online++;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Ban All Devices?")
                .setMessage("Ban " + online + " online device(s).\n" +
                        protect + " protected device(s) will be skipped.\n\nAre you sure?")
                .setPositiveButton("Ban All", (d, w) -> performBanAll())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performBanAll() {
        if (!bound || service == null) return;
        List<Device> toBan = new ArrayList<>();
        for (Device device : service.getConnectedDevices()) {
            if (device.isOnline() && !device.isProtected()) {
                toBan.add(device);
            }
        }
        if (toBan.isEmpty()) {
            Toast.makeText(this, "No online unprotected devices to ban", Toast.LENGTH_SHORT).show();
            return;
        }
        service.banDevices(toBan);
        Toast.makeText(this, "Banning all online devices...", Toast.LENGTH_SHORT).show();
    }

    // ──────────────────────────────────────────────
    //  TABS  (dynamic, no hardcoded hex)
    // ──────────────────────────────────────────────
    private void showTab(boolean connected) {
        rvConnected.setVisibility(connected ? RecyclerView.VISIBLE : RecyclerView.GONE);
        rvBanned.setVisibility(connected ? RecyclerView.GONE : RecyclerView.VISIBLE);
        if (connected) {
            setTabStyle(btnTabConnected, btnTabBanned);
        } else {
            setTabStyle(btnTabBanned, btnTabConnected);
        }
    }

    private void setTabStyle(MaterialButton selected, MaterialButton unselected) {
        selected.setBackgroundTintList(
                ColorStateList.valueOf(getColor(R.color.tab_selected_bg)));
        selected.setTextColor(getColor(R.color.tab_selected_text));
        unselected.setBackgroundTintList(
                ColorStateList.valueOf(getColor(R.color.tab_unselected_bg)));
        unselected.setTextColor(getColor(R.color.tab_unselected_text));
    }

    // ──────────────────────────────────────────────
    //  REFRESH UI
    // ──────────────────────────────────────────────
    private void refreshUI() {
        if (!bound || service == null || isFinishing()) return;

        List<Device> connected = service.getConnectedDevices();
        List<Device> banned = service.getBannedDevices();

        connectedAdapter.updateDevices(connected);
        bannedAdapter.updateDevices(banned);

        int onlineCount = 0;
        int protectedCount = 0;
        for (Device d : connected) {
            if (d.isOnline()) onlineCount++;
            if (d.isProtected()) protectedCount++;
        }

        tvStats.setText(String.format("%d devices online", onlineCount));
        deviceStats.setText(String.format("Banned: %d • Protected: %d", banned.size(), protectedCount));

        if (service.isManualModeActive()) {
            if (!service.isEngineRunning() && service.isWaitingForWifi()) {
                btnStart.setIconResource(R.drawable.ic_play);
                btnStart.setIconTintResource(R.color.on_primary);
                btnStart.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.warning)));
                btnStart.setContentDescription("Waiting for WiFi");
                showLoader();
            } else {
                applyRunningUI();
            }
        } else {
            applyStoppedUI();
        }

        if (service.isTransitioning()) showLoader();

        updateSelectionBar();
    }

    private void updateSelectionBar() {
        if (connectedAdapter == null || llSelectionActions == null) return;

        int selected = connectedAdapter.getSelectedItemCount();

        if (selectionBackCallback != null) {
            selectionBackCallback.setEnabled(selected > 0);
        }

        if (selected > 0) {
            llSelectionActions.setVisibility(View.VISIBLE);
            btnBanSelected.setText("Ban (" + selected + ")");
            btnUnbanSelected.setText("Unban (" + selected + ")");
        } else {
            llSelectionActions.setVisibility(View.GONE);
        }
    }

    // ──────────────────────────────────────────────
    //  ADAPTER CALLBACKS
    // ──────────────────────────────────────────────
    @Override
    public void onBanClick(Device device) {
        if (!bound || service == null) return;
        if (device.isProtected()) {
            Toast.makeText(this, "Cannot ban protected device", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean newState = !device.isBanned();
        ioExecutor.execute(() -> {
            if (newState) {
                service.banDevice(device.getMac(), device.getIp());
            } else {
                service.unbanDevice(device.getMac());
            }
            mainHandler.postDelayed(this::refreshUI, 300);
        });
    }

    @Override
    public void onPingClick(Device device) {
        String ip = device.getIp();
        if (!Device.isValidIpv4(ip)) return;
        Toast.makeText(this, "Pinging " + ip + "...", Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            String output = RootManager.execute("ping -c 1 " + ip, 4000);
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
    public void onDetailsClick(Device device) {
        showDeviceBottomSheet(device);
    }

    @Override
    public void onSelectionChanged() {
        updateSelectionBar();
    }

    @Override
    public void onUnbanClick(Device device) {
        if (!bound || service == null) return;
        ioExecutor.execute(() -> {
            service.unbanDevice(device.getMac());
            mainHandler.postDelayed(this::refreshUI, 300);
        });
    }

    @Override
    public void onDataChanged() {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                refreshUI();
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                if (scanProgress != null) scanProgress.setVisibility(View.GONE);
                hideLoader();
            }
        });
    }

    @Override
    public void onToastMessage(String msg) {
        runOnUiThread(() -> {
            if (!isFinishing()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onNewDeviceDetected(Device device) {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            new AlertDialog.Builder(this)
                    .setTitle("🆕 New Device Detected")
                    .setMessage("A new device joined the network.\n\n" +
                            "IP: " + device.getIp() + "\n" +
                            "MAC: " + device.getMac())
                    .setPositiveButton("Ban", (d, w) -> {
                        if (bound && service != null)
                            service.banDevice(device.getMac(), device.getIp());
                    })
                    .setNeutralButton("Protect", (d, w) -> {
                        if (bound && service != null)
                            service.setProtected(device.getMac(), true);
                    })
                    .setNegativeButton("Ignore", null)
                    .show();
        });
    }

    // ──────────────────────────────────────────────
    //  BOTTOM SHEET
    // ──────────────────────────────────────────────
    private void showDeviceBottomSheet(Device device) {
        if (isFinishing()) return;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_device, null);
        dialog.setContentView(sheet);

        TextView tvSheetName = sheet.findViewById(R.id.tv_sheet_name);
        TextView tvSheetIp = sheet.findViewById(R.id.tv_sheet_ip);
        TextView tvSheetMac = sheet.findViewById(R.id.tv_sheet_mac);
        TextView tvSheetStatus = sheet.findViewById(R.id.tv_sheet_status);
        TextView tvSheetFirstSeen = sheet.findViewById(R.id.tv_sheet_first_seen);
        TextView tvSheetLastSeen = sheet.findViewById(R.id.tv_sheet_last_seen);
        MaterialButton btnSheetBan = sheet.findViewById(R.id.btn_sheet_ban);
        MaterialButton btnSheetProtect = sheet.findViewById(R.id.btn_sheet_protect);
        MaterialButton btnSheetPing = sheet.findViewById(R.id.btn_sheet_ping);
        MaterialButton btnSheetRename = sheet.findViewById(R.id.btn_sheet_rename);
        MaterialButton btnSheetClose = sheet.findViewById(R.id.btn_sheet_close);
        View dotSheetStatus = sheet.findViewById(R.id.dot_sheet_status);
        MaterialCardView cardSheetStatus = sheet.findViewById(R.id.card_sheet_status);

        tvSheetName.setText(device.getName());
        tvSheetIp.setText(device.getIp());
        tvSheetMac.setText(device.getMac());

        boolean online = device.isOnline();
        tvSheetStatus.setText(online ? "Online" : "Offline");
        tvSheetStatus.setTextColor(getColor(online ? R.color.success : R.color.error));
        setDotColor(dotSheetStatus, getColor(online ? R.color.success : R.color.error));
        cardSheetStatus.setCardBackgroundColor(getColor(online ? R.color.success_container : R.color.error_container));
        cardSheetStatus.setStrokeColor(getColor(online ? R.color.success : R.color.error));

        tvSheetFirstSeen.setText(Device.formatLastSeen(device.getFirstSeen()));
        tvSheetLastSeen.setText(Device.formatLastSeen(device.getLastSeen()));

        if (device.isProtected()) {
            btnSheetBan.setEnabled(false);
            btnSheetBan.setText("Protected");
            btnSheetBan.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.surface_variant)));
            btnSheetBan.setTextColor(getColor(R.color.text_tertiary));
            btnSheetProtect.setText("Remove Protection");
        } else {
            btnSheetBan.setEnabled(true);
            btnSheetBan.setText(device.isBanned() ? "Unban Device" : "Ban Device");
            btnSheetBan.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(device.isBanned() ? R.color.success : R.color.error)));
            btnSheetBan.setTextColor(getColor(device.isBanned() ? R.color.on_success : R.color.on_error));
            btnSheetProtect.setText("Protect Device");
        }

        btnSheetBan.setOnClickListener(v -> {
            if (!bound || service == null || device.isProtected()) {
                dialog.dismiss();
                return;
            }
            if (device.isBanned()) {
                ioExecutor.execute(() -> service.unbanDevice(device.getMac()));
            } else {
                ioExecutor.execute(() -> service.banDevice(device.getMac(), device.getIp()));
            }
            dialog.dismiss();
            mainHandler.postDelayed(this::refreshUI, 300);
        });

        btnSheetProtect.setOnClickListener(v -> {
            if (!bound || service == null) {
                dialog.dismiss();
                return;
            }
            boolean newProtect = !device.isProtected();
            ioExecutor.execute(() -> service.setProtected(device.getMac(), newProtect));
            dialog.dismiss();
            mainHandler.postDelayed(this::refreshUI, 300);
        });

        btnSheetPing.setOnClickListener(v -> {
            dialog.dismiss();
            onPingClick(device);
        });

        btnSheetRename.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(device);
        });

        btnSheetClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // ──────────────────────────────────────────────
    //  RENAME DIALOG
    // ──────────────────────────────────────────────
    private void showRenameDialog(Device device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Device Name");

        final EditText input = new EditText(this);
        input.setText(device.getName().equals("Unnamed") ? "" : device.getName());
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_tertiary));
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            if (bound && service != null) {
                service.updateDeviceName(device.getMac(), device.getIp(), input.getText().toString());
                refreshUI();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }
}