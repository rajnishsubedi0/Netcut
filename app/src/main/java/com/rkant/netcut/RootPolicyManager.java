package com.rkant.netcut;

import android.util.Log;

/**
 * Grants the SELinux permissions the native ARP engine needs (a raw
 * {@code packet_socket} plus the {@code net_raw} capability) in a way that works
 * across the major root solutions, <b>without ever setting SELinux globally
 * permissive</b>:
 *
 * <ul>
 *   <li><b>Magisk</b> and <b>APatch</b> ship {@code magiskpolicy} → live-patch
 *       the policy with {@code magiskpolicy --live "<rule>"}.</li>
 *   <li><b>KernelSU</b> ships {@code ksud} → {@code ksud sepolicy patch "<rule>"},
 *       which accepts the same magiskpolicy statement syntax.</li>
 * </ul>
 *
 * The old approach ran {@code setenforce 0}, which put the whole device into
 * SELinux permissive mode and never restored it — a device-wide security
 * downgrade. This class only adds the specific rules the engine requires.
 */
public final class RootPolicyManager {

    private static final String TAG = "RootPolicyManager";

    private RootPolicyManager() {}

    /**
     * magiskpolicy statement syntax. KernelSU's {@code ksud sepolicy patch}
     * accepts the same statements. Both {@code magisk} and {@code su} domains are
     * covered because the engine may run in either depending on the root manager.
     */
    private static final String[] POLICY_STATEMENTS = {
            "allow magisk self:packet_socket { create read write bind ioctl }",
            "allow magisk self:capability net_raw",
            "allow su self:packet_socket { create read write bind ioctl }",
            "allow su self:capability net_raw",
    };

    // Where magiskpolicy may live (Magisk, and APatch bundles it too).
    private static final String[] MAGISKPOLICY_PATHS = {
            "magiskpolicy",
            "/data/adb/magisk/magiskpolicy",
            "/data/adb/ap/bin/magiskpolicy",
            "/system/bin/magiskpolicy",
    };

    // Where KernelSU's ksud may live.
    private static final String[] KSUD_PATHS = {
            "ksud",
            "/data/adb/ksud",
            "/data/adb/ksu/bin/ksud",
    };

    /**
     * Applies the raw-socket SELinux rules using whichever root solution is
     * present. SELinux is never set permissive.
     *
     * @return a short description of the backend used ("magiskpolicy" / "ksud"),
     *         or "none" when no known mechanism was found.
     */
    public static String grantRawSocketPolicy() {
        String magiskpolicy = resolve(MAGISKPOLICY_PATHS);
        if (magiskpolicy != null) {
            for (String stmt : POLICY_STATEMENTS) {
                RootManager.execute(magiskpolicy + " --live \"" + stmt + "\"", 2000);
            }
            Log.i(TAG, "Applied SELinux rules via magiskpolicy (" + magiskpolicy + ")");
            return "magiskpolicy";
        }

        String ksud = resolve(KSUD_PATHS);
        if (ksud != null) {
            for (String stmt : POLICY_STATEMENTS) {
                RootManager.execute(ksud + " sepolicy patch '" + stmt + "'", 2000);
            }
            Log.i(TAG, "Applied SELinux rules via ksud (" + ksud + ")");
            return "ksud";
        }

        Log.w(TAG, "No SELinux policy tool found (magiskpolicy/ksud). The raw "
                + "socket may be blocked under enforcing SELinux on this root solution.");
        return "none";
    }

    /** @return the first candidate that exists/is invocable as root, or null. */
    private static String resolve(String[] candidates) {
        for (String c : candidates) {
            String probe = c.startsWith("/")
                    ? "[ -x '" + c + "' ] && echo FOUND"
                    : "command -v " + c + " >/dev/null 2>&1 && echo FOUND";
            String out = RootManager.execute(probe, 2000);
            if (out != null && out.contains("FOUND")) {
                return c;
            }
        }
        return null;
    }
}
