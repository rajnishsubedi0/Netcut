package com.rkant.netcut;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves a MAC's hardware vendor, preferring the bundled offline table
 * ({@link OuiLookup}) and falling back to public online lookup APIs for MACs
 * that aren't in the table.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Privacy:</b> only the OUI (first 3 octets, e.g. {@code AA:BB:CC:00:00:00})
 *       is sent to the remote APIs, never the device's full MAC.</li>
 *   <li><b>Caching:</b> results are cached in memory and, for hits, persisted in
 *       SharedPreferences so a given OUI is queried at most once.</li>
 *   <li><b>Rate limits:</b> lookups run on a single background thread, are
 *       de-duplicated per OUI, and spaced out to respect free-tier limits.</li>
 *   <li><b>Fallback:</b> the APIs are tried in order; the first that returns a
 *       vendor wins, and an error simply moves on to the next.</li>
 * </ul>
 *
 * {@link #describe(Context, String)} never blocks on the network: it returns the
 * best value known right now (offline or cached) and schedules an online lookup
 * for misses, so a later scan picks up the resolved vendor.
 */
public final class VendorResolver {

    private VendorResolver() {}

    private static final String TAG = "VendorResolver";
    private static final String PREFS = "vendor_cache";

    // Spacing between remote requests to stay within free-tier rate limits.
    private static final int REQUEST_SPACING_MS = 1200;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;

    // OUI -> vendor. An empty string is a negative cache entry ("looked up,
    // unknown") kept only in memory so a restart will retry.
    private static final Map<String, String> memCache = new ConcurrentHashMap<>();
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private static final ExecutorService netPool = Executors.newSingleThreadExecutor();

    /**
     * Best vendor label known right now for the MAC, scheduling a background
     * online lookup for cache misses. Returns "Randomized MAC" for private
     * addresses and null when nothing is known yet.
     */
    public static String describe(Context context, String mac) {
        if (OuiLookup.isRandomized(mac)) return "Randomized MAC";

        String offline = OuiLookup.lookupVendor(mac);
        if (offline != null) return offline;

        String oui = ouiKey(mac);
        if (oui == null) return null;

        String cached = memCache.get(oui);
        if (cached != null) return cached.isEmpty() ? null : cached;

        if (context != null) {
            String pref = prefs(context).getString(oui, null);
            if (pref != null) {
                memCache.put(oui, pref);
                return pref.isEmpty() ? null : pref;
            }
            scheduleFetch(context.getApplicationContext(), oui);
        }
        return null;
    }

    private static void scheduleFetch(Context appCtx, String oui) {
        if (!inFlight.add(oui)) return;
        netPool.execute(() -> {
            String vendor = null;
            try {
                vendor = queryApis(queryMac(oui));
            } catch (Exception e) {
                Log.w(TAG, "Vendor lookup failed for " + oui, e);
            }
            if (vendor != null && !vendor.isEmpty()) {
                memCache.put(oui, vendor);
                prefs(appCtx).edit().putString(oui, vendor).apply();
            } else {
                memCache.put(oui, ""); // negative cache (memory only)
            }
            inFlight.remove(oui);
            try {
                Thread.sleep(REQUEST_SPACING_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Tries each API in turn; the first vendor found wins. */
    private static String queryApis(String mac) {
        String v = fromMacVendors(mac);
        if (v != null) return v;

        v = fromMacVendorLookup(mac);
        if (v != null) return v;

        v = fromMacAddress(mac);
        return v;
    }

    // https://api.macvendors.com/<mac>  ->  plain-text vendor
    private static String fromMacVendors(String mac) {
        String body = httpGet("https://api.macvendors.com/" + mac);
        if (body == null) return null;
        body = body.trim();
        if (body.isEmpty() || body.startsWith("{") || body.startsWith("[")
                || body.toLowerCase().contains("not found")
                || body.toLowerCase().contains("errors")) {
            return null;
        }
        return body;
    }

    // https://www.macvendorlookup.com/api/v2/<mac>/json  ->  [ { "company": "..." } ]
    private static String fromMacVendorLookup(String mac) {
        String body = httpGet("https://www.macvendorlookup.com/api/v2/" + mac + "/json");
        if (body == null) return null;
        try {
            body = body.trim();
            if (body.startsWith("[")) {
                JSONArray arr = new JSONArray(body);
                if (arr.length() > 0) {
                    String company = arr.getJSONObject(0).optString("company", "");
                    if (!company.isEmpty()) return company;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // https://api.macadress.com/v1/vendor/<mac>  (best-effort parsing)
    private static String fromMacAddress(String mac) {
        String body = httpGet("https://api.macadress.com/v1/vendor/" + mac);
        if (body == null) return null;
        body = body.trim();
        if (body.isEmpty()) return null;
        try {
            if (body.startsWith("{")) {
                JSONObject obj = new JSONObject(body);
                for (String key : new String[]{"vendor", "company", "result", "name", "manufacturer"}) {
                    String v = obj.optString(key, "");
                    if (!v.isEmpty()) return v;
                }
                return null;
            }
        } catch (Exception ignored) {}
        // Plain-text response
        if (!body.startsWith("<") && !body.toLowerCase().contains("not found")
                && !body.toLowerCase().contains("error")) {
            return body;
        }
        return null;
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "Netcut-App");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** First 3 octets of the MAC as "AABBCC", or null if malformed. */
    private static String ouiKey(String mac) {
        if (mac == null) return null;
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() < 6) return null;
        return hex.substring(0, 6);
    }

    /** Turns an "AABBCC" OUI into a full, privacy-preserving query MAC. */
    private static String queryMac(String oui) {
        return oui.substring(0, 2) + ":" + oui.substring(2, 4) + ":" + oui.substring(4, 6)
                + ":00:00:00";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
