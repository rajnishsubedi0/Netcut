package com.rkant.netcut;

import android.os.Build;
import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class RootManager {
    private static final String TAG = "RootManager";
    private static final long DEFAULT_TIMEOUT_MS = 10000;

    public static boolean isRooted() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            boolean finished = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                finished = p.waitFor(5, TimeUnit.SECONDS);
            }
            if (!finished) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly();
                else p.destroy();
                return false;
            }
            return line != null && line.contains("uid=0");
        } catch (Exception e) { return false; }
    }

    public static String execute(String command) {
        return execute(command, DEFAULT_TIMEOUT_MS);
    }

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

            boolean finished = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    try {
                        p.exitValue();
                        finished = true;
                        break;
                    } catch (IllegalThreadStateException e) {
                        Thread.sleep(100);
                    }
                }
            }

            if (!finished) {
                Log.w(TAG, "Root command timed out");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly();
                else p.destroy();
                return "TIMEOUT";
            }

            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }

            StringBuilder errOut = new StringBuilder();
            while ((line = errBr.readLine()) != null) {
                errOut.append(line).append("\n");
            }

            // ✅ FIX: Append stderr to output so we can catch ping errors
            output.append(errOut);

        } catch (Exception e) {
            Log.e(TAG, "Root execution failed: " + e.getMessage());
        } finally {
            if (p != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly();
                    else p.destroy();
                } catch (Exception ignored) {}
            }
        }
        return output.toString();
    }
}