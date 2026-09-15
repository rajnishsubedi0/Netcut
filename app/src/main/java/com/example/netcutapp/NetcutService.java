package com.example.netcutapp;

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
    private static final String TAG = "NetcutService";
    private final IBinder binder = new LocalBinder();
    private RustBridge bridge;
    private DeviceDbHelper dbHelper;
    private ScheduledExecutorService scheduler;
    private ServiceCallback callback;

    // ✅ Tracks all known devices to accurately mark them as offline when they drop
    private Map<String, Device> knownDevices = new ConcurrentHashMap<>();
    private List<Device> currentScan = new ArrayList<>();

    // ✅ WiFi monitoring callback (lives in Service so it works in background)
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
        createNotificationChannel();

        // ✅ Register WiFi listener in Service so it survives background
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, "NETCUT_CHANNEL")
                .setContentTitle("Netcut Active")
                .setContentText("Monitoring network and enforcing bans")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build();
        startForeground(1, notification);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "App removed from recents. Initiating graceful binary shutdown and ARP restore...");
        killBinaryGracefully();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed. Ensuring binary is killed...");
        unregisterNetworkCallback();
        killBinaryGracefully();
        super.onDestroy();
    }

    public void startEngine(String iface, String gateway) {
        if (bridge != null && bridge.isRunning()) return;

        // ✅ FIX: Clear memory completely on start to prevent stale caching
        knownDevices.clear();
        synchronized (currentScan) { currentScan.clear(); }
        notifyDataChanged();

        bridge = new RustBridge();
        bridge.start(iface, gateway, new RustBridge.BridgeEventListener() {
            @Override
            public void onEvent(String event, JSONObject data) {
                Log.i(TAG, "Event: " + event);
                if ("SERVICE_STARTED".equals(event)) {
                    startPeriodicScan();
                    notifyToast("Service Started");
                } else if ("ERROR".equals(event)) {
                    notifyToast("Error: " + data.optString("message"));
                }
                notifyDataChanged();
            }
        });
    }

    public void stopEngine() { killBinaryGracefully(); }
    public boolean isEngineRunning() { return bridge != null && bridge.isRunning(); }
    public void pingBinary() { if (bridge != null) bridge.pingBinary(); }

    public void banDevice(String mac, String ip) {
        dbHelper.setBanned(mac, ip, true);
        syncBannedDevices();
        notifyDataChanged();
    }

    public void unbanDevice(String mac) {
        dbHelper.setBanned(mac, "", false);
        syncBannedDevices();
        notifyDataChanged();
    }

    public void updateDeviceName(String mac, String ip, String name) {
        dbHelper.setName(mac, ip, name);
        notifyDataChanged();
    }

    public List<Device> getConnectedDevices() {
        synchronized (currentScan) { return new ArrayList<>(currentScan); }
    }

    public List<Device> getBannedDevices() { return dbHelper.getBannedDevices(); }

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

    // ========================================================================
    // ✅ WIFI AUTO-RESTART (Lives in Service → works in background)
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
                Log.d(TAG, "WiFi available detected in background service");
                // Delay to let DHCP assign gateway IP
                mainHandler.postDelayed(() -> {
                    if (bridge == null || !bridge.isRunning()) {
                        String gateway = NetworkScanner.getGatewayIp(NetcutService.this);
                        String iface = NetworkScanner.getInterfaceName();
                        if (gateway != null) {
                            Log.d(TAG, "WiFi reconnected. Auto-restarting engine...");
                            notifyToast("WiFi connected. Restarting service...");
                            startEngine(iface, gateway);
                            // Delay scan to let engine initialize
                            mainHandler.postDelayed(() -> forceScan(), 1500);
                        }
                    }
                }, 1500);
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "WiFi lost detected in background service");
                mainHandler.post(() -> {
                    if (bridge != null && bridge.isRunning()) {
                        Log.d(TAG, "WiFi disconnected. Stopping engine...");
                        notifyToast("WiFi disconnected. Stopping service...");
                        stopEngine();
                    }
                });
            }
        };

        try {
            cm.registerNetworkCallback(request, networkCallback);
            Log.d(TAG, "WiFi NetworkCallback registered in Service");
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
                    Log.d(TAG, "WiFi NetworkCallback unregistered");
                } catch (Exception ignored) {}
            }
            networkCallback = null;
        }
    }

    // ========================================================================
    // ✅ GRACEFUL KILL (Detached shell survives app process death)
    // ========================================================================

    private void killBinaryGracefully() {
        if (bridge != null && bridge.isRunning()) {
            bridge.stop();
        }

        try {
            // Detached shell survives app death
            Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "setsid sh -c 'pidof netcut | xargs -r kill -15; sleep 3; pidof netcut | xargs -r kill -9' >/dev/null 2>&1 &"});
            Log.d(TAG, "Detached kill process spawned. App can now die safely.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute detached kill", e);
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        stopForeground(true);
        stopSelf();
    }
}