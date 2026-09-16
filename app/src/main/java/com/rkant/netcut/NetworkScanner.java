package com.rkant.netcut;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class NetworkScanner {
    private static final String TAG = "NetworkScanner";

    /**
     * Gets gateway IP using ConnectivityManager (preferred) with DHCP fallback.
     */
    public static String getGatewayIp(Context context) {
        // Method 1: ConnectivityManager LinkProperties (more reliable)
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties lp = cm.getLinkProperties(activeNetwork);
                    if (lp != null) {
                        for (RouteInfo route : lp.getRoutes()) {
                            if (route.isDefaultRoute() && route.getGateway() != null) {
                                String gw = route.getGateway().getHostAddress();
                                if (gw != null && Device.isValidIpv4(gw) && !gw.equals("0.0.0.0")) {
                                    return gw;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ConnectivityManager gateway detection failed", e);
        }

        // Method 2: DHCP fallback
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getDhcpInfo() != null) {
                int gatewayInt = wm.getDhcpInfo().gateway;
                if (gatewayInt != 0) {
                    String gw = String.format("%d.%d.%d.%d",
                            (gatewayInt & 0xff),
                            (gatewayInt >> 8 & 0xff),
                            (gatewayInt >> 16 & 0xff),
                            (gatewayInt >> 24 & 0xff));
                    if (Device.isValidIpv4(gw) && !gw.equals("0.0.0.0")) {
                        return gw;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "DHCP gateway detection failed", e);
        }

        return null;
    }

    /**
     * Gets the active network IPv4 address.
     */
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
                            if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
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

    /**
     * Detects the active network interface name dynamically.
     * Falls back to "wlan0" if detection fails.
     */
    public static String getInterfaceName(Context context) {
        try {
            String myIp = getActiveNetworkIp(context);
            if (myIp != null) {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) continue;

                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && addr.getHostAddress().equals(myIp)) {
                            Log.d(TAG, "Detected interface: " + ni.getName());
                            return ni.getName();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Interface detection failed", e);
        }
        return "wlan0"; // Fallback
    }

    /**
     * Gets own MAC address for the active interface.
     */
    public static String getOwnMac(Context context) {
        try {
            String iface = getInterfaceName(context);
            NetworkInterface ni = NetworkInterface.getByName(iface);
            if (ni != null) {
                byte[] mac = ni.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) sb.append(":");
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get own MAC", e);
        }
        return null;
    }

    /**
     * Pings the subnet to populate ARP cache.
     */
    public static void pingSubnet(Context context) {
        String myIp = getActiveNetworkIp(context);
        if (myIp == null || !Device.isValidIpv4(myIp)) return;

        String[] parts = myIp.split("\\.");
        if (parts.length != 4) return;
        String prefix = parts[0] + "." + parts[1] + "." + parts[2];

        Log.d(TAG, "Pinging subnet: " + prefix + ".x to populate ARP cache");
        String cmd = "for i in $(seq 1 254); do ping -c 1 -W 1 " + prefix + ".$i >/dev/null 2>&1 & done; wait";
        RootManager.execute(cmd, 8000);
        Log.d(TAG, "Subnet ping sweep completed.");
    }

    /**
     * Scans ARP table and returns list of devices.
     * Validates entries and skips stale/incomplete ones.
     */
    public static List<Device> scanArp(Context context) {
        List<Device> devices = new ArrayList<>();
        String gatewayIp = getGatewayIp(context);
        String myIp = getActiveNetworkIp(context);
        String ownMac = getOwnMac(context);
        String iface = getInterfaceName(context);

        // 1. Force the OS to discover devices by pinging the subnet
        pingSubnet(context);

        // 2. Read the newly populated ARP cache
        String output = RootManager.execute("cat /proc/net/arp", 5000);
        if (output == null || output.trim().isEmpty()) {
            Log.e(TAG, "Failed to read ARP table");
            return devices;
        }

        Log.d(TAG, "Raw ARP output:\n" + output);
        String[] lines = output.split("\n");

        for (String line : lines) {
            if (line.startsWith("IP") || line.trim().isEmpty()) continue;

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 6) continue;

            String ip = parts[0].trim();
            String flagsStr = parts[2].trim();
            String mac = parts[3].trim();
            String device = parts[5].trim();

            // Validate IP
            if (!Device.isValidIpv4(ip)) continue;

            // Validate MAC
            if (mac.equals("00:00:00:00:00:00") || mac.equalsIgnoreCase("incomplete")) continue;
            if (!Device.isValidMac(mac)) continue;

            // Check ARP flags - 0x0 means incomplete
            try {
                int flags = Integer.decode(flagsStr);
                if ((flags & 0x2) == 0) continue; // Skip incomplete entries
            } catch (Exception e) {
                // If we can't parse flags, include the entry anyway
            }

            // Exclude gateway, own IP, own MAC
            if (ip.equals(gatewayIp)) continue;
            if (ip.equals(myIp)) continue;
            if (ownMac != null && Device.normalizeMac(mac).equals(Device.normalizeMac(ownMac))) continue;

            // Only include valid network interfaces
            if (device.equals(iface) || device.startsWith("wlan") || device.startsWith("eth")) {
                devices.add(new Device(mac, ip, "", false, true));
            }
        }

        return devices;
    }
}