package com.rkant.netcut;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RustBridge {
    private static final String TAG = "RustBridge";

    private Process process;
    private DataOutputStream stdin;
    private BufferedReader stdout;
    private ExecutorService executor;
    private BridgeEventListener listener;
    private AtomicInteger idCounter = new AtomicInteger(1);

    private volatile boolean isRunning = false;
    private volatile boolean isStopping = false;

    // For waiting on restore completion
    private volatile int pendingStopId = -1;
    private CountDownLatch stopLatch;

    // For waiting on startup
    private CountDownLatch startLatch;

    public interface BridgeEventListener {
        void onEvent(String event, JSONObject data);
        void onBridgeExited();
    }

    /**
     * Starts the Rust binary and waits for SERVICE_STARTED event.
     * Returns true if engine started successfully within timeout.
     */
    public boolean startAndWait(String binaryPath, String iface, String gateway,
                                BridgeEventListener listener, long timeoutMs) {
        startLatch = new CountDownLatch(1);
        start(binaryPath, iface, gateway, listener);

        try {
            boolean started = startLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!started) {
                Log.e(TAG, "Engine start timeout after " + timeoutMs + "ms");
                stop();
                return false;
            }
            return isRunning;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * Starts the Rust binary (non-blocking).
     */
    public void start(String binaryPath, String iface, String gateway, BridgeEventListener listener) {
        this.listener = listener;
        try {
            // SELinux handling
            if (RootManager.isSelinuxEnforcing()) {
                Log.w(TAG, "SELinux is enforcing. Attempting to set permissive...");
                RootManager.setSelinuxPermissive();
            }

            // Use exec to replace shell with binary (better signal handling)
            String cmd = "exec '" + binaryPath + "' '" + iface + "' '" + gateway + "'";
            Log.i(TAG, "Starting binary: " + cmd);

            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true); // Merge stderr into stdout for logging
            process = pb.start();

            stdin = new DataOutputStream(process.getOutputStream());
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            executor = Executors.newSingleThreadExecutor();
            executor.submit(this::readLoop);
            isRunning = true;
            Log.i(TAG, "Rust binary process spawned");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start binary", e);
            isRunning = false;
        }
    }

    /**
     * Graceful stop with restore. Waits for RESTORE_COMPLETED before killing.
     * Returns true if process exited cleanly.
     */
    public boolean stopAndWait(long restoreTimeoutMs, long exitTimeoutMs) {
        if (process == null && !isRunning) {
            cleanup();
            return true;
        }

        isStopping = true;

        try {
            // Send restore_and_quit command
            stopLatch = new CountDownLatch(1);
            JSONObject cmd = createCommand("restore_and_quit");
            pendingStopId = cmd.getInt("id");
            sendCommand(cmd);

            // Wait for restore completion
            boolean restored = stopLatch.await(restoreTimeoutMs, TimeUnit.MILLISECONDS);
            if (!restored) {
                Log.w(TAG, "Restore timeout. Sending quit fallback.");
                sendCommand(createCommand("quit"));
            }

            // Wait for process to exit
            if (process != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    process.waitFor(exitTimeoutMs, TimeUnit.MILLISECONDS);
                } else {
                    Thread.sleep(exitTimeoutMs);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "stopAndWait failed", e);
        } finally {
            isRunning = false;
            isStopping = false;
            destroyProcessIfAlive();
            cleanup();
        }

        return !isProcessAlive();
    }

    /**
     * Legacy stop method (kept for compatibility but delegates to stopAndWait).
     */
    public void stop() {
        stopAndWait(5000, 3000);
    }

    public boolean isRunning() { return isRunning; }

    /**
     * Syncs target list to the native engine.
     */
    public void syncTargets(List<Device> targets) {
        if (!isRunning || isStopping) return;
        try {
            JSONObject cmd = createCommand("sync");
            JSONArray arr = new JSONArray();
            for (Device d : targets) {
                if (!Device.isValidIpv4(d.getIp()) || !Device.isValidMac(d.getMac())) {
                    continue; // Skip invalid entries
                }
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

    /**
     * Sends a ping to keep the bridge alive.
     */
    public void pingBinary() {
        if (!isRunning || isStopping) return;
        try {
            sendCommand(createCommand("ping"));
        } catch (Exception e) {
            Log.e(TAG, "Ping failed", e);
        }
    }

    /**
     * Requests restore of all targets without quitting.
     */
    public void restoreAllTargets() {
        if (!isRunning || isStopping) return;
        try {
            JSONObject cmd = createCommand("sync");
            cmd.put("targets", new JSONArray());
            sendCommand(cmd);
        } catch (Exception e) {
            Log.e(TAG, "Restore all targets failed", e);
        }
    }

    // ========================================================================
    // Internal methods
    // ========================================================================

    private JSONObject createCommand(String command) throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("protocol", 1);
        cmd.put("id", idCounter.getAndIncrement());
        cmd.put("command", command);
        return cmd;
    }

    private void sendCommand(JSONObject cmd) {
        try {
            if (stdin == null) return;
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
            while ((isRunning || isStopping) && (line = stdout.readLine()) != null) {
                Log.d(TAG, "Received: " + line);
                try {
                    JSONObject json = new JSONObject(line);
                    handleProtocolEvent(json);

                    String event = json.optString("event");
                    if (listener != null) {
                        listener.onEvent(event, json);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Non-JSON output: " + line);
                }
            }
        } catch (Exception e) {
            if (isRunning) {
                Log.e(TAG, "Read loop crashed", e);
            }
        } finally {
            isRunning = false;
            if (listener != null) {
                listener.onBridgeExited();
            }
        }
    }

    private void handleProtocolEvent(JSONObject json) {
        long id = json.optLong("id", -1);
        String event = json.optString("event", "");

        // Handle startup confirmation
        if ("SERVICE_STARTED".equals(event) && startLatch != null) {
            startLatch.countDown();
        }

        // Handle stop/restore confirmation
        if (pendingStopId >= 0 && id == pendingStopId) {
            if ("RESTORE_COMPLETED".equals(event) ||
                    "SYNC_COMPLETED".equals(event) ||
                    "SUCCESS".equals(event)) {
                if (stopLatch != null) {
                    stopLatch.countDown();
                }
            }
        }
    }

    private void destroyProcessIfAlive() {
        if (process != null && isProcessAlive()) {
            try {
                process.destroy();
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    boolean exited = process.waitFor(2000, TimeUnit.MILLISECONDS);
                    if (!exited) {
                        process.destroyForcibly();
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isProcessAlive() {
        if (process == null) return false;
        try {
            process.exitValue();
            return false; // If exitValue() doesn't throw, process is dead
        } catch (IllegalThreadStateException e) {
            return true; // Still running
        }
    }

    private void cleanup() {
        try { if (stdin != null) stdin.close(); } catch (Exception ignored) {}
        try { if (stdout != null) stdout.close(); } catch (Exception ignored) {}
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        process = null;
        stdin = null;
        stdout = null;
    }
}