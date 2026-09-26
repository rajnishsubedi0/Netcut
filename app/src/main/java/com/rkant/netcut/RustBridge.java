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
     */
    public boolean startAndWait(String binaryPath, String iface, String gateway,
                                BridgeEventListener listener, long timeoutMs) {
        startLatch = new CountDownLatch(1);
        start(binaryPath, iface, gateway, listener);
        try {
            boolean started = startLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!started) {
                Log.e(TAG, "Engine start timeout after " + timeoutMs + "ms");
                forceCleanup();
                return false;
            }
            return isRunning;
        } catch (InterruptedException e) { return false; }
    }

    public void start(String binaryPath, String iface, String gateway, BridgeEventListener listener) {
        this.listener = listener;
        try {

            // Grant the raw-socket SELinux permissions across Magisk / APatch
            // (magiskpolicy) and KernelSU (ksud), without setting SELinux
            // globally permissive.
            String policyBackend = RootPolicyManager.grantRawSocketPolicy();
            Log.i(TAG, "SELinux policy backend: " + policyBackend);

            String tmpBinPath = "/data/local/tmp/netcut_engine";
            String setupScript =
                    "cp -f '" + binaryPath + "' '" + tmpBinPath + "' 2>/dev/null && " +
                            "chmod 755 '" + tmpBinPath + "' && " +
                            "chcon u:object_r:system_file:s0 '" + tmpBinPath + "' 2>/dev/null; " +
                            "chcon u:object_r:magisk_file:s0 '" + tmpBinPath + "' 2>/dev/null; " +
                            "chcon u:object_r:shell_exec:s0 '" + tmpBinPath + "' 2>/dev/null; " +
                            "echo 'READY'";

            String setupResult = RootManager.execute(setupScript, 5000);
            if (setupResult == null || !setupResult.contains("READY")) {
                Log.w(TAG, "Failed to copy binary to /data/local/tmp, falling back to app dir.");
                tmpBinPath = binaryPath;
            }

            String cmd = "exec '" + tmpBinPath + "' '" + iface + "' '" + gateway + "'";
            Log.i(TAG, "Starting binary in Enforcing mode: " + cmd);

            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
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
     * ✅ CRITICAL FIX: Graceful stop with proper wait for restore.
     * Waits up to restoreTimeoutMs for RESTORE_COMPLETED, then exitTimeoutMs for exit.
     */
    public boolean stopAndWait(long restoreTimeoutMs, long exitTimeoutMs) {
        if (process == null && !isRunning) {
            forceCleanup();
            return true;
        }

        isStopping = true;
        Log.i(TAG, "stopAndWait: restoreTimeout=" + restoreTimeoutMs + "ms, exitTimeout=" + exitTimeoutMs + "ms");

        try {
            // Send restore_and_quit command
            stopLatch = new CountDownLatch(1);
            JSONObject cmd = createCommand("restore_and_quit");
            pendingStopId = cmd.getInt("id");
            sendCommand(cmd);
            Log.i(TAG, "Sent restore_and_quit, id=" + pendingStopId);

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
            forceCleanup();
        }

        boolean exited = !isProcessAlive();
        Log.i(TAG, "stopAndWait complete. exited=" + exited);
        return exited;
    }

    public void stop() {
        stopAndWait(6000, 3000);
    }

    public boolean isRunning() { return isRunning; }

    public void syncTargets(List<Device> targets) {
        if (!isRunning || isStopping) return;
        try {
            JSONObject cmd = createCommand("sync");
            JSONArray arr = new JSONArray();
            for (Device d : targets) {
                if (!Device.isValidIpv4(d.getIp()) || !Device.isValidMac(d.getMac())) continue;
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
        if (!isRunning || isStopping) return;
        try { sendCommand(createCommand("ping")); } catch (Exception e) {
            Log.e(TAG, "Ping failed", e);
        }
    }

    // ========================================================================
    // Internal
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
                    if (listener != null) listener.onEvent(event, json);
                } catch (Exception e) {
                    Log.w(TAG, "Non-JSON output: " + line);
                }
            }
        } catch (Exception e) {
            if (isRunning) Log.e(TAG, "Read loop crashed", e);
        } finally {
            isRunning = false;
            if (listener != null) listener.onBridgeExited();
        }
    }

    private void handleProtocolEvent(JSONObject json) {
        long id = json.optLong("id", -1);
        String event = json.optString("event", "");

        if ("SERVICE_STARTED".equals(event) && startLatch != null) {
            startLatch.countDown();
        }

        if (pendingStopId >= 0 && id == pendingStopId) {
            if ("RESTORE_COMPLETED".equals(event) || "SYNC_COMPLETED".equals(event) || "SUCCESS".equals(event)) {
                if (stopLatch != null) stopLatch.countDown();
            }
        }
    }

    private void destroyProcessIfAlive() {
        if (process != null && isProcessAlive()) {
            try {
                process.destroy();
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    boolean exited = process.waitFor(2000, TimeUnit.MILLISECONDS);
                    if (!exited) process.destroyForcibly();
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isProcessAlive() {
        if (process == null) return false;
        try { process.exitValue(); return false; }
        catch (IllegalThreadStateException e) { return true; }
    }

    private void forceCleanup() {
        try { if (stdin != null) stdin.close(); } catch (Exception ignored) {}
        try { if (stdout != null) stdout.close(); } catch (Exception ignored) {}
        if (executor != null) { executor.shutdownNow(); executor = null; }
        process = null; stdin = null; stdout = null;
    }
}