package com.rkant.netcut;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class NetworkScanner {
    private static final String TAG = "NetworkScanner";

    public static String getGatewayIp(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int gatewayInt = wm.getDhcpInfo().gateway;
            return String.format("%d.%d.%d.%d",
                    (gatewayInt & 0xff),
                    (gatewayInt >> 8 & 0xff),
                    (gatewayInt >> 16 & 0xff),
                    (gatewayInt >> 24 & 0xff));
        } catch (Exception e) {
            return null;
        }
    }

    public static String getActiveNetworkIp(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        for (android.net.LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                            InetAddress address = linkAddress.getAddress();
                            if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ConnectivityManager failed", e);
        }
        return null;
    }

    public static String getInterfaceName() {
        return "wlan0"; // Standard Android WiFi interface
    }

    public static void pingSubnet(Context context) {
        String myIp = getActiveNetworkIp(context);
        if (myIp == null) return;

        String[] parts = myIp.split("\\.");
        if (parts.length != 4) return;

        String prefix = parts[0] + "." + parts[1] + "." + parts[2];
        Log.d(TAG, "Pinging subnet: " + prefix + ".x to populate ARP cache");

        String cmd = "for i in $(seq 1 254); do ping -c 1 -W 1 " + prefix + ".$i >/dev/null 2>&1 & done; wait";
        RootManager.execute(cmd);
        Log.d(TAG, "Subnet ping sweep completed.");
    }

    public static List<Device> scanArp(Context context) {
        List<Device> devices = new ArrayList<>();
        String gatewayIp = getGatewayIp(context);
        String myIp = getActiveNetworkIp(context);
        String iface = getInterfaceName();

        // 1. Force the OS to discover devices by pinging the subnet
        pingSubnet(context);

        // 2. Read the newly populated ARP cache
        String output = RootManager.execute("cat /proc/net/arp");
        Log.d(TAG, "Raw ARP output:\n" + output);

        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.startsWith("IP") || line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+");

            if (parts.length >= 6) {
                String ip = parts[0];
                String mac = parts[3];
                String device = parts[5];

                // ✅ STRICTLY Exclude gateway, own IP, and invalid MACs
                if (ip.equals(gatewayIp) || ip.equals(myIp)) continue;
                if (mac.equals("00:00:00:00:00:00") || mac.equalsIgnoreCase("incomplete")) continue;

                // Only include valid network interfaces
                if (device.equals(iface) || device.startsWith("wlan") || device.startsWith("eth")) {
                    devices.add(new Device(mac, ip, "", false, true));
                }
            }
        }
        return devices;
    }
}
