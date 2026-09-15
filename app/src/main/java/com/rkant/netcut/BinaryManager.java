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

    // Raw resource names (must match files in res/raw/)
    private static final String BINARY_NAME_ARM64 = "netcut_arm64";
    private static final String BINARY_NAME_ARMEABI = "netcut_armeabi";

    // SharedPreferences keys for version tracking
    private static final String PREFS_NAME = "binary_manager_prefs";
    private static final String KEY_EXTRACTED_VERSION = "last_extracted_version_code";

    private final Context context;

    public BinaryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Prepares the native binary for execution.
     * Handles: fresh install, app updates, corrupted files, permission issues.
     * Returns the absolute path to the binary, or null if preparation failed.
     */
    public String prepareBinary() {
        try {
            // Step 1: Detect architecture
            String arch = detectArchitecture();
            if (arch == null) {
                Log.e(TAG, "Unsupported CPU architecture. Supported: arm64-v8a, armeabi-v7a");
                return null;
            }
            Log.i(TAG, "Detected architecture: " + arch);

            // Step 2: Determine resource ID and target filename
            int rawResId = getRawResourceId(arch);
            String binaryFileName = getBinaryFileName(arch);

            if (rawResId == 0) {
                Log.e(TAG, "Raw resource not found for architecture: " + arch);
                return null;
            }

            // Step 3: Determine target path in app's private directory
            File binDir = context.getDir("bin", Context.MODE_PRIVATE);
            File targetFile = new File(binDir, binaryFileName);

            Log.i(TAG, "Binary target path: " + targetFile.getAbsolutePath());

            // Step 4: ✅ Check if app was updated (version mismatch)
            boolean needsReExtraction = false;
            if (hasAppBeenUpdated()) {
                Log.i(TAG, "App version changed. Forcing binary re-extraction...");
                needsReExtraction = true;
                deleteAllBinaries(binDir); // Clean up old binaries
            }

            // Step 5: Validate existing binary or extract fresh
            if (needsReExtraction || !isBinaryValid(targetFile, rawResId)) {
                Log.i(TAG, "Binary missing, outdated, or invalid. Extracting from raw resources...");
                if (!extractBinary(rawResId, targetFile)) {
                    Log.e(TAG, "Failed to extract binary");
                    return null;
                }
                // Save the current version so we know extraction succeeded for this version
                saveExtractedVersion(getCurrentVersionCode());
            } else {
                Log.i(TAG, "Binary already exists and matches current version. Skipping extraction.");
            }

            // Step 6: Ensure execute permission
            if (!ensureExecutable(targetFile)) {
                Log.e(TAG, "Failed to set execute permission on binary");
                return null;
            }

            // Step 7: Final verification
            if (!targetFile.exists() || !targetFile.canExecute()) {
                Log.e(TAG, "Binary preparation failed final verification");
                return null;
            }

            Log.i(TAG, "Binary ready: " + targetFile.getAbsolutePath());
            return targetFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Binary preparation failed with exception", e);
            return null;
        }
    }

    // ========================================================================
    // ✅ VERSION TRACKING (Handles app updates)
    // ========================================================================

    /**
     * Returns the current app's versionCode from the APK.
     */
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
            Log.e(TAG, "Could not get version code", e);
            return -1;
        }
    }

    /**
     * Checks if the app has been updated since the binary was last extracted.
     * Returns true if re-extraction is needed.
     */
    private boolean hasAppBeenUpdated() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int lastExtractedVersion = prefs.getInt(KEY_EXTRACTED_VERSION, -1);
        int currentVersion = getCurrentVersionCode();

        Log.d(TAG, "Version check: last_extracted=" + lastExtractedVersion + ", current=" + currentVersion);

        // If no version was saved, this is a fresh install → need extraction
        if (lastExtractedVersion == -1) return true;

        // If versions differ, app was updated → need re-extraction
        return lastExtractedVersion != currentVersion;
    }

    /**
     * Saves the version code after successful extraction.
     */
    private void saveExtractedVersion(int versionCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_EXTRACTED_VERSION, versionCode).apply();
        Log.i(TAG, "Saved extracted version: " + versionCode);
    }

    /**
     * Deletes all binaries in the bin directory (used during app updates).
     */
    private void deleteAllBinaries(File binDir) {
        File[] files = binDir.listFiles();
        if (files != null) {
            for (File f : files) {
                boolean deleted = f.delete();
                Log.d(TAG, "Deleted old binary: " + f.getName() + " → " + deleted);
            }
        }
    }

    // ========================================================================
    // Architecture Detection
    // ========================================================================

    /**
     * Returns the detected architecture string, or null if unsupported.
     */
    public String detectArchitecture() {
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        if (supportedAbis == null || supportedAbis.length == 0) {
            return null;
        }

        // Check preferred ABI first
        String preferred = supportedAbis[0];
        if ("arm64-v8a".equals(preferred)) return "arm64";
        if ("armeabi-v7a".equals(preferred) || "armeabi".equals(preferred)) return "armeabi";

        // Fallback: check all supported ABIs
        for (String abi : supportedAbis) {
            if ("arm64-v8a".equals(abi)) return "arm64";
            if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi)) return "armeabi";
        }

        return null; // Unsupported (x86, x86_64, etc.)
    }

    // ========================================================================
    // Resource Helpers
    // ========================================================================

    private int getRawResourceId(String arch) {
        String resourceName = "arm64".equals(arch) ? BINARY_NAME_ARM64 : BINARY_NAME_ARMEABI;
        return context.getResources().getIdentifier(resourceName, "raw", context.getPackageName());
    }

    private String getBinaryFileName(String arch) {
        return "arm64".equals(arch) ? BINARY_NAME_ARM64 : BINARY_NAME_ARMEABI;
    }

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Validates that the binary file exists, has correct size, and is not corrupted.
     */
    private boolean isBinaryValid(File file, int rawResId) {
        if (!file.exists()) {
            Log.d(TAG, "Binary does not exist");
            return false;
        }

        if (!file.isFile()) {
            Log.d(TAG, "Binary path is not a regular file");
            return false;
        }

        if (file.length() == 0) {
            Log.d(TAG, "Binary file is empty");
            return false;
        }

        // Compare size against raw resource to detect corruption
        try {
            long expectedSize = getRawResourceSize(rawResId);
            if (expectedSize > 0 && file.length() != expectedSize) {
                Log.d(TAG, "Binary size mismatch. Expected: " + expectedSize + ", Found: " + file.length());
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not verify binary size, assuming valid", e);
        }

        return true;
    }

    private long getRawResourceSize(int rawResId) {
        InputStream is = null;
        try {
            is = context.getResources().openRawResource(rawResId);
            long size = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                size += bytesRead;
            }
            return size;
        } catch (Exception e) {
            return -1;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ========================================================================
    // Extraction
    // ========================================================================

    /**
     * Extracts the binary from res/raw to the target file.
     * Uses atomic write (write to temp, then rename) to prevent corruption.
     */
    private boolean extractBinary(int rawResId, File targetFile) {
        File tempFile = new File(targetFile.getAbsolutePath() + ".tmp");
        InputStream is = null;
        FileOutputStream fos = null;

        try {
            is = context.getResources().openRawResource(rawResId);
            fos = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            fos.flush();
            fos.getFD().sync(); // Force write to disk
            fos.close();
            fos = null;

            // Atomic rename: prevents partial/corrupted binary
            if (targetFile.exists()) {
                targetFile.delete();
            }

            boolean renamed = tempFile.renameTo(targetFile);
            if (!renamed) {
                Log.w(TAG, "Rename failed, using copy fallback");
                copyFile(tempFile, targetFile);
                tempFile.delete();
            }

            Log.i(TAG, "Binary extracted successfully. Size: " + totalBytes + " bytes");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Extraction failed", e);
            if (tempFile.exists()) tempFile.delete();
            if (targetFile.exists()) targetFile.delete();
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

    // ========================================================================
    // Permissions
    // ========================================================================

    /**
     * Ensures the file has execute permission.
     * Tries multiple methods for reliability.
     */
    private boolean ensureExecutable(File file) {
        if (file.canExecute()) {
            Log.d(TAG, "Binary already has execute permission");
            return true;
        }

        // Method 1: Java's setExecutable (works on app's private directory)
        boolean javaResult = file.setExecutable(true, false);
        if (javaResult && file.canExecute()) {
            Log.i(TAG, "Execute permission set via File.setExecutable()");
            return true;
        }

        // Method 2: chmod via shell (fallback)
        try {
            Process chmodProcess = Runtime.getRuntime().exec(
                    new String[]{"chmod", "755", file.getAbsolutePath()});
            int exitCode = chmodProcess.waitFor();
            if (exitCode == 0 && file.canExecute()) {
                Log.i(TAG, "Execute permission set via chmod 755");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "chmod fallback failed", e);
        }

        // Method 3: chmod via root (last resort)
        try {
            RootManager.execute("chmod 755 " + file.getAbsolutePath());
            if (file.canExecute()) {
                Log.i(TAG, "Execute permission set via root chmod");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "root chmod fallback failed", e);
        }

        return file.canExecute();
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Deletes the extracted binary (useful for forced re-extraction).
     */
    public void deleteBinary() {
        try {
            File binDir = context.getDir("bin", Context.MODE_PRIVATE);
            deleteAllBinaries(binDir);
            // Also reset the version tracker
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_EXTRACTED_VERSION).apply();
            Log.i(TAG, "All binaries and version tracker cleared");
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete binaries", e);
        }
    }

    /**
     * Returns info about the current binary state (for debugging/UI).
     */
    public String getBinaryInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Architecture: ").append(detectArchitecture()).append("\n");
        sb.append("Supported ABIs: ").append(java.util.Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        sb.append("App versionCode: ").append(getCurrentVersionCode()).append("\n");

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sb.append("Last extracted version: ").append(prefs.getInt(KEY_EXTRACTED_VERSION, -1)).append("\n");

        File binDir = context.getDir("bin", Context.MODE_PRIVATE);
        sb.append("Binary dir: ").append(binDir.getAbsolutePath()).append("\n");

        File[] files = binDir.listFiles();
        if (files != null && files.length > 0) {
            for (File f : files) {
                sb.append("  - ").append(f.getName())
                        .append(" (").append(f.length()).append(" bytes, exec=")
                        .append(f.canExecute()).append(")\n");
            }
        } else {
            sb.append("  (no binaries extracted)\n");
        }

        return sb.toString();
    }
}
