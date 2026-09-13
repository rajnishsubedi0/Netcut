package com.example.netcutapp;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class RustBridge {
    private static final String TAG = "RustBridge";
    private Process process;
    private DataOutputStream stdin;
    private BufferedReader stdout;
    private ExecutorService executor;
    private BridgeEventListener listener;
    private AtomicInteger idCounter = new AtomicInteger(1);
    private boolean isRunning = false;

    public interface BridgeEventListener {
        void onEvent(String event, JSONObject data);
    }

    public void start(String iface, String gateway, BridgeEventListener listener) {
        this.listener = listener;
        try {
            // SELinux bypass for AF_PACKET raw sockets
            RootManager.execute("setenforce 0");

            String cmd = "/data/local/tmp/netcut " + iface + " " + gateway;
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            stdin = new DataOutputStream(process.getOutputStream());
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

            executor = Executors.newSingleThreadExecutor();
            executor.submit(this::readLoop);
            isRunning = true;
            Log.i(TAG, "Rust binary started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start binary", e);
            isRunning = false;
        }
    }

    public void stop() {
        isRunning = false;
        try {
            sendCommand(createCommand("quit"));
            if (process != null) {
                process.waitFor();
                process.destroy();
            }
            if (executor != null) executor.shutdownNow();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping bridge", e);
        }
    }

    public boolean isRunning() { return isRunning; }

    public void syncTargets(List<Device> targets) {
        try {
            JSONObject cmd = createCommand("sync");
            JSONArray arr = new JSONArray();
            for (Device d : targets) {
                JSONObject t = new JSONObject();
                t.put("ip", d.getIp());
                t.put("mac", d.getMac());
                arr.put(t);
            }
            cmd.put("targets", arr);
            sendCommand(cmd);
        } catch (Exception e) {
            Log.e(TAG, "Sync failed", e);
        }
    }

    public void pingBinary() {
        try {
            sendCommand(createCommand("ping"));
        } catch (Exception e) {
            Log.e(TAG, "Ping failed", e);
        }
    }

    private JSONObject createCommand(String command) throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("protocol", 1);
        cmd.put("id", idCounter.getAndIncrement());
        cmd.put("command", command);
        return cmd;
    }

    private void sendCommand(JSONObject cmd) {
        try {
            String jsonStr = cmd.toString() + "\n";
            stdin.write(jsonStr.getBytes());
            stdin.flush();
            Log.d(TAG, "Sent: " + jsonStr.trim());
        } catch (Exception e) {
            Log.e(TAG, "Write to stdin failed", e);
        }
    }

    private void readLoop() {
        try {
            String line;
            while (isRunning && (line = stdout.readLine()) != null) {
                Log.d(TAG, "Received: " + line);
                try {
                    JSONObject json = new JSONObject(line);
                    String event = json.optString("event");
                    if (listener != null) {
                        listener.onEvent(event, json);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Non-JSON output: " + line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Read loop crashed", e);
        } finally {
            isRunning = false;
        }
    }
}