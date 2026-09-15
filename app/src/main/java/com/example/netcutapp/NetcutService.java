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

    // In-memory list for currently scanned devices (Issue 2 Fix)
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
                .build();
        startForeground(1, notification);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

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
                    notifyToast("Sync Completed");
                } else if ("ERROR".equals(event)) {
                    notifyToast("Error: " + data.optString("message"));
                }
                notifyDataChanged();
            }
        });
    }

    public void stopEngine() {
        if (scheduler != null) scheduler.shutdownNow();
        if (bridge != null) bridge.stop();
        stopForeground(true);
        stopSelf();
    }

    public boolean isEngineRunning() {
        return bridge != null && bridge.isRunning();
    }

    public void pingBinary() {
        if (bridge != null) bridge.pingBinary();
    }

    // FIX: Updated to accept IP, as the DB requires it for new entries
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

    // FIX: Updated to accept IP, as the DB requires it for new entries
    public void updateDeviceName(String mac, String ip, String name) {
        dbHelper.setName(mac, ip, name);
        notifyDataChanged();
    }

    // FIX: Returns the in-memory scanned list instead of querying the DB
    public List<Device> getConnectedDevices() {
        synchronized (currentScan) {
            return new ArrayList<>(currentScan);
        }
    }

    public List<Device> getBannedDevices() {
        return dbHelper.getBannedDevices();
    }

    private void startPeriodicScan() {
        // FIX (Issue 2): Clear previous scan on restart to ensure a fresh state
        synchronized (currentScan) {
            currentScan.clear();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            List<Device> scanned = NetworkScanner.scanArp(this);

            // Merge with DB for banned/named status
            for (Device d : scanned) {
                Device dbDevice = dbHelper.getDevice(d.getMac());
                if (dbDevice != null) {
                    d.setBanned(dbDevice.isBanned());
                    // FIX (Issue 3): Only pull name if it was explicitly saved
                    if (dbDevice.getName() != null && !dbDevice.getName().isEmpty()) {
                        d.setName(dbDevice.getName());
                    }
                    // Update IP in DB if the device is already tracked
                    dbHelper.updateIp(d.getMac(), d.getIp());
                }
            }

            synchronized (currentScan) {
                currentScan.clear();
                currentScan.addAll(scanned);
            }

            syncBannedDevices();
            notifyDataChanged();
        }, 0, 15, TimeUnit.SECONDS);
    }

    public void forceScan() {
        // SAFETY CHECK: Do not attempt to scan if the scheduler is shut down or terminated
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            Log.w(TAG, "Cannot force scan: scheduler is not running");
            return;
        }

        scheduler.execute(() -> {
            List<Device> scanned = NetworkScanner.scanArp(this);
            for (Device d : scanned) {
                Device dbDevice = dbHelper.getDevice(d.getMac());
                if (dbDevice != null) {
                    d.setBanned(dbDevice.isBanned());
                    if (dbDevice.getName() != null && !dbDevice.getName().isEmpty()) {
                        d.setName(dbDevice.getName());
                    }
                    dbHelper.updateIp(d.getMac(), d.getIp());
                }
            }
            synchronized (currentScan) {
                currentScan.clear();
                currentScan.addAll(scanned);
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
            manager.createNotificationChannel(channel);
        }
    }
}