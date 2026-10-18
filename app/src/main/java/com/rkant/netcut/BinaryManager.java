package com.rkant.netcut;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class BinaryManager {
    private static final String TAG = "BinaryManager";
    private static final String BINARY_NAME_ARM64 = "netcut_arm64";
    private static final String BINARY_NAME_ARMEABI = "netcut_armeabi";
    private static final String PREFS_NAME = "binary_manager_prefs";
    private static final String KEY_EXTRACTED_VERSION = "last_extracted_version_code";
    public static final String TMP_BINARY_PATH = "/data/local/tmp/netcut_engine";
    private final Context context;

    public BinaryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Prepares the native binary for execution.
     * Returns the absolute path to the binary, or null if preparation failed.
     */
    public String prepareBinary() {
        try {
            String arch = detectArchitecture();
            if (arch == null) {
                Log.e(TAG, "Unsupported CPU architecture");
                return null;
            }
            Log.i(TAG, "Detected architecture: " + arch);

            int rawResId = getRawResourceId(arch);
            String binaryFileName = getBinaryFileName(arch);
            if (rawResId == 0) {
                Log.e(TAG, "Raw resource not found for architecture: " + arch);
                return null;
            }

            File binDir = context.getDir("bin", Context.MODE_PRIVATE);
            File targetFile = new File(binDir, binaryFileName);

            boolean needsReExtraction = false;
            if (hasAppBeenUpdated()) {
                Log.i(TAG, "App version changed. Forcing binary re-extraction...");
                needsReExtraction = true;
                deleteAllBinaries(binDir);
            }

            if (needsReExtraction || !isBinaryValid(targetFile, rawResId)) {
                Log.i(TAG, "Extracting binary from raw resources...");
                if (!extractBinary(rawResId, targetFile)) {
                    Log.e(TAG, "Failed to extract binary");
                    return null;
                }
                saveExtractedVersion(getCurrentVersionCode());
            } else {
                Log.i(TAG, "Binary already exists and is valid.");
            }

            if (!ensureExecutable(targetFile)) {
                Log.e(TAG, "Failed to set execute permission");
                return null;
            }

            if (!targetFile.exists() || !targetFile.canExecute()) {
                Log.e(TAG, "Binary preparation failed final verification");
                return null;
            }

            Log.i(TAG, "Binary ready: " + targetFile.getAbsolutePath());
            return targetFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Binary preparation failed", e);
            return null;
        }
    }

    public String detectArchitecture() {
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        if (supportedAbis == null || supportedAbis.length == 0) return null;

        String preferred = supportedAbis[0];
        if ("arm64-v8a".equals(preferred)) return "arm64";
        if ("armeabi-v7a".equals(preferred) || "armeabi".equals(preferred)) return "armeabi";

        for (String abi : supportedAbis) {
            if ("arm64-v8a".equals(abi)) return "arm64";
            if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi)) return "armeabi";
        }
        return null;
    }

    /**
     * Kills any existing netcut processes before starting a new one.
     */


    // ========================================================================
    // Internal methods (same as before with minor improvements)
    // ========================================================================

    private int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private boolean hasAppBeenUpdated() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int lastExtractedVersion = prefs.getInt(KEY_EXTRACTED_VERSION, -1);
        int currentVersion = getCurrentVersionCode();
        if (lastExtractedVersion == -1) return true;
        return lastExtractedVersion != currentVersion;
    }

    private void saveExtractedVersion(int versionCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_EXTRACTED_VERSION, versionCode).apply();
    }

    private void deleteAllBinaries(File binDir) {
        File[] files = binDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private int getRawResourceId(String arch) {
        String resourceName = "arm64".equals(arch) ? BINARY_NAME_ARM64 : BINARY_NAME_ARMEABI;
        return context.getResources().getIdentifier(resourceName, "raw", context.getPackageName());
    }

    private String getBinaryFileName(String arch) {
        return "arm64".equals(arch) ? BINARY_NAME_ARM64 : BINARY_NAME_ARMEABI;
    }

    private boolean isBinaryValid(File file, int rawResId) {
        if (!file.exists() || !file.isFile() || file.length() == 0) return false;
        try {
            long expectedSize = getRawResourceSize(rawResId);
            if (expectedSize > 0 && file.length() != expectedSize) return false;
        } catch (Exception e) {
            // Assume valid if we can't check
        }
        return true;
    }

    private long getRawResourceSize(int rawResId) {
        InputStream is = null;
        try {
            is = context.getResources().openRawResource(rawResId);
            return is.available(); // Quick size check
        } catch (Exception e) {
            return -1;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    private boolean extractBinary(int rawResId, File targetFile) {
        File tempFile = new File(targetFile.getAbsolutePath() + ".tmp");
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            is = context.getResources().openRawResource(rawResId);
            fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();
            fos.getFD().sync();
            fos.close();
            fos = null;

            if (targetFile.exists()) targetFile.delete();
            boolean renamed = tempFile.renameTo(targetFile);
            if (!renamed) {
                copyFile(tempFile, targetFile);
                tempFile.delete();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed", e);
            if (tempFile.exists()) tempFile.delete();
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            out.getFD().sync();
        } finally {
            in.close();
            out.close();
        }
    }

    private boolean ensureExecutable(File file) {
        if (file.canExecute()) return true;

        boolean javaResult = file.setExecutable(true, false);
        if (javaResult && file.canExecute()) return true;

        try {
            Process chmodProcess = Runtime.getRuntime().exec(
                    new String[]{"chmod", "755", file.getAbsolutePath()});
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                chmodProcess.waitFor(3, TimeUnit.SECONDS);
            }
            if (file.canExecute()) return true;
        } catch (Exception ignored) {}

        try {
            RootManager.execute("chmod 755 " + file.getAbsolutePath(), 3000);
            if (file.canExecute()) return true;
        } catch (Exception ignored) {}

        return file.canExecute();
    }

    /**
     * Kills any existing netcut processes before starting a new one.
     */
    public void killExistingProcesses() {
        String script =
                "pids=$(pidof netcut_engine 2>/dev/null); " +
                        "[ -n \"$pids\" ] && kill -15 $pids 2>/dev/null; " +
                        "sleep 1; " +
                        "pids=$(pidof netcut_engine 2>/dev/null); " +
                        "[ -n \"$pids\" ] && kill -9 $pids 2>/dev/null";
        RootManager.execute(script, 5000);
    }
    // ========================================================================
    // Repair (Settings → Troubleshooting)
    // ========================================================================

    /** Simple result holder for {@link #repairBinary()}. */
    public static class RepairResult {
        public final boolean success;
        public final String message;

        RepairResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }


    public RepairResult repairBinary() {
        try {
            Log.i(TAG, "=== Binary repair started ===");
            SessionLogManager.getInstance().log("Binary repair started");

            if (!RootManager.isRooted()) {
                SessionLogManager.getInstance().log("Binary repair failed: no root");
                return new RepairResult(false,
                        "Root access is not available. Grant root to Netcut and try again.");
            }

            String arch = detectArchitecture();
            if (arch == null) {
                return new RepairResult(false,
                        "Unsupported CPU architecture. Cannot repair.");
            }

            // 1. Stop anything currently running the binary.
            killExistingProcesses();

            // 2. Remove the staged copy in /data/local/tmp.
            RootManager.execute("rm -f '" + TMP_BINARY_PATH + "'", 3000);
            String check = RootManager.execute(
                    "[ -e '" + TMP_BINARY_PATH + "' ] && echo EXISTS || echo GONE", 3000);
            boolean tmpRemoved = check != null && check.contains("GONE");
            if (tmpRemoved) {
                Log.i(TAG, "Old /data/local/tmp binary removed");
                SessionLogManager.getInstance().log("Old engine binary deleted from /data/local/tmp");
            } else {
                Log.w(TAG, "Could not confirm removal of /data/local/tmp binary");
            }

            // 3. Wipe app-private binaries and force re-extraction.
            File binDir = context.getDir("bin", Context.MODE_PRIVATE);
            deleteAllBinaries(binDir);
            saveExtractedVersion(-1);

            // 4. Fresh extraction from raw resources (size-validated).
            String binaryPath = prepareBinary();
            if (binaryPath == null) {
                SessionLogManager.getInstance().log("Binary repair failed: re-extraction error");
                return new RepairResult(false,
                        "Could not re-extract the binary from app resources. Try reinstalling the app.");
            }
            SessionLogManager.getInstance().log("Fresh engine binary extracted");

            // 5. Re-apply SELinux policy needed for the raw socket.
            String policyBackend = RootPolicyManager.grantRawSocketPolicy();
            Log.i(TAG, "SELinux policy re-applied via: " + policyBackend);

            // 6. Re-stage a fresh copy where RustBridge expects it.
            boolean staged = stageTmpBinary(binaryPath);

            // 7. Final verification of the app-private binary.
            File fresh = new File(binaryPath);
            if (!fresh.exists() || !fresh.canExecute()) {
                SessionLogManager.getInstance().log("Binary repair failed: verification error");
                return new RepairResult(false,
                        "Repair ran but the binary failed final verification. Please try again.");
            }

            StringBuilder msg = new StringBuilder("Engine binary repaired successfully.");
            if (!tmpRemoved) {
                msg.append(" The old /data/local/tmp copy could not be verified as deleted, but a fresh copy was staged.");
            }
            if (!staged) {
                msg.append(" Staging to /data/local/tmp will be retried automatically on next start.");
            }
            SessionLogManager.getInstance().log("Binary repair completed");
            Log.i(TAG, "=== Binary repair finished ===");
            return new RepairResult(true, msg.toString());
        } catch (Exception e) {
            Log.e(TAG, "Binary repair failed", e);
            SessionLogManager.getInstance().log("Binary repair failed: " + e.getMessage());
            return new RepairResult(false, "Repair failed: " + e.getMessage());
        }
    }


    private boolean stageTmpBinary(String binaryPath) {
        String script =
                "rm -f '" + TMP_BINARY_PATH + "' 2>/dev/null; " +
                        "cp -f '" + binaryPath + "' '" + TMP_BINARY_PATH + "' && " +
                        "chmod 755 '" + TMP_BINARY_PATH + "' && " +
                        "chcon u:object_r:system_file:s0 '" + TMP_BINARY_PATH + "' 2>/dev/null; " +
                        "chcon u:object_r:magisk_file:s0 '" + TMP_BINARY_PATH + "' 2>/dev/null; " +
                        "chcon u:object_r:shell_exec:s0 '" + TMP_BINARY_PATH + "' 2>/dev/null; " +
                        "[ -x '" + TMP_BINARY_PATH + "' ] && echo STAGED";
        String out = RootManager.execute(script, 5000);
        return out != null && out.contains("STAGED");
    }
}