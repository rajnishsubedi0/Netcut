package com.rkant.netcut;

import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class RootManager {
    private static final String TAG = "RootManager";
    private static final long DEFAULT_TIMEOUT_MS = 10000; // 10 seconds

    public static boolean isRooted() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Executes a root command with timeout.
     * Returns output or empty string on failure/timeout.
     */
    public static String execute(String command) {
        return execute(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Executes a root command with specified timeout.
     */
    public static String execute(String command, long timeoutMs) {
        StringBuilder output = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader errBr = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                Log.w(TAG, "Root command timed out: " + command.substring(0, Math.min(50, command.length())));
                p.destroyForcibly();
                return "";
            }

            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Read stderr for debugging
            StringBuilder errOutput = new StringBuilder();
            while ((line = errBr.readLine()) != null) {
                errOutput.append(line).append("\n");
            }
            if (errOutput.length() > 0) {
                Log.d(TAG, "Root stderr: " + errOutput.toString().trim());
            }

        } catch (Exception e) {
            Log.e(TAG, "Root execution failed: " + e.getMessage());
        } finally {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
        return output.toString();
    }

    /**
     * Checks if SELinux is enforcing.
     */
    public static boolean isSelinuxEnforcing() {
        String result = execute("getenforce", 3000);
        return result.trim().equalsIgnoreCase("Enforcing");
    }

    /**
     * Attempts to set SELinux to permissive. Returns true if successful.
     */
    public static boolean setSelinuxPermissive() {
        execute("setenforce 0", 3000);
        return !isSelinuxEnforcing();
    }
}