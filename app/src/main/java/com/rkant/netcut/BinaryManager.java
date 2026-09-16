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

public class BinaryManager {
    private static final String TAG = "BinaryManager";
    private static final String BINARY_NAME_ARM64 = "netcut_arm64";
    private static final String BINARY_NAME_ARMEABI = "netcut_armeabi";
    private static final String PREFS_NAME = "binary_manager_prefs";
    private static final String KEY_EXTRACTED_VERSION = "last_extracted_version_code";

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
            chmodProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (file.canExecute()) return true;
        } catch (Exception ignored) {}

        try {
            RootManager.execute("chmod 755 " + file.getAbsolutePath(), 3000);
            if (file.canExecute()) return true;
        } catch (Exception ignored) {}

        return file.canExecute();
    }

    public void deleteBinary() {
        try {
            File binDir = context.getDir("bin", Context.MODE_PRIVATE);
            deleteAllBinaries(binDir);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_EXTRACTED_VERSION).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete binaries", e);
        }
    }
    /**
     * Kills any existing netcut processes before starting a new one.
     */
    public void killExistingProcesses() {
        String script =
                "pids=$(pidof netcut_arm64 netcut_armeabi netcut 2>/dev/null); " +
                        "[ -n \"$pids\" ] && kill -15 $pids 2>/dev/null; " +
                        "sleep 1; " +
                        "pids=$(pidof netcut_arm64 netcut_armeabi netcut 2>/dev/null); " +
                        "[ -n \"$pids\" ] && kill -9 $pids 2>/dev/null";
        RootManager.execute(script, 5000);
    }
}