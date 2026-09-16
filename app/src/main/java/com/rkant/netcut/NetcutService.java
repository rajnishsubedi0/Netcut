package com.rkant.netcut;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NetcutService extends Service {
    private static final String TAG = "NetcutService";

    private volatile boolean userRequestedRunning = false;
    private volatile boolean waitingForWifi = false;
    private volatile boolean pendingStartAttempt = false;

    private final IBinder binder = new LocalBinder();
    private RustBridge bridge;
    private DeviceDbHelper dbHelper;
    private ScheduledExecutorService scheduler;
    private ServiceCallback callback;
    private BinaryManager binaryManager;
    private String binaryPath = null;

    private Map<String, Device> knownDevices = new ConcurrentHashMap<>();
    private List<Device> currentScan = new ArrayList<>();
    private ConnectivityManager.NetworkCallback networkCallback;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ✅ WakeLock and WifiLock for background reliability
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // ✅ Background executor for heavy operations
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // ✅ Debounce for WiFi reconnect
    private Runnable wifiStartRunnable;

    public class LocalBinder extends Binder {
        public NetcutService getService() { return NetcutService.this; }
    }

    public interface ServiceCallback {
        void onDataChanged();
        void onToastMessage(String msg);
    }

    public void setCallback(ServiceCallback cb) { this.callback = cb; }

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new DeviceDbHelper(this);
        binaryManager = new BinaryManager(this);
        createNotificationChannel();
        registerNetworkCallback();
        restoreKnownDevicesFromDb();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (userRequestedRunning || isEngineRunning()) {
            showForegroundNotification(
                    waitingForWifi ? "Netcut Waiting" : "Netcut Active",
                    waitingForWifi ? "Waiting for WiFi..." : "Monitoring network and enforcing bans"
            );
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "App removed from recents. Stopping and restoring...");
        userRequestedRunning = false;
        waitingForWifi = false;
        pendingStartAttempt = false;
        mainHandler.removeCallbacksAndMessages(null);
        killBinaryGracefully();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed. Ensuring binary is killed...");
        userRequestedRunning = false;
        waitingForWifi = false;
        pendingStartAttempt = false;
        unregisterNetworkCallback();
        stopBridgeOnly();
        releaseLocks();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    // ========================================================================
    // ✅ ENGINE MANAGEMENT
    // ========================================================================

    public boolean startEngine(String iface, String gateway) {
        if (bridge != null && bridge.isRunning()) return true;

        if (bridge != null) {
            try { bridge.stop(); } catch (Exception ignored) {}
            bridge = null;
        }

        // Kill any orphaned processes first
        binaryManager.killExistingProcesses();

        if (binaryPath == null || binaryPath.isEmpty()) {
            binaryPath = binaryManager.prepareBinary();
        }
        if (binaryPath == null) {
            Log.e(TAG, "Binary preparation failed.");
            notifyToast("Error: Failed to prepare native binary.");
            return false;
        }

        showForegroundNotification("Netcut Starting", "Starting engine...");
        acquireLocks();

        bridge = new RustBridge();
        boolean started = bridge.startAndWait(binaryPath, iface, gateway,
                new RustBridge.BridgeEventListener() {
                    @Override
                    public void onEvent(String event, JSONObject data) {
                        Log.i(TAG, "Event: " + event);
                        if ("SERVICE_STARTED".equals(event)) {
                            waitingForWifi = false;
                            startPeriodicScan();
                            syncBannedDevices();
                            showForegroundNotification("Netcut Active",
                                    "Monitoring network and enforcing bans");
                            notifyToast("Service Started");
                        } else if ("ERROR".equals(event)) {
                            String code = data.optString("code", "");
                            if ("NETWORK_CHANGED".equals(code) || "INTERFACE_DOWN".equals(code)) {
                                handleNetworkLost();
                            } else {
                                notifyToast("Error: " + data.optString("message"));
                            }
                        }
                        notifyDataChanged();
                    }

                    @Override
                    public void onBridgeExited() {
                        Log.w(TAG, "Rust bridge exited unexpectedly");
                        if (userRequestedRunning) {
                            mainHandler.post(() -> {
                                notifyToast("Engine stopped. Restarting...");
                                requestStartWhenReady();
                            });
                        }
                    }
                }, 5000);

        return started;
    }

    public void stopEngine() { manualStop(); }
    public boolean isEngineRunning() { return bridge != null && bridge.isRunning(); }
    public void pingBinary() { if (bridge != null) bridge.pingBinary(); }

    // ========================================================================
    // ✅ BAN/UNBAN OPERATIONS
    // ========================================================================

    public void banDevice(String mac, String ip) {
        if (mac == null) return;
        mac = Device.normalizeMac(mac);
        dbHelper.setBanned(mac, ip, true);
        updateDeviceBanStateInMemory(mac, ip, true);
        syncBannedDevices();
        notifyDataChanged();
    }

    public void unbanDevice(String mac) {
        if (mac == null) return;
        mac = Device.normalizeMac(mac);
        dbHelper.setBanned(mac, null, false);
        updateDeviceBanStateInMemory(mac, null, false);
        syncBannedDevices();
        notifyDataChanged();
    }

    /**
     * Batch ban multiple devices with a single sync.
     */
    public void banDevices(List<Device> devices) {
        for (Device d : devices) {
            String mac = Device.normalizeMac(d.getMac());
            dbHelper.setBanned(mac, d.getIp(), true);
            updateDeviceBanStateInMemory(mac, d.getIp(), true);
        }
        syncBannedDevices(); // Single sync for all
        notifyDataChanged();
    }

    /**
     * Batch unban all devices with a single sync.
     */
    public void unbanAllDevices() {
        dbHelper.unbanAll();
        // Update memory
        for (Device d : knownDevices.values()) {
            d.setBanned(false);
        }
        synchronized (currentScan) {
            for (Device d : currentScan) {
                d.setBanned(false);
            }
        }
        syncBannedDevices(); // Will send empty list
        notifyDataChanged();
    }

    public void updateDeviceName(String mac, String ip, String name) {
        if (mac == null) return;
        mac = Device.normalizeMac(mac);
        dbHelper.setName(mac, ip, name);
        String safeName = name == null ? "" : name.trim();

        Device known = knownDevices.get(mac);
        if (known != null) {
            known.setName(safeName);
            if (ip != null && !ip.trim().isEmpty()) known.setIp(ip.trim());
        }

        synchronized (currentScan) {
            for (Device d : currentScan) {
                if (mac.equals(d.getMac())) {
                    d.setName(safeName);
                    if (ip != null && !ip.trim().isEmpty()) d.setIp(ip.trim());
                    break;
                }
            }
        }
        notifyDataChanged();
    }

    // ========================================================================
    // ✅ DATA ACCESS
    // ========================================================================

    public List<Device> getConnectedDevices() {
        synchronized (currentScan) { return new ArrayList<>(currentScan); }
    }

    public List<Device> getBannedDevices() { return dbHelper.getBannedDevices(); }
    public String getDetectedArchitecture() { return binaryManager.detectArchitecture(); }
    public boolean isManualModeActive() { return userRequestedRunning; }
    public boolean isWaitingForWifi() { return waitingForWifi; }

    // ========================================================================
    // ✅ MANUAL START/STOP
    // ========================================================================

    public void manualStart() {
        userRequestedRunning = true;
        waitingForWifi = false;
        pendingStartAttempt = false;
        requestStartWhenReady();
        notifyDataChanged();
    }

    public void manualStop() {
        userRequestedRunning = false;
        waitingForWifi = false;
        pendingStartAttempt = false;
        mainHandler.removeCallbacksAndMessages(null);
        killBinaryGracefully();
        notifyDataChanged();
    }

    public void forceScan() {
        if (scheduler == null || scheduler.isShutdown()) return;
        scheduler.execute(this::performScan);
    }

    // ========================================================================
    // ✅ INTERNAL: SCANNING
    // ========================================================================

    private void startPeriodicScan() {
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::performScan, 0, 15, TimeUnit.SECONDS);
    }

    private void performScan() {
        try {
            List<Device> scanned = NetworkScanner.scanArp(this);

            // Mark all known devices offline first
            for (Device d : knownDevices.values()) d.setOnline(false);

            // Update with scanned devices
            for (Device d : scanned) {
                String mac = Device.normalizeMac(d.getMac());
                Device dbDevice = dbHelper.getDevice(mac);
                if (dbDevice != null) {
                    d.setBanned(dbDevice.isBanned());
                    if (dbDevice.getName() != null && !dbDevice.getName().isEmpty()) {
                        d.setName(dbDevice.getName());
                    }
                    dbHelper.updateIp(mac, d.getIp());
                }
                d.setOnline(true);
                knownDevices.put(mac, d);
            }

            // Sort and update currentScan
            synchronized (currentScan) {
                currentScan.clear();
                List<Device> sortedDevices = new ArrayList<>(knownDevices.values());
                sortedDevices.sort((d1, d2) -> {
                    if (d1.isOnline() == d2.isOnline()) {
                        return compareIpv4(d1.getIp(), d2.getIp());
                    }
                    return d1.isOnline() ? -1 : 1;
                });
                currentScan.addAll(sortedDevices);
            }

            syncBannedDevices();
            notifyDataChanged();

        } catch (Exception e) {
            Log.e(TAG, "Scan failed", e);
        }
    }

    /**
     * Syncs only active (online) banned devices to the native engine.
     * Prevents poisoning stale/wrong IPs.
     */
    private void syncBannedDevices() {
        if (bridge != null && bridge.isRunning()) {
            List<Device> activeBanned = new ArrayList<>();
            for (Device d : knownDevices.values()) {
                if (d.isBanned() && d.isOnline() &&
                        Device.isValidIpv4(d.getIp()) && Device.isValidMac(d.getMac())) {
                    activeBanned.add(d);
                }
            }
            bridge.syncTargets(activeBanned);
        }
    }

    // ========================================================================
    // ✅ INTERNAL: STOP/RESTORE
    // ========================================================================

    private void stopBridgeOnly() {
        final RustBridge b = bridge;
        bridge = null;

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        if (b != null) {
            // Run stop on background thread to avoid blocking main
            ioExecutor.execute(() -> {
                boolean exited = b.stopAndWait(6000, 3000);
                if (!exited) {
                    Log.w(TAG, "Binary did not exit gracefully. Spawning delayed kill.");
                    spawnDelayedKill();
                }
                releaseLocks();
            });
        }
    }

    private void killBinaryGracefully() {
        stopBridgeOnly();
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    /**
     * Spawns a detached root script that waits then kills.
     * Gives the binary time to restore before force-killing.
     */
    private void spawnDelayedKill() {
        try {
            String script =
                    "pids=$(pidof netcut_arm64 netcut_armeabi netcut 2>/dev/null); " +
                            "[ -z \"$pids\" ] && exit 0; " +
                            "kill -15 $pids 2>/dev/null; " +
                            "for i in $(seq 1 50); do " +
                            "  alive=0; " +
                            "  for p in $pids; do " +
                            "    kill -0 $p 2>/dev/null && alive=1; " +
                            "  done; " +
                            "  [ $alive -eq 0 ] && exit 0; " +
                            "  sleep 0.2; " +
                            "done; " +
                            "kill -9 $pids 2>/dev/null";

            String escaped = script.replace("'", "'\\''");
            Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "setsid sh -c '" + escaped + "' >/dev/null 2>&1 &"
            });
            Log.d(TAG, "Delayed kill spawned (10s grace period).");
        } catch (Exception e) {
            Log.e(TAG, "Failed to spawn delayed kill", e);
        }
    }

    private void handleNetworkLost() {
        if (!userRequestedRunning) return;
        mainHandler.post(() -> pauseEngineForWifiLoss());
    }

    // ========================================================================
    // ✅ INTERNAL: WIFI MANAGEMENT
    // ========================================================================

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!userRequestedRunning) return;
                // Debounce: wait 2 seconds before attempting start
                mainHandler.removeCallbacks(wifiStartRunnable);
                wifiStartRunnable = () -> requestStartWhenReady();
                mainHandler.postDelayed(wifiStartRunnable, 2000);
            }

            @Override
            public void onLost(Network network) {
                if (!userRequestedRunning) return;
                mainHandler.post(() -> pauseEngineForWifiLoss());
            }
        };

        try {
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            }
            networkCallback = null;
        }
    }

    private void pauseEngineForWifiLoss() {
        if (!userRequestedRunning) return;
        stopBridgeOnly();
        waitingForWifi = true;
        showForegroundNotification("Netcut Waiting", "WiFi disconnected. Waiting...");
        notifyToast("WiFi disconnected. Waiting...");
        notifyDataChanged();
    }

    private void requestStartWhenReady() {
        if (!userRequestedRunning || pendingStartAttempt) return;
        pendingStartAttempt = true;
        ioExecutor.execute(() -> attemptStartEngine(30));
    }

    private void attemptStartEngine(int retriesLeft) {
        if (!userRequestedRunning) {
            pendingStartAttempt = false;
            return;
        }
        if (isEngineRunning()) {
            waitingForWifi = false;
            pendingStartAttempt = false;
            return;
        }

        String iface = NetworkScanner.getInterfaceName(this);
        String gateway = NetworkScanner.getGatewayIp(this);

        if (isGatewayValid(gateway)) {
            waitingForWifi = false;
            boolean started = startEngine(iface, gateway);
            if (started) {
                pendingStartAttempt = false;
                mainHandler.postDelayed(() -> forceScan(), 1000);
                notifyDataChanged();
                return;
            }
        }

        if (retriesLeft > 0 && userRequestedRunning) {
            waitingForWifi = true;
            mainHandler.post(() -> {
                showForegroundNotification("Netcut Waiting", "Waiting for WiFi...");
                notifyDataChanged();
            });
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            attemptStartEngine(retriesLeft - 1);
        } else {
            pendingStartAttempt = false;
            waitingForWifi = true;
            mainHandler.post(() -> {
                showForegroundNotification("Netcut Waiting", "Waiting for WiFi...");
                notifyToast("Waiting for WiFi...");
                notifyDataChanged();
            });
        }
    }

    // ========================================================================
    // ✅ INTERNAL: WAKELOCK / WIFILOCK
    // ========================================================================

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netcut:engine");
                wakeLock.acquire(30 * 60 * 1000L); // 30 min max, refreshed on scan
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "netcut:wifi");
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire locks", e);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                wifiLock = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release locks", e);
        }
    }

    // ========================================================================
    // ✅ INTERNAL: HELPERS
    // ========================================================================

    private boolean isGatewayValid(String gateway) {
        return gateway != null && !gateway.trim().isEmpty() &&
                !gateway.equals("0.0.0.0") && Device.isValidIpv4(gateway);
    }

    private int compareIpv4(String a, String b) {
        try {
            String[] p1 = a.split("\\.");
            String[] p2 = b.split("\\.");
            for (int i = 0; i < 4; i++) {
                int x = Integer.parseInt(p1[i]);
                int y = Integer.parseInt(p2[i]);
                if (x != y) return Integer.compare(x, y);
            }
            return 0;
        } catch (Exception e) {
            return (a != null ? a : "").compareTo(b != null ? b : "");
        }
    }

    private void updateDeviceBanStateInMemory(String mac, String ip, boolean banned) {
        if (mac == null) return;
        mac = Device.normalizeMac(mac);

        Device known = knownDevices.get(mac);
        if (known != null) {
            known.setBanned(banned);
            if (ip != null && !ip.trim().isEmpty()) known.setIp(ip.trim());
        }

        synchronized (currentScan) {
            for (Device d : currentScan) {
                if (mac.equals(d.getMac())) {
                    d.setBanned(banned);
                    if (ip != null && !ip.trim().isEmpty()) d.setIp(ip.trim());
                    break;
                }
            }
        }
    }

    private void restoreKnownDevicesFromDb() {
        knownDevices.clear();
        synchronized (currentScan) { currentScan.clear(); }

        List<Device> savedDevices = dbHelper.getSavedDevices();
        for (Device d : savedDevices) {
            d.setOnline(false);
            knownDevices.put(Device.normalizeMac(d.getMac()), d);
        }

        List<Device> sorted = new ArrayList<>(knownDevices.values());
        sorted.sort((d1, d2) -> {
            if (d1.isOnline() == d2.isOnline()) {
                return compareIpv4(d1.getIp(), d2.getIp());
            }
            return d1.isOnline() ? -1 : 1;
        });

        synchronized (currentScan) { currentScan.addAll(sorted); }
    }

    private void notifyDataChanged() { if (callback != null) callback.onDataChanged(); }
    private void notifyToast(String msg) { if (callback != null) callback.onToastMessage(msg); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "NETCUT_CHANNEL", "Netcut Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void showForegroundNotification(String title, String text) {
        try {
            Notification notification = new NotificationCompat.Builder(this, "NETCUT_CHANNEL")
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Notification update failed", e);
        }
    }
}