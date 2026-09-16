package com.rkant.netcut;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NetcutService extends Service {
    private volatile boolean userRequestedRunning = false;
    private volatile boolean waitingForWifi = false;
    private volatile boolean pendingStartAttempt = false;
    private static final String TAG = "NetcutService";
    private final IBinder binder = new LocalBinder();
    private RustBridge bridge;
    private DeviceDbHelper dbHelper;
    private ScheduledExecutorService scheduler;
    private ServiceCallback callback;

    // ✅ Binary manager for extraction and permission handling
    private BinaryManager binaryManager;
    private String binaryPath = null;

    private Map<String, Device> knownDevices = new ConcurrentHashMap<>();
    private List<Device> currentScan = new ArrayList<>();

    private ConnectivityManager.NetworkCallback networkCallback;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

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
        binaryManager = new BinaryManager(this); // ✅ Initialize BinaryManager
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

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "App removed from recents. Stopping service and restoring internet immediately.");

        userRequestedRunning = false;
        waitingForWifi = false;
        pendingStartAttempt = false;

        mainHandler.removeCallbacksAndMessages(null);

        killBinaryGracefully();

        super.onTaskRemoved(rootIntent);
    }
    private void unregisterNetworkCallback() {
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                    Log.d(TAG, "WiFi NetworkCallback unregistered");
                } catch (Exception ignored) {
                }
            }

            networkCallback = null;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed. Ensuring binary is killed...");

        userRequestedRunning = false;
        waitingForWifi = false;
        pendingStartAttempt = false;

        unregisterNetworkCallback();
        stopBridgeOnly();

        super.onDestroy();
    }

    /**
     * ✅ Prepares the binary and starts the engine.
     * Returns true if engine started successfully, false otherwise.
     */
    public boolean startEngine(String iface, String gateway) {
        if (bridge != null && bridge.isRunning()) return true;

        if (bridge != null) {
            try {
                bridge.stop();
            } catch (Exception ignored) {}
            bridge = null;
        }

        if (binaryPath == null || binaryPath.isEmpty()) {
            binaryPath = binaryManager.prepareBinary();
        }

        if (binaryPath == null) {
            Log.e(TAG, "Binary preparation failed. Cannot start engine.");
            notifyToast("Error: Failed to prepare native binary. Unsupported architecture?");
            return false;
        }

        Log.i(TAG, "Using binary at: " + binaryPath);

        // ✅ Do NOT clear knownDevices here.
        // This prevents losing the current list when WiFi disconnects/reconnects.
        // The periodic scan will refresh online/offline state.

        showForegroundNotification(
                "Netcut Starting",
                "Starting engine..."
        );

        bridge = new RustBridge();

        bridge.start(binaryPath, iface, gateway, new RustBridge.BridgeEventListener() {
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
                    notifyToast("Error: " + data.optString("message"));
                }

                notifyDataChanged();
            }
        });

        return bridge.isRunning();
    }

    public void stopEngine() {
        manualStop();
    }
    public boolean isEngineRunning() { return bridge != null && bridge.isRunning(); }
    public void pingBinary() { if (bridge != null) bridge.pingBinary(); }

    public void banDevice(String mac, String ip) {
        if (mac == null) return;

        mac = mac.trim();

        dbHelper.setBanned(mac, ip, true);

        updateDeviceBanStateInMemory(mac, ip, true);

        syncBannedDevices();
        notifyDataChanged();
    }

    public void unbanDevice(String mac) {
        if (mac == null) return;

        mac = mac.trim();

        dbHelper.setBanned(mac, null, false);

        updateDeviceBanStateInMemory(mac, null, false);

        syncBannedDevices();
        notifyDataChanged();
    }

    public void updateDeviceName(String mac, String ip, String name) {
        if (mac == null) return;

        mac = mac.trim();

        dbHelper.setName(mac, ip, name);

        String safeName = name == null ? "" : name.trim();

        // Update knownDevices cache
        Device known = knownDevices.get(mac);
        if (known != null) {
            known.setName(safeName);

            if (ip != null && !ip.trim().isEmpty()) {
                known.setIp(ip.trim());
            }
        }

        // Update current visible scan list
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

    public List<Device> getConnectedDevices() {
        synchronized (currentScan) { return new ArrayList<>(currentScan); }
    }

    public List<Device> getBannedDevices() { return dbHelper.getBannedDevices(); }

    /**
     * ✅ Returns the detected architecture for UI display.
     */
    public String getDetectedArchitecture() {
        if (binaryManager != null) {
            return binaryManager.detectArchitecture();
        }
        return "unknown";
    }

    private void startPeriodicScan() {
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            List<Device> scanned = NetworkScanner.scanArp(this);

            for (Device d : knownDevices.values()) d.setOnline(false);

            for (Device d : scanned) {
                Device dbDevice = dbHelper.getDevice(d.getMac());
                if (dbDevice != null) {
                    d.setBanned(dbDevice.isBanned());
                    if (dbDevice.getName() != null && !dbDevice.getName().isEmpty()) d.setName(dbDevice.getName());
                    dbHelper.updateIp(d.getMac(), d.getIp());
                }
                d.setOnline(true);
                knownDevices.put(d.getMac(), d);
            }

            synchronized (currentScan) {
                currentScan.clear();
                List<Device> sortedDevices = new ArrayList<>(knownDevices.values());
                sortedDevices.sort((d1, d2) -> {
                    if (d1.isOnline() == d2.isOnline()) return d1.getIp().compareTo(d2.getIp());
                    return d1.isOnline() ? -1 : 1;
                });
                currentScan.addAll(sortedDevices);
            }

            syncBannedDevices();
            notifyDataChanged();
        }, 0, 15, TimeUnit.SECONDS);
    }

    public void forceScan() {
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) return;
        scheduler.execute(() -> {
            List<Device> scanned = NetworkScanner.scanArp(this);
            for (Device d : knownDevices.values()) d.setOnline(false);
            for (Device d : scanned) {
                Device dbDevice = dbHelper.getDevice(d.getMac());
                if (dbDevice != null) {
                    d.setBanned(dbDevice.isBanned());
                    if (dbDevice.getName() != null && !dbDevice.getName().isEmpty()) d.setName(dbDevice.getName());
                    dbHelper.updateIp(d.getMac(), d.getIp());
                }
                d.setOnline(true);
                knownDevices.put(d.getMac(), d);
            }
            synchronized (currentScan) {
                currentScan.clear();
                List<Device> sortedDevices = new ArrayList<>(knownDevices.values());
                sortedDevices.sort((d1, d2) -> {
                    if (d1.isOnline() == d2.isOnline()) return d1.getIp().compareTo(d2.getIp());
                    return d1.isOnline() ? -1 : 1;
                });
                currentScan.addAll(sortedDevices);
            }
            syncBannedDevices();
            notifyDataChanged();
        });
    }

    private void syncBannedDevices() {
        if (bridge != null && bridge.isRunning()) {
            bridge.syncTargets(dbHelper.getBannedDevices());
        }
    }

    private void notifyDataChanged() { if (callback != null) callback.onDataChanged(); }
    private void notifyToast(String msg) { if (callback != null) callback.onToastMessage(msg); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("NETCUT_CHANNEL", "Netcut Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
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
                Log.d(TAG, "WiFi available detected in background service");

                // ✅ Only auto-start if user has manually pressed Start Service.
                if (!userRequestedRunning) return;

                mainHandler.postDelayed(() -> requestStartWhenReady(), 1000);
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "WiFi lost detected in background service");

                // ✅ Only pause/wait if user manually enabled the service.
                if (!userRequestedRunning) return;

                mainHandler.post(() -> pauseEngineForWifiLoss());
            }
        };

        try {
            cm.registerNetworkCallback(request, networkCallback);
            Log.d(TAG, "WiFi NetworkCallback registered in Service");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }
    private void stopBridgeOnly() {
        if (bridge != null) {
            try {
                bridge.stop();
            } catch (Exception e) {
                Log.e(TAG, "Bridge stop failed", e);
            }

            bridge = null;
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        spawnDetachedKill();
    }

    private void killBinaryGracefully() {
        stopBridgeOnly();

        try {
            stopForeground(true);
        } catch (Exception ignored) {}

        stopSelf();
    }

    private void spawnDetachedKill() {
        try {
            String script =
                    "for name in netcut_arm64 netcut_armeabi netcut; do " +
                            "pids=$(pidof $name 2>/dev/null); " +
                            "[ -n \"$pids\" ] && kill -15 $pids 2>/dev/null; " +
                            "done; " +
                            "sleep 0.3; " +
                            "for name in netcut_arm64 netcut_armeabi netcut; do " +
                            "pids=$(pidof $name 2>/dev/null); " +
                            "[ -n \"$pids\" ] && kill -9 $pids 2>/dev/null; " +
                            "done";

            String escaped = script.replace("'", "'\\''");

            Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "setsid sh -c '" + escaped + "' >/dev/null 2>&1 &"
            });

            Log.d(TAG, "Detached kill process spawned.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute detached kill", e);
        }
    }
    private void showForegroundNotification(String title, String text) {
        try {
            Notification notification = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(this, "NETCUT_CHANNEL")
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .setOngoing(true)
                        .build();
            }

            startForeground(1, notification);
        } catch (Exception e) {
            Log.e(TAG, "Notification update failed", e);
        }
    }
    private void updateDeviceBanStateInMemory(String mac, String ip, boolean banned) {
        if (mac == null) return;

        mac = mac.trim();

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
    private void restoreKnownDevicesFromDb() {
        knownDevices.clear();

        synchronized (currentScan) {
            currentScan.clear();
        }

        List<Device> savedDevices = dbHelper.getSavedDevices();

        for (Device d : savedDevices) {
            d.setOnline(false);
            knownDevices.put(d.getMac(), d);
        }

        List<Device> sorted = new ArrayList<>(knownDevices.values());

        sorted.sort((d1, d2) -> {
            if (d1.isOnline() == d2.isOnline()) {
                String ip1 = d1.getIp() == null ? "" : d1.getIp();
                String ip2 = d2.getIp() == null ? "" : d2.getIp();
                return ip1.compareTo(ip2);
            }

            return d1.isOnline() ? -1 : 1;
        });

        synchronized (currentScan) {
            currentScan.addAll(sorted);
        }
    }
    public boolean isManualModeActive() {
        return userRequestedRunning;
    }

    public boolean isWaitingForWifi() {
        return waitingForWifi;
    }
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
    private void requestStartWhenReady() {
        if (!userRequestedRunning || pendingStartAttempt) return;

        pendingStartAttempt = true;
        mainHandler.post(() -> attemptStartEngine(30));
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

        String iface = NetworkScanner.getInterfaceName();
        String gateway = NetworkScanner.getGatewayIp(this);

        if (isGatewayValid(gateway)) {
            waitingForWifi = false;

            showForegroundNotification(
                    "Netcut Active",
                    "Monitoring network and enforcing bans"
            );

            boolean started = startEngine(iface, gateway);

            if (started) {
                pendingStartAttempt = false;
                mainHandler.postDelayed(() -> forceScan(), 1000);
                notifyDataChanged();
                return;
            }

            notifyToast("Failed to start engine. Retrying...");
        }

        if (retriesLeft > 0 && userRequestedRunning) {
            waitingForWifi = true;

            showForegroundNotification(
                    "Netcut Waiting",
                    "Waiting for WiFi..."
            );

            mainHandler.postDelayed(() -> attemptStartEngine(retriesLeft - 1), 2000);
        } else {
            pendingStartAttempt = false;
            waitingForWifi = true;

            showForegroundNotification(
                    "Netcut Waiting",
                    "Waiting for WiFi..."
            );

            notifyToast("Waiting for WiFi...");
            notifyDataChanged();
        }
    }

    private boolean isGatewayValid(String gateway) {
        return gateway != null
                && !gateway.trim().isEmpty()
                && !gateway.equals("0.0.0.0");
    }
    private void pauseEngineForWifiLoss() {
        if (!userRequestedRunning) return;

        stopBridgeOnly();

        waitingForWifi = true;

        showForegroundNotification(
                "Netcut Waiting",
                "WiFi disconnected. Waiting for WiFi..."
        );

        notifyToast("WiFi disconnected. Waiting for WiFi...");

        notifyDataChanged();
    }
}
