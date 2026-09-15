package com.example.netcutapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
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

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "App removed from recents. Initiating graceful binary shutdown and ARP restore...");
        killBinaryGracefully();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed. Ensuring binary is killed...");
        killBinaryGracefully();
        super.onDestroy();
    }

    public void startEngine(String iface, String gateway) {
        if (bridge != null && bridge.isRunning()) return;

        bridge = new RustBridge();
        bridge.start(iface, gateway, new RustBridge.BridgeEventListener() {
            @Override
            public void onEvent(String event, JSONObject data) {
                Log.i(TAG, "Event: " + event);
                if ("SERVICE_STARTED".equals(event)) {
                    startPeriodicScan();
                    notifyToast("Service Started");
                } else if ("SYNC_COMPLETED".equals(event)) {
                    // notifyToast("Sync Completed");
                } else if ("ERROR".equals(event)) {
                    notifyToast("Error: " + data.optString("message"));
                }
                notifyDataChanged();
            }
        });
    }

    public void stopEngine() {
        killBinaryGracefully();
    }

    public boolean isEngineRunning() {
        return bridge != null && bridge.isRunning();
    }

    public void pingBinary() {
        if (bridge != null) bridge.pingBinary();
    }

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
        synchronized (currentScan) {
            return new ArrayList<>(currentScan);
        }
    }

    public List<Device> getBannedDevices() {
        return dbHelper.getBannedDevices();
    }

    private void startPeriodicScan() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            List<Device> scanned = NetworkScanner.scanArp(this);

            // ✅ 1. Mark all currently known devices as offline first
            for (Device d : knownDevices.values()) {
                d.setOnline(false);
            }

            // ✅ 2. Update with freshly scanned devices (they are online)
            for (Device d : scanned) {
                Device dbDevice = dbHelper.getDevice(d.getMac());
                if (dbDevice != null) {
                    d.setBanned(dbDevice.isBanned());
                    if (dbDevice.getName() != null && !dbDevice.getName().isEmpty()) {
                        d.setName(dbDevice.getName());
                    }
                    dbHelper.updateIp(d.getMac(), d.getIp());
                }
                d.setOnline(true);
                knownDevices.put(d.getMac(), d);
            }

            // ✅ 3. Update currentScan for the UI (sort: Online first, then by IP)
            synchronized (currentScan) {
                currentScan.clear();
                List<Device> sortedDevices = new ArrayList<>(knownDevices.values());
                sortedDevices.sort((d1, d2) -> {
                    if (d1.isOnline() == d2.isOnline()) {
                        return d1.getIp().compareTo(d2.getIp());
                    }
                    return d1.isOnline() ? -1 : 1; // Online devices appear at the top
                });
                currentScan.addAll(sortedDevices);
            }

            syncBannedDevices();
            notifyDataChanged();
        }, 0, 15, TimeUnit.SECONDS);
    }

    public void forceScan() {
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            Log.w(TAG, "Cannot force scan: scheduler is not running");
            return;
        }

        scheduler.execute(() -> {
            List<Device> scanned = NetworkScanner.scanArp(this);

            for (Device d : knownDevices.values()) {
                d.setOnline(false);
            }

            for (Device d : scanned) {
                Device dbDevice = dbHelper.getDevice(d.getMac());
                if (dbDevice != null) {
                    d.setBanned(dbDevice.isBanned());
                    if (dbDevice.getName() != null && !dbDevice.getName().isEmpty()) {
                        d.setName(dbDevice.getName());
                    }
                    dbHelper.updateIp(d.getMac(), d.getIp());
                }
                d.setOnline(true);
                knownDevices.put(d.getMac(), d);
            }

            synchronized (currentScan) {
                currentScan.clear();
                List<Device> sortedDevices = new ArrayList<>(knownDevices.values());
                sortedDevices.sort((d1, d2) -> {
                    if (d1.isOnline() == d2.isOnline()) {
                        return d1.getIp().compareTo(d2.getIp());
                    }
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
            List<Device> targets = dbHelper.getBannedDevices();
            bridge.syncTargets(targets);
        }
    }

    private void notifyDataChanged() {
        if (callback != null) callback.onDataChanged();
    }

    private void notifyToast(String msg) {
        if (callback != null) callback.onToastMessage(msg);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "NETCUT_CHANNEL", "Netcut Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void killBinaryGracefully() {
        if (bridge != null && bridge.isRunning()) {
            bridge.stop();
        }

        new Thread(() -> {
            try {
                String pidOutput = RootManager.execute("pidof netcut");
                if (pidOutput != null && !pidOutput.trim().isEmpty()) {
                    String pid = pidOutput.trim().split("\\s+")[0];
                    Log.d(TAG, "Found netcut PID: " + pid + ". Sending SIGTERM (15) for graceful restore...");
                    RootManager.execute("kill -15 " + pid);

                    Thread.sleep(2500);

                    String stillRunning = RootManager.execute("pidof netcut");
                    if (stillRunning != null && !stillRunning.trim().isEmpty()) {
                        Log.d(TAG, "Force killing (SIGKILL)...");
                        RootManager.execute("kill -9 " + pid);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during graceful kill", e);
            }

            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
        }).start();

        stopForeground(true);
        stopSelf();
    }
}