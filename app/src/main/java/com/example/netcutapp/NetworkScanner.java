package com.example.netcutapp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
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

    public static String getInterfaceName() {
        return "wlan0"; // Standard Android WiFi interface
    }

    /**
     * Pings all IPs in the local subnet to force the OS to populate the ARP cache.
     */
    public static void pingSubnet(Context context) {
        String gateway = getGatewayIp(context);
        if (gateway == null) return;

        String[] parts = gateway.split("\\.");
        if (parts.length != 4) return;

        // Extract the first 3 octets (e.g., "192.168.1")
        String prefix = parts[0] + "." + parts[1] + "." + parts[2];
        Log.d(TAG, "Pinging subnet: " + prefix + ".0/24 to populate ARP cache");

        // Shell script to ping all 254 IPs in parallel.
        // -c 1: send 1 packet, -W 1: timeout 1 sec.
        // Output is redirected to /dev/null to keep it clean.
        // 'wait' ensures the shell doesn't exit until all pings finish.
        String cmd = "for i in $(seq 1 254); do ping -c 1 -W 1 " + prefix + ".$i >/dev/null 2>&1 & done; wait";
        RootManager.execute(cmd);
        Log.d(TAG, "Subnet ping sweep completed.");
    }

    /**
     * Scans the network by first pinging the subnet, then reading the ARP cache.
     */
    public static List<Device> scanArp(Context context) {
        // 1. Force the OS to discover devices by pinging the subnet
        pingSubnet(context);

        // 2. Read the newly populated ARP cache
        List<Device> devices = new ArrayList<>();
        String output = RootManager.execute("cat /proc/net/arp");
        String iface = getInterfaceName();

        Log.d(TAG, "Raw ARP output:\n" + output);

        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.startsWith("IP") || line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+");

            // /proc/net/arp columns: 0:IP, 1:HW type, 2:Flags, 3:HW address, 4:Mask, 5:Device
            if (parts.length >= 6) {
                String ip = parts[0];
                String mac = parts[3];
                String device = parts[5];

                // Filter by our WiFi interface and ignore incomplete entries (00:00:00:00:00:00)
                if (device.equals(iface) && !mac.equals("00:00:00:00:00:00")) {
                    devices.add(new Device(mac, ip, "", false, true));
                }
            }
        }
        return devices;
    }
}