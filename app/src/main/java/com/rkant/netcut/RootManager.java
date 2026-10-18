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


    public static String execute(String command, long timeoutMs) {
        final StringBuilder output = new StringBuilder();
        final StringBuilder errOut = new StringBuilder();
        Process p = null;
        Thread outReader = null;
        Thread errReader = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());

            final Process proc = p;
            outReader = new Thread(() -> drain(proc.getInputStream(), output));
            errReader = new Thread(() -> drain(proc.getErrorStream(), errOut));
            outReader.setDaemon(true);
            errReader.setDaemon(true);
            outReader.start();
            errReader.start();

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
                // Destroying the process closes its streams, which will
                // unblock the reader threads (read() returns -1/throws).
                joinQuietly(outReader, 1000);
                joinQuietly(errReader, 1000);
                return "TIMEOUT";
            }

            // The process has exited; give the reader threads a bounded
            // window to drain whatever is left in the pipes, then move on.
            joinQuietly(outReader, 2000);
            joinQuietly(errReader, 2000);

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

    /** Reads lines from {@code in} into {@code sink} until EOF or the stream errors/closes. */
    private static void drain(java.io.InputStream in, StringBuilder sink) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append("\n");
                }
            }
        } catch (Exception ignored) {
            // Stream closed/destroyed underneath us (e.g. on timeout) — nothing more to read.
        }
    }

    private static void joinQuietly(Thread t, long millis) {
        if (t == null) return;
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
