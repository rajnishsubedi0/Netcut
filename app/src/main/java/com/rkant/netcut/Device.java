package com.rkant.netcut;

public class Device {
    private String mac;
    private String ip;
    private String name;
    private boolean isBanned;
    private boolean isOnline;

    public Device(String mac, String ip, String name, boolean isBanned, boolean isOnline) {
        this.mac = normalizeMac(mac);
        this.ip = ip != null ? ip.trim() : "";
        this.name = name;
        this.isBanned = isBanned;
        this.isOnline = isOnline;
    }

    public String getMac() { return mac; }
    public void setMac(String mac) { this.mac = normalizeMac(mac); }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip != null ? ip.trim() : ""; }
    public String getName() { return name == null || name.isEmpty() ? "Unnamed" : name; }
    public void setName(String name) { this.name = name; }
    public boolean isBanned() { return isBanned; }
    public void setBanned(boolean banned) { isBanned = banned; }
    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    public static String normalizeMac(String mac) {
        if (mac == null || mac.trim().isEmpty()) return "";
        return mac.trim().toUpperCase().replace("-", ":");
    }

    public static boolean isValidIpv4(String ip) {
        if (ip == null || ip.trim().isEmpty()) return false;
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
            } catch (NumberFormatException e) { return false; }
        }
        return true;
    }

    public static boolean isValidMac(String mac) {
        if (mac == null) return false;
        return mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");
    }
}