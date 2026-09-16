package com.rkant.netcut;

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

    /**
     * Starts the Rust binary.
     * @param binaryPath Absolute path to the extracted netcut binary
     * @param iface Network interface (e.g., wlan0)
     * @param gateway Gateway IP address
     * @param listener Event listener for JSON responses
     */
    public void start(String binaryPath, String iface, String gateway, BridgeEventListener listener) {
        this.listener = listener;
        try {
            // SELinux bypass for AF_PACKET raw sockets
            RootManager.execute("setenforce 0");

            // ✅ Use the provided binary path instead of hardcoded /data/local/tmp/netcut
            String cmd = binaryPath + " " + iface + " " + gateway;
            Log.i(TAG, "Starting binary: " + cmd);

            process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            stdin = new DataOutputStream(process.getOutputStream());
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

            executor = Executors.newSingleThreadExecutor();
            executor.submit(this::readLoop);
            isRunning = true;
            Log.i(TAG, "Rust binary started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start binary", e);
            isRunning = false;
        }
    }

    public void stop() {
        if (!isRunning && process == null && executor == null) return;

        isRunning = false;

        try {
            // First ask the engine to restore all banned targets.
            restoreAllTargets();

            // Then ask it to quit.
            sendCommand(createCommand("quit"));

            // Give the native binary a small window to process restore/quit.
            // This is important for instant internet restoration.
            Thread.sleep(200);
        } catch (Exception ignored) {
        }

        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Exception ignored) {
        }

        if (executor != null) {
            executor.shutdownNow();
        }

        if (process != null) {
            try {
                process.destroy();

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    boolean exited = process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!exited) {
                        process.destroyForcibly();
                    }
                }
            } catch (Exception ignored) {
            }
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
    public void restoreAllTargets() {
        try {
            JSONObject cmd = createCommand("sync");
            cmd.put("targets", new JSONArray());
            sendCommand(cmd);
        } catch (Exception e) {
            Log.e(TAG, "Restore all targets failed", e);
        }
    }
}
