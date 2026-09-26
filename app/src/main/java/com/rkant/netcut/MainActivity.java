package com.rkant.netcut;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
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
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements DeviceAdapter.OnDeviceActionListener,
        BannedDeviceAdapter.OnBannedDeviceActionListener,
        ProtectedDeviceAdapter.OnProtectedDeviceActionListener,
        SavedDeviceAdapter.OnSavedDeviceActionListener,
        NetcutService.ServiceCallback {

    private static final int NOTIFICATION_PERMISSION_CODE = 101;
    private static final String PREFS_NAME = NetcutService.PREFS_NAME;
    private static final String KEY_OEM_HINT_SHOWN = "oem_hint_shown";
    private static final String KEY_BATTERY_OPT_DONT_ASK = "battery_opt_dont_ask";
    private static final String KEY_BATTERY_PROMPT_LAST_TIME = "battery_prompt_last_time";
    private static final long BATTERY_PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    private static boolean batteryWarningShownThisSession = false;

    private NetcutService service;
    private boolean bound = false;

    private int appliedThemeMode;

    private DeviceAdapter connectedAdapter;
    private BannedDeviceAdapter bannedAdapter;
    private ProtectedDeviceAdapter protectedAdapter;
    private SavedDeviceAdapter savedAdapter;

    private RecyclerView rvConnected, rvBanned, rvProtected, rvSaved;

    private TextView tvStats, deviceStats, serviceStatus;
    private EditText etSearch;
    private MaterialButton btnStart;
    private MaterialButton btnBanAll;
    private MaterialButton btnRestoreAll;
    private TabLayout tabLayout;
    private MaterialButton btnSettings;
    private MaterialButton btnLogs;
    private View greenRed;

    private LinearLayout llSelectionActions;
    private MaterialCardView bgRunningNotrunningLayout;
    private MaterialButton btnBanSelected;
    private MaterialButton btnUnbanSelected;
    private MaterialButton btnClearSelection;

    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressLoading;
    private ProgressBar scanProgress;
    private View searchContainer;

    private OnBackPressedCallback selectionBackCallback;
    private OnBackPressedCallback searchBackCallback;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // New-device alerts are coalesced so a burst of discoveries shows a single
    // dialog instead of a stack of overlapping popups (one per device).
    private static final long NEW_DEVICE_ALERT_DEBOUNCE_MS = 600;
    private final List<Device> pendingNewDevices = new ArrayList<>();
    private AlertDialog newDeviceDialog;
    private final Runnable showNewDevicesRunnable = this::showPendingNewDevicesDialog;

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

        appliedThemeMode = ThemeManager.getSavedMode(this);

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
        rvProtected = findViewById(R.id.rv_protected);
        rvSaved = findViewById(R.id.rv_saved);

        tvStats = findViewById(R.id.tv_stats);
        etSearch = findViewById(R.id.et_search);
        deviceStats = findViewById(R.id.device_stats);
        serviceStatus = findViewById(R.id.service_status);
        greenRed = findViewById(R.id.green_red);
        btnStart = findViewById(R.id.btn_start);
        btnBanAll = findViewById(R.id.btn_ban_all);
        btnRestoreAll = findViewById(R.id.btn_restore_all);
        btnLogs = findViewById(R.id.btn_logs);
        btnSettings = findViewById(R.id.btn_settings);

        llSelectionActions = findViewById(R.id.ll_selection_actions);
        btnBanSelected = findViewById(R.id.btn_ban_selected);
        btnUnbanSelected = findViewById(R.id.btn_unban_selected);
        btnClearSelection = findViewById(R.id.btn_clear_selection);
        bgRunningNotrunningLayout = findViewById(R.id.bg_running_notrunning_layout);

        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressLoading = findViewById(R.id.progress_loading);
        scanProgress = findViewById(R.id.scan_progress);
        searchContainer = findViewById(R.id.search_container);
        tabLayout = findViewById(R.id.tab_layout);

        if (btnLogs != null) {
            btnLogs.setOnClickListener(v -> showSessionLogs());
        }

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

        if (rvProtected != null) {
            rvProtected.setLayoutManager(new LinearLayoutManager(this));
            protectedAdapter = new ProtectedDeviceAdapter(new ArrayList<>(), this);
            rvProtected.setAdapter(protectedAdapter);
        }

        if (rvSaved != null) {
            rvSaved.setLayoutManager(new LinearLayoutManager(this));
            savedAdapter = new SavedDeviceAdapter(new ArrayList<>(), this);
            rvSaved.setAdapter(savedAdapter);
        }

        btnStart.setOnClickListener(v -> toggleService());
        btnBanAll.setOnClickListener(v -> confirmBanAll());
        btnRestoreAll.setOnClickListener(v -> confirmRestoreAll());
        btnSettings.setOnClickListener(v -> showOverflowMenu(v));

        setupTabs();

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
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString();

                if (searchBackCallback != null) {
                    searchBackCallback.setEnabled(q.length() > 0);
                }

                connectedAdapter.setFilter(q);
                bannedAdapter.setFilter(q);

                if (protectedAdapter != null) {
                    protectedAdapter.setFilter(q);
                }

                if (savedAdapter != null) {
                    savedAdapter.setFilter(q);
                }
            }
        });

        searchBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (etSearch != null) {
                    etSearch.setText("");
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                    }
                    etSearch.clearFocus();
                }
                setEnabled(false);
            }
        };

        getOnBackPressedDispatcher().addCallback(this, searchBackCallback);

        selectionBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (connectedAdapter != null && connectedAdapter.isSelectionActive()) {
                    connectedAdapter.clearSelection();
                }
                setEnabled(false);
            }
        };

        getOnBackPressedDispatcher().addCallback(this, selectionBackCallback);

        showTab(0);
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

        if (ThemeManager.getSavedMode(this) != appliedThemeMode) {
            recreate();
            return;
        }

        if (searchBackCallback != null && etSearch != null) {
            searchBackCallback.setEnabled(etSearch.getText() != null && etSearch.getText().length() > 0);
        }

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
        mainHandler.removeCallbacks(showNewDevicesRunnable);
        if (newDeviceDialog != null && newDeviceDialog.isShowing()) {
            newDeviceDialog.dismiss();
        }
        newDeviceDialog = null;
        ioExecutor.shutdownNow();
    }

    private void setupTabs() {
        if (tabLayout == null) return;

        tabLayout.addTab(tabLayout.newTab().setText("Connected"));
        tabLayout.addTab(tabLayout.newTab().setText("Banned"));
        tabLayout.addTab(tabLayout.newTab().setText("Protected"));
        tabLayout.addTab(tabLayout.newTab().setText("Saved"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        TabLayout.Tab first = tabLayout.getTabAt(0);
        if (first != null && !first.isSelected()) {
            first.select();
        }
    }

    private void showTab(int tab) {
        if (rvConnected != null) {
            rvConnected.setVisibility(tab == 0 ? RecyclerView.VISIBLE : RecyclerView.GONE);
        }

        if (rvBanned != null) {
            rvBanned.setVisibility(tab == 1 ? RecyclerView.VISIBLE : RecyclerView.GONE);
        }

        if (rvProtected != null) {
            rvProtected.setVisibility(tab == 2 ? RecyclerView.VISIBLE : RecyclerView.GONE);
        }

        if (rvSaved != null) {
            rvSaved.setVisibility(tab == 3 ? RecyclerView.VISIBLE : RecyclerView.GONE);
        }

        if (tab != 0 && connectedAdapter != null && connectedAdapter.isSelectionActive()) {
            connectedAdapter.clearSelection();
        }
    }

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
            swipeRefresh.setRefreshing(false);
        } else {
            if (showScanToast) {
                Toast.makeText(this, "Service is not running. Start it first.", Toast.LENGTH_SHORT).show();
            }
            swipeRefresh.setRefreshing(false);
        }
    }

    private void showOverflowMenu(View anchor) {
        Context themed = new ContextThemeWrapper(this, R.style.Theme_Netcut_Popup);
        PopupMenu popup = new PopupMenu(themed, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_main, popup.getMenu());

        for (int i = 0; i < popup.getMenu().size(); i++) {
            MenuItem item = popup.getMenu().getItem(i);
            if (item.getIcon() != null) {
                Drawable icon = item.getIcon().mutate();
                icon.setTint(ContextCompat.getColor(themed, R.color.text_secondary));
                item.setIcon(icon);
            }
        }

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
                    new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                            .setTitle("Root Access Required")
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
            new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                    .setTitle("Unsupported Architecture")
                    .setMessage("This device uses an unsupported CPU architecture.\n\n" +
                            "Detected ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS))
                    .setCancelable(false)
                    .setPositiveButton("Exit", (d, w) -> finish())
                    .show();
        }
    }

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

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle("Disable Battery Optimization")
                .setMessage("This app must run continuously to keep devices banned.\n\n" +
                        "Tap 'Allow Now' to exclude this app from battery optimization.")
                .setCancelable(false)
                .setPositiveButton("Allow Now", (d, w) -> requestIgnoreBatteryOptimizations())
                .setNegativeButton("Not Now", (d, w) -> {
                })
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
            } catch (Exception ignored) {
            }
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
            new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
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
            showLoader();
        }
    }

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

    private void setDotColor(View dotView, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dotView.setBackground(dot);
    }

    private void confirmRestoreAll() {
        if (!bound || service == null) return;

        int bannedCount = service.getBannedDevices().size();

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
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

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
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

    private void refreshUI() {
        if (!bound || service == null || isFinishing()) return;

        List<Device> connected = service.getConnectedDevices();
        List<Device> banned = service.getBannedDevices();

        if (connected == null) connected = new ArrayList<>();
        if (banned == null) banned = new ArrayList<>();

        connectedAdapter.updateDevices(connected);
        bannedAdapter.updateDevices(banned);

        int protectedCount = 0;
        int savedCount = 0;

        if (protectedAdapter != null) {
            List<Device> protectedDevices = service.getProtectedDevices();
            if (protectedDevices == null) protectedDevices = new ArrayList<>();
            protectedAdapter.updateDevices(protectedDevices);
            protectedCount = protectedDevices.size();
        }

        if (savedAdapter != null) {
            List<Device> savedDevices = service.getSavedDevices();
            if (savedDevices == null) savedDevices = new ArrayList<>();
            savedAdapter.updateDevices(savedDevices);
            savedCount = savedDevices.size();
        }

        int onlineCount = 0;
        for (Device d : connected) {
            if (d.isOnline()) onlineCount++;
        }

        tvStats.setText(String.format("%d devices online", onlineCount));

        deviceStats.setText(String.format("Banned: %d • Protected: %d • Saved: %d",
                banned.size(), protectedCount, savedCount));

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
            if (searchContainer != null) {
                searchContainer.setVisibility(View.GONE);
            }

            llSelectionActions.setVisibility(View.VISIBLE);

            btnBanSelected.setText("Ban (" + selected + ")");
            btnUnbanSelected.setText("Unban (" + selected + ")");
        } else {
            llSelectionActions.setVisibility(View.GONE);

            if (searchContainer != null) {
                searchContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onBanClick(Device device) {
        if (!bound || service == null || device == null) return;

        if (device.isBanned()) {
            unbanDeviceDirect(device);
            return;
        }

        if (device.isProtected()) {
            Toast.makeText(this, "Cannot ban protected device", Toast.LENGTH_SHORT).show();
            return;
        }

        banDeviceDirect(device);
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
        confirmUnbanDevice(device);
    }

    @Override
    public void onBannedDeviceClick(Device device) {
        showBannedDeviceBottomSheet(device);
    }

    @Override
    public void onRemoveProtectionClick(Device device) {
        confirmRemoveProtection(device);
    }

    @Override
    public void onProtectedDeviceClick(Device device) {
        showProtectedDeviceBottomSheet(device);
    }

    @Override
    public void onRemoveSavedClick(Device device) {
        confirmRemoveSaved(device);
    }

    @Override
    public void onSavedDeviceClick(Device device) {
        showSavedDeviceBottomSheet(device);
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
        if (device == null) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            // Coalesce a burst of discoveries: collect the devices and show a
            // single dialog shortly after the last one arrives, so 10 new
            // devices produce 1 popup instead of 10 stacked ones.
            String mac = device.getMac();
            boolean alreadyPending = false;
            for (Device pending : pendingNewDevices) {
                if (pending.getMac() != null && pending.getMac().equalsIgnoreCase(mac)) {
                    alreadyPending = true;
                    break;
                }
            }
            if (!alreadyPending) pendingNewDevices.add(device);

            mainHandler.removeCallbacks(showNewDevicesRunnable);
            mainHandler.postDelayed(showNewDevicesRunnable, NEW_DEVICE_ALERT_DEBOUNCE_MS);
        });
    }

    private void showPendingNewDevicesDialog() {
        if (isFinishing() || isDestroyed() || pendingNewDevices.isEmpty()) return;

        // Replace any alert still on screen so popups never stack.
        if (newDeviceDialog != null && newDeviceDialog.isShowing()) {
            newDeviceDialog.dismiss();
        }

        final List<Device> devices = new ArrayList<>(pendingNewDevices);
        pendingNewDevices.clear();

        if (devices.size() == 1) {
            Device device = devices.get(0);
            newDeviceDialog = new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                    .setTitle("New Device Detected")
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
            return;
        }

        StringBuilder message = new StringBuilder(devices.size() + " new devices joined the network.\n");
        for (Device device : devices) {
            message.append("\n• ")
                    .append(safeText(device.getIp()))
                    .append("  (")
                    .append(safeText(device.getMac()))
                    .append(")");
        }

        newDeviceDialog = new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle(devices.size() + " New Devices Detected")
                .setMessage(message.toString())
                .setPositiveButton("Ban all", (d, w) -> {
                    if (bound && service != null) {
                        for (Device device : devices) {
                            service.banDevice(device.getMac(), device.getIp());
                        }
                    }
                })
                .setNegativeButton("Ignore", null)
                .show();
    }

    private String safeText(String value) {
        return value == null || value.trim().isEmpty() ? "N/A" : value;
    }

    private void banDeviceDirect(Device device) {
        if (!bound || service == null || device == null) return;

        ioExecutor.execute(() -> {
            service.banDevice(device.getMac(), device.getIp());
            mainHandler.postDelayed(() -> {
                if (!isFinishing()) refreshUI();
            }, 300);
        });
    }

    private void unbanDeviceDirect(Device device) {
        if (!bound || service == null || device == null) return;

        ioExecutor.execute(() -> {
            service.unbanDevice(device.getMac());
            mainHandler.postDelayed(() -> {
                if (!isFinishing()) refreshUI();
            }, 300);
        });
    }

    private void protectDeviceDirect(Device device) {
        if (!bound || service == null || device == null) return;

        ioExecutor.execute(() -> {
            service.setProtected(device.getMac(), true);
            mainHandler.postDelayed(() -> {
                if (!isFinishing()) refreshUI();
            }, 300);
        });
    }

    private void confirmUnbanDevice(Device device) {
        if (!bound || service == null || device == null) return;

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle("Unban Device?")
                .setMessage("IP: " + safeText(device.getIp()) +
                        "\nMAC: " + safeText(device.getMac()) +
                        "\n\nThis will restore network access for this device.")
                .setPositiveButton("Unban", (d, w) -> {
                    ioExecutor.execute(() -> {
                        service.unbanDevice(device.getMac());
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing()) {
                                refreshUI();
                                Toast.makeText(this, "Device unbanned", Toast.LENGTH_SHORT).show();
                            }
                        }, 300);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemoveProtection(Device device) {
        if (!bound || service == null || device == null) return;

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle("Remove Protection?")
                .setMessage("IP: " + safeText(device.getIp()) +
                        "\nMAC: " + safeText(device.getMac()) +
                        "\n\nThis device will no longer be protected.")
                .setPositiveButton("Remove", (d, w) -> {
                    ioExecutor.execute(() -> {
                        service.setProtected(device.getMac(), false);
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing()) {
                                refreshUI();
                                Toast.makeText(this, "Protection removed", Toast.LENGTH_SHORT).show();
                            }
                        }, 300);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemoveSaved(Device device) {
        if (!bound || service == null || device == null) return;

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle("Remove from Saved?")
                .setMessage("IP: " + safeText(device.getIp()) +
                        "\nMAC: " + safeText(device.getMac()) +
                        "\n\nThis will remove the device from the Saved tab only.\n" +
                        "Ban and protection state will remain unchanged.")
                .setPositiveButton("Remove", (d, w) -> {
                    ioExecutor.execute(() -> {
                        boolean removed = service.removeSavedDevice(device.getMac());
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing()) {
                                refreshUI();
                                Toast.makeText(this,
                                        removed ? "Removed from saved" : "Device is not saved",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }, 300);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmUnprotectAndBan(Device device) {
        if (!bound || service == null || device == null) return;

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle("Unprotect & Ban?")
                .setMessage("IP: " + safeText(device.getIp()) +
                        "\nMAC: " + safeText(device.getMac()) +
                        "\n\nThis will remove protection and ban the device.")
                .setPositiveButton("Ban", (d, w) -> {
                    ioExecutor.execute(() -> {
                        service.unprotectAndBanDevice(device.getMac(), device.getIp());
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing()) {
                                refreshUI();
                                Toast.makeText(this, "Protection removed and device banned", Toast.LENGTH_SHORT).show();
                            }
                        }, 300);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmUnbanAndProtect(Device device) {
        if (!bound || service == null || device == null) return;

        new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog)
                .setTitle("Unban & Protect?")
                .setMessage("IP: " + safeText(device.getIp()) +
                        "\nMAC: " + safeText(device.getMac()) +
                        "\n\nThis will unban the device and protect it.")
                .setPositiveButton("Protect", (d, w) -> {
                    ioExecutor.execute(() -> {
                        service.setProtected(device.getMac(), true);
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing()) {
                                refreshUI();
                                Toast.makeText(this, "Device protected", Toast.LENGTH_SHORT).show();
                            }
                        }, 300);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateSaveButton(MaterialButton button, Device device) {
        if (button == null || device == null) return;

        if (device.isSaved()) {
            button.setText("Saved ✓");
        } else {
            button.setText("Save Device");
        }
    }

    private void setupSaveButton(MaterialButton button, Device device) {
        if (button == null || device == null) return;

        updateSaveButton(button, device);
        button.setOnClickListener(v -> saveDeviceIfNotSaved(device, button));
    }

    private void saveDeviceIfNotSaved(Device device, MaterialButton button) {
        if (!bound || service == null || device == null) return;

        String mac = Device.normalizeMac(device.getMac());
        if (mac.isEmpty()) return;

        if (device.isSaved()) {
            Toast.makeText(this, "Device already saved", Toast.LENGTH_SHORT).show();
            updateSaveButton(button, device);
            return;
        }

        ioExecutor.execute(() -> {
            boolean newlySaved = service.saveDevice(device);

            mainHandler.post(() -> {
                if (isFinishing()) return;

                device.setSaved(true);

                if (newlySaved) {
                    Toast.makeText(this, "Device saved", Toast.LENGTH_SHORT).show();
                    refreshUI();
                } else {
                    Toast.makeText(this, "Device already saved", Toast.LENGTH_SHORT).show();
                }

                updateSaveButton(button, device);
            });
        });
    }

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
        MaterialButton btnSheetSave = sheet.findViewById(R.id.btn_sheet_save);

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

        if (device.isBanned()) {
            btnSheetBan.setEnabled(true);
            btnSheetBan.setText("Unban Device");
            btnSheetBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.success)));
            btnSheetBan.setTextColor(getColor(R.color.on_success));
            btnSheetBan.setOnClickListener(v -> {
                dialog.dismiss();
                confirmUnbanDevice(device);
            });
        } else if (device.isProtected()) {
            btnSheetBan.setEnabled(false);
            btnSheetBan.setText("Protected");
            btnSheetBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_variant)));
            btnSheetBan.setTextColor(getColor(R.color.text_tertiary));
            btnSheetBan.setOnClickListener(null);
        } else {
            btnSheetBan.setEnabled(true);
            btnSheetBan.setText("Ban Device");
            btnSheetBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.error)));
            btnSheetBan.setTextColor(getColor(R.color.on_error));
            btnSheetBan.setOnClickListener(v -> {
                dialog.dismiss();
                banDeviceDirect(device);
            });
        }

        if (device.isProtected()) {
            btnSheetProtect.setText("Remove Protection");
            btnSheetProtect.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_variant)));
            btnSheetProtect.setTextColor(getColor(R.color.text_primary));
            btnSheetProtect.setOnClickListener(v -> {
                dialog.dismiss();
                confirmRemoveProtection(device);
            });
        } else if (device.isBanned()) {
            btnSheetProtect.setText("Unban & Protect");
            btnSheetProtect.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary)));
            btnSheetProtect.setTextColor(getColor(R.color.on_primary));
            btnSheetProtect.setOnClickListener(v -> {
                dialog.dismiss();
                confirmUnbanAndProtect(device);
            });
        } else {
            btnSheetProtect.setText("Protect Device");
            btnSheetProtect.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary)));
            btnSheetProtect.setTextColor(getColor(R.color.on_primary));
            btnSheetProtect.setOnClickListener(v -> {
                dialog.dismiss();
                protectDeviceDirect(device);
            });
        }

        setupSaveButton(btnSheetSave, device);

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

    private void showBannedDeviceBottomSheet(Device device) {
        if (isFinishing()) return;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_banned_device, null);
        dialog.setContentView(sheet);

        TextView tvName = sheet.findViewById(R.id.tv_banned_sheet_name);
        TextView tvIp = sheet.findViewById(R.id.tv_banned_sheet_ip);
        TextView tvMac = sheet.findViewById(R.id.tv_banned_sheet_mac);
        TextView tvStatus = sheet.findViewById(R.id.tv_banned_sheet_status);
        TextView tvFirstSeen = sheet.findViewById(R.id.tv_banned_sheet_first_seen);
        TextView tvLastSeen = sheet.findViewById(R.id.tv_banned_sheet_last_seen);

        MaterialButton btnUnban = sheet.findViewById(R.id.btn_banned_sheet_unban);
        MaterialButton btnProtect = sheet.findViewById(R.id.btn_banned_sheet_protect);
        MaterialButton btnRename = sheet.findViewById(R.id.btn_banned_sheet_rename);
        MaterialButton btnClose = sheet.findViewById(R.id.btn_banned_sheet_close);
        MaterialButton btnSave = sheet.findViewById(R.id.btn_banned_sheet_save);

        View dotStatus = sheet.findViewById(R.id.dot_banned_sheet_status);
        MaterialCardView cardStatus = sheet.findViewById(R.id.card_banned_sheet_status);

        tvName.setText(device.getName());

        tvIp.setText(device.getIp() != null && !device.getIp().trim().isEmpty()
                ? device.getIp()
                : "N/A");

        tvMac.setText(device.getMac() != null && !device.getMac().trim().isEmpty()
                ? device.getMac()
                : "N/A");

        tvStatus.setText(device.isProtected() ? "Banned • Protected" : "Banned");
        tvStatus.setTextColor(getColor(R.color.error));

        setDotColor(dotStatus, getColor(R.color.error));

        cardStatus.setCardBackgroundColor(getColor(R.color.error_container));
        cardStatus.setStrokeColor(getColor(R.color.error));

        tvFirstSeen.setText(Device.formatLastSeen(device.getFirstSeen()));
        tvLastSeen.setText(Device.formatLastSeen(device.getLastSeen()));

        btnUnban.setOnClickListener(v -> {
            dialog.dismiss();
            confirmUnbanDevice(device);
        });

        btnProtect.setText(device.isProtected() ? "Unban & Keep Protected" : "Unban & Protect");
        btnProtect.setOnClickListener(v -> {
            dialog.dismiss();
            confirmUnbanAndProtect(device);
        });

        setupSaveButton(btnSave, device);

        btnRename.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(device);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showProtectedDeviceBottomSheet(Device device) {
        if (isFinishing()) return;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_protected_device, null);
        dialog.setContentView(sheet);

        TextView tvName = sheet.findViewById(R.id.tv_protected_sheet_name);
        TextView tvIp = sheet.findViewById(R.id.tv_protected_sheet_ip);
        TextView tvMac = sheet.findViewById(R.id.tv_protected_sheet_mac);
        TextView tvStatus = sheet.findViewById(R.id.tv_protected_sheet_status);
        TextView tvFirstSeen = sheet.findViewById(R.id.tv_protected_sheet_first_seen);
        TextView tvLastSeen = sheet.findViewById(R.id.tv_protected_sheet_last_seen);

        MaterialButton btnRemove = sheet.findViewById(R.id.btn_protected_sheet_remove);
        MaterialButton btnBan = sheet.findViewById(R.id.btn_protected_sheet_ban);
        MaterialButton btnRename = sheet.findViewById(R.id.btn_protected_sheet_rename);
        MaterialButton btnClose = sheet.findViewById(R.id.btn_protected_sheet_close);
        MaterialButton btnSave = sheet.findViewById(R.id.btn_protected_sheet_save);

        View dotStatus = sheet.findViewById(R.id.dot_protected_sheet_status);
        MaterialCardView cardStatus = sheet.findViewById(R.id.card_protected_sheet_status);

        tvName.setText(device.getName());

        tvIp.setText(device.getIp() != null && !device.getIp().trim().isEmpty()
                ? device.getIp()
                : "N/A");

        tvMac.setText(device.getMac() != null && !device.getMac().trim().isEmpty()
                ? device.getMac()
                : "N/A");

        tvStatus.setText(device.isOnline() ? "Protected • Online" : "Protected");
        tvStatus.setTextColor(getColor(R.color.success));

        setDotColor(dotStatus, getColor(R.color.success));

        cardStatus.setCardBackgroundColor(getColor(R.color.success_container));
        cardStatus.setStrokeColor(getColor(R.color.success));

        tvFirstSeen.setText(Device.formatLastSeen(device.getFirstSeen()));
        tvLastSeen.setText(Device.formatLastSeen(device.getLastSeen()));

        btnRemove.setOnClickListener(v -> {
            dialog.dismiss();
            confirmRemoveProtection(device);
        });

        if (device.isBanned()) {
            btnBan.setText("Unban Device");
            btnBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.success)));
            btnBan.setTextColor(getColor(R.color.on_success));
            btnBan.setOnClickListener(v -> {
                dialog.dismiss();
                confirmUnbanDevice(device);
            });
        } else {
            btnBan.setText("Unprotect & Ban");
            btnBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.error)));
            btnBan.setTextColor(getColor(R.color.on_error));
            btnBan.setOnClickListener(v -> {
                dialog.dismiss();
                confirmUnprotectAndBan(device);
            });
        }

        setupSaveButton(btnSave, device);

        btnRename.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(device);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showSavedDeviceBottomSheet(Device device) {
        if (isFinishing()) return;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_saved_device, null);
        dialog.setContentView(sheet);

        TextView tvName = sheet.findViewById(R.id.tv_saved_sheet_name);
        TextView tvIp = sheet.findViewById(R.id.tv_saved_sheet_ip);
        TextView tvMac = sheet.findViewById(R.id.tv_saved_sheet_mac);
        TextView tvStatus = sheet.findViewById(R.id.tv_saved_sheet_status);
        TextView tvFirstSeen = sheet.findViewById(R.id.tv_saved_sheet_first_seen);
        TextView tvLastSeen = sheet.findViewById(R.id.tv_saved_sheet_last_seen);

        MaterialButton btnRemoveSaved = sheet.findViewById(R.id.btn_saved_sheet_remove_saved);
        MaterialButton btnBan = sheet.findViewById(R.id.btn_saved_sheet_ban);
        MaterialButton btnProtect = sheet.findViewById(R.id.btn_saved_sheet_protect);
        MaterialButton btnPing = sheet.findViewById(R.id.btn_saved_sheet_ping);
        MaterialButton btnRename = sheet.findViewById(R.id.btn_saved_sheet_rename);
        MaterialButton btnClose = sheet.findViewById(R.id.btn_saved_sheet_close);

        View dotStatus = sheet.findViewById(R.id.dot_saved_sheet_status);
        MaterialCardView cardStatus = sheet.findViewById(R.id.card_saved_sheet_status);

        tvName.setText(device.getName());

        tvIp.setText(device.getIp() != null && !device.getIp().trim().isEmpty()
                ? device.getIp()
                : "N/A");

        tvMac.setText(device.getMac() != null && !device.getMac().trim().isEmpty()
                ? device.getMac()
                : "N/A");

        tvStatus.setText(device.isOnline() ? "Saved • Online" : "Saved");
        tvStatus.setTextColor(getColor(R.color.warning));

        setDotColor(dotStatus, getColor(R.color.warning));

        cardStatus.setCardBackgroundColor(getColor(R.color.surface_variant));
        cardStatus.setStrokeColor(getColor(R.color.warning));

        tvFirstSeen.setText(Device.formatLastSeen(device.getFirstSeen()));
        tvLastSeen.setText(Device.formatLastSeen(device.getLastSeen()));

        btnRemoveSaved.setOnClickListener(v -> {
            dialog.dismiss();
            confirmRemoveSaved(device);
        });

        if (device.isBanned()) {
            btnBan.setText("Unban Device");
            btnBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.success)));
            btnBan.setTextColor(getColor(R.color.on_success));
            btnBan.setOnClickListener(v -> {
                dialog.dismiss();
                confirmUnbanDevice(device);
            });
        } else if (device.isProtected()) {
            btnBan.setText("Unprotect & Ban");
            btnBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.error)));
            btnBan.setTextColor(getColor(R.color.on_error));
            btnBan.setOnClickListener(v -> {
                dialog.dismiss();
                confirmUnprotectAndBan(device);
            });
        } else {
            btnBan.setText("Ban Device");
            btnBan.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.error)));
            btnBan.setTextColor(getColor(R.color.on_error));
            btnBan.setOnClickListener(v -> {
                dialog.dismiss();
                banDeviceDirect(device);
            });
        }

        if (device.isProtected()) {
            btnProtect.setText("Remove Protection");
            btnProtect.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_variant)));
            btnProtect.setTextColor(getColor(R.color.text_primary));
            btnProtect.setOnClickListener(v -> {
                dialog.dismiss();
                confirmRemoveProtection(device);
            });
        } else if (device.isBanned()) {
            btnProtect.setText("Unban & Protect");
            btnProtect.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary)));
            btnProtect.setTextColor(getColor(R.color.on_primary));
            btnProtect.setOnClickListener(v -> {
                dialog.dismiss();
                confirmUnbanAndProtect(device);
            });
        } else {
            btnProtect.setText("Protect Device");
            btnProtect.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary)));
            btnProtect.setTextColor(getColor(R.color.on_primary));
            btnProtect.setOnClickListener(v -> {
                dialog.dismiss();
                protectDeviceDirect(device);
            });
        }

        boolean canPing = Device.isValidIpv4(device.getIp());
        btnPing.setEnabled(canPing);

        btnPing.setOnClickListener(v -> {
            dialog.dismiss();
            onPingClick(device);
        });

        btnRename.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(device);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showRenameDialog(Device device) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this, R.style.Theme_Netcut_Dialog);
        builder.setTitle("Edit Device Name");

        final EditText input = new EditText(this);
        input.setText(device.getName().equals("Unnamed") ? "" : device.getName());
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_tertiary));

        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            if (!bound || service == null) return;

            String newName = input.getText().toString();

            ioExecutor.execute(() -> {
                service.updateDeviceName(device.getMac(), device.getIp(), newName);
                mainHandler.postDelayed(() -> {
                    if (!isFinishing()) refreshUI();
                }, 200);
            });
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void showSessionLogs() {
        startActivity(new Intent(this, LogsActivity.class));
    }
}