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
import java.util.concurrent.atomic.AtomicBoolean;

public class NetcutService extends Service {

    private static final String TAG = "NetcutService";

    // Increased restore timeout.
    //
    // Patched main2.rs caps restore time at 5 seconds.
    // Android side waits 9 seconds to be safe.
    private static final long RESTORE_TIMEOUT_MS = 9000L;
    private static final long EXIT_TIMEOUT_MS = 3000L;

    private volatile boolean userRequestedRunning = false;
    private volatile boolean waitingForWifi = false;
    private volatile boolean pendingStartAttempt = false;

    private final AtomicBoolean isTransitioning = new AtomicBoolean(false);

    private final Handler syncHandler = new Handler(Looper.getMainLooper());

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
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
    };

    private final IBinder binder = new LocalBinder();

    private volatile RustBridge bridge;
    private DeviceDbHelper dbHelper;
    private ScheduledExecutorService scheduler;
    private ServiceCallback callback;
    private BinaryManager binaryManager;
    private String binaryPath = null;

    private Map<String, Device> knownDevices = new ConcurrentHashMap<>();
    private List<Device> currentScan = new ArrayList<>();

    private ConnectivityManager.NetworkCallback networkCallback;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public class LocalBinder extends Binder {
        public NetcutService getService() {
            return NetcutService.this;
        }
    }

    public interface ServiceCallback {
        void onDataChanged();
        void onToastMessage(String msg);
    }

    public void setCallback(ServiceCallback cb) {
        this.callback = cb;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        dbHelper = new DeviceDbHelper(this);
        binaryManager = new BinaryManager(this);

        createNotificationChannel();
        registerNetworkCallback();
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
    public IBinder onBind(Intent intent) {
        return binder;
    }

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
        Log.d(TAG, "Service destroyed.");

        userRequestedRunning = false;
        waitingForWifi = false;
        pendingStartAttempt = false;

        unregisterNetworkCallback();

        boolean hadBridge = bridge != null;

        stopBridgeOnly();

        // If a stop thread was spawned, that thread releases locks after restore.
        // If there was no bridge, release locks immediately.
        if (!hadBridge) {
            releaseLocks();
        }

        ioExecutor.shutdownNow();

        super.onDestroy();
    }

    // ========================================================================
    // MANUAL START / STOP
    // ========================================================================

    public void manualStart() {
        if (isTransitioning.getAndSet(true)) {
            Log.w(TAG, "Start blocked: already transitioning");
            notifyToast("Please wait...");
            return;
        }

        userRequestedRunning = true;
        waitingForWifi = false;
        pendingStartAttempt = false;

        requestStartWhenReady();
        notifyDataChanged();

        mainHandler.postDelayed(() -> isTransitioning.set(false), 3000);
    }

    public void manualStop() {
        if (isTransitioning.getAndSet(true)) {
            Log.w(TAG, "Stop blocked: already transitioning");
            notifyToast("Please wait...");
            return;
        }

        userRequestedRunning = false;
        waitingForWifi = false;
        pendingStartAttempt = false;

        mainHandler.removeCallbacksAndMessages(null);

        killBinaryGracefully();

        notifyDataChanged();

        mainHandler.postDelayed(() -> isTransitioning.set(false), 3000);
    }

    public boolean isTransitioning() {
        return isTransitioning.get();
    }

    // ========================================================================
    // ENGINE MANAGEMENT
    // ========================================================================

    public boolean startEngine(String iface, String gateway) {
        if (!userRequestedRunning) {
            return false;
        }

        if (bridge != null && bridge.isRunning()) {
            return true;
        }

        if (bridge != null) {
            try {
                bridge.stop();
            } catch (Exception ignored) {
            }
            bridge = null;
        }

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

        boolean started = bridge.startAndWait(
                binaryPath,
                iface,
                gateway,
                new RustBridge.BridgeEventListener() {
                    @Override
                    public void onEvent(String event, JSONObject data) {
                        Log.i(TAG, "Event: " + event);

                        if ("SERVICE_STARTED".equals(event)) {
                            waitingForWifi = false;

                            startPeriodicScan();
                            syncBannedDevices();

                            showForegroundNotification(
                                    "Netcut Active",
                                    "Monitoring network and enforcing bans"
                            );

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
                },
                5000
        );

        return started;
    }

    public void stopEngine() {
        manualStop();
    }

    public boolean isEngineRunning() {
        return bridge != null && bridge.isRunning();
    }

    public void pingBinary() {
        if (bridge != null) {
            bridge.pingBinary();
        }
    }

    // ========================================================================
    // BAN / UNBAN
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

    public void banDevices(List<Device> devices) {
        for (Device d : devices) {
            String mac = Device.normalizeMac(d.getMac());

            dbHelper.setBanned(mac, d.getIp(), true);
            updateDeviceBanStateInMemory(mac, d.getIp(), true);
        }

        syncBannedDevices();
        notifyDataChanged();
    }

    public void unbanAllDevices() {
        dbHelper.unbanAll();

        for (Device d : knownDevices.values()) {
            d.setBanned(false);
        }

        synchronized (currentScan) {
            for (Device d : currentScan) {
                d.setBanned(false);
            }
        }

        syncBannedDevices();
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

            if (ip != null && !ip.trim().isEmpty()) {
                known.setIp(ip.trim());
            }
        }

        synchronized (currentScan) {
            for (Device d : currentScan) {
                if (mac.equals(d.getMac())) {
                    d.setName(safeName);

                    if (ip != null && !ip.trim().isEmpty()) {
                        d.setIp(ip.trim());
                    }

                    break;
                }
            }
        }

        notifyDataChanged();
    }

    // ========================================================================
    // DATA ACCESS
    // ========================================================================

    public List<Device> getConnectedDevices() {
        synchronized (currentScan) {
            return new ArrayList<>(currentScan);
        }
    }

    public List<Device> getBannedDevices() {
        return dbHelper.getBannedDevices();
    }

    public String getDetectedArchitecture() {
        return binaryManager != null ? binaryManager.detectArchitecture() : "unknown";
    }

    public boolean isManualModeActive() {
        return userRequestedRunning;
    }

    public boolean isWaitingForWifi() {
        return waitingForWifi;
    }

    // ========================================================================
    // SCANNING
    // ========================================================================

    private void startPeriodicScan() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleWithFixedDelay(
                this::performScan,
                0,
                15,
                TimeUnit.SECONDS
        );
    }

    private void performScan() {
        try {
            List<Device> scanned = NetworkScanner.scanArp(this);

            for (Device d : knownDevices.values()) {
                d.setOnline(false);
            }

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

            synchronized (currentScan) {
                currentScan.clear();

                List<Device> onlineOnly = new ArrayList<>();

                for (Device d : knownDevices.values()) {
                    if (d.isOnline()) {
                        onlineOnly.add(d);
                    }
                }

                onlineOnly.sort(this::compareIpv4);

                currentScan.addAll(onlineOnly);
            }

            syncBannedDevices();
            notifyDataChanged();
        } catch (Exception e) {
            Log.e(TAG, "Scan failed", e);
        }
    }

    public void forceScan() {
        if (scheduler == null || scheduler.isShutdown()) return;

        scheduler.execute(this::performScan);
    }

    private void syncBannedDevices() {
        syncHandler.removeCallbacks(syncRunnable);
        syncHandler.postDelayed(syncRunnable, 300);
    }

    // ========================================================================
    // STOP / RESTORE
    // ========================================================================

    private void stopBridgeOnly() {
        final RustBridge b = bridge;
        bridge = null;

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        if (b != null) {
            // Important fix:
            //
            // Do NOT use ioExecutor here.
            //
            // onDestroy() calls ioExecutor.shutdownNow(), which can interrupt
            // the graceful restore wait and cause the native binary to be killed
            // before ARP restore completes.
            //
            // A dedicated non-daemon thread gives the restore process a better
            // chance to finish even if the app is removed from recents.
            Thread stopThread = new Thread(() -> {
                boolean exited = b.stopAndWait(RESTORE_TIMEOUT_MS, EXIT_TIMEOUT_MS);

                if (!exited) {
                    Log.w(TAG, "Binary did not exit gracefully. Spawning delayed kill.");
                    spawnDelayedKill();
                }

                releaseLocks();
            }, "netcut-graceful-stop");

            stopThread.setDaemon(false);
            stopThread.start();
        }
    }

    private void killBinaryGracefully() {
        stopBridgeOnly();

        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }

        stopSelf();
    }

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

            Log.d(TAG, "Delayed kill spawned.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to spawn delayed kill", e);
        }
    }

    // ========================================================================
    // WIFI MANAGEMENT
    // ========================================================================

    private void handleNetworkLost() {
        if (!userRequestedRunning) return;

        mainHandler.post(() -> pauseEngineForWifiLoss());
    }

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

                mainHandler.removeCallbacksAndMessages(null);
                mainHandler.postDelayed(() -> requestStartWhenReady(), 2000);
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
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {
                }
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

                mainHandler.postDelayed(this::forceScan, 1000);

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

            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }

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
    // WAKELOCK / WIFILOCK
    // ========================================================================

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netcut:engine");
                wakeLock.acquire(30 * 60 * 1000L);
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
    // HELPERS
    // ========================================================================

    private boolean isGatewayValid(String gateway) {
        return gateway != null && !gateway.trim().isEmpty() &&
                !gateway.equals("0.0.0.0") && Device.isValidIpv4(gateway);
    }

    private int compareIpv4(Device a, Device b) {
        try {
            String[] p1 = a.getIp().split("\\.");
            String[] p2 = b.getIp().split("\\.");

            for (int i = 0; i < 4; i++) {
                int x = Integer.parseInt(p1[i]);
                int y = Integer.parseInt(p2[i]);

                if (x != y) return Integer.compare(x, y);
            }

            return 0;
        } catch (Exception e) {
            return (a.getIp() != null ? a.getIp() : "")
                    .compareTo(b.getIp() != null ? b.getIp() : "");
        }
    }

    private void updateDeviceBanStateInMemory(String mac, String ip, boolean banned) {
        if (mac == null) return;

        mac = Device.normalizeMac(mac);

        Device known = knownDevices.get(mac);

        if (known != null) {
            known.setBanned(banned);

            if (ip != null && !ip.trim().isEmpty()) {
                known.setIp(ip.trim());
            }
        }

        synchronized (currentScan) {
            for (Device d : currentScan) {
                if (mac.equals(d.getMac())) {
                    d.setBanned(banned);

                    if (ip != null && !ip.trim().isEmpty()) {
                        d.setIp(ip.trim());
                    }

                    break;
                }
            }
        }
    }

    private void notifyDataChanged() {
        if (callback != null) {
            callback.onDataChanged();
        }
    }

    private void notifyToast(String msg) {
        if (callback != null) {
            callback.onToastMessage(msg);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "NETCUT_CHANNEL",
                    "Netcut Service",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager = getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
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
                try {
                    startForeground(
                            1,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    );
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Foreground type mismatch, falling back to default", e);
                    startForeground(1, notification);
                }
            } else {
                startForeground(1, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Notification update failed", e);
        }
    }
}