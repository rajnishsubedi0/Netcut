package com.rkant.netcut;

public class Device {
    private String mac;
    private String ip;
    private String name;
    private String vendor;
    private boolean isBanned;
    private boolean isOnline;
    private boolean isProtected;
    private boolean isSaved;
    private long firstSeen;
    private long lastSeen;

    public Device(String mac, String ip, String name, boolean isBanned, boolean isOnline) {
        this(mac, ip, name, isBanned, isOnline, false, 0L, 0L, false);
    }

    public Device(String mac, String ip, String name, boolean isBanned, boolean isOnline,
                  boolean isProtected, long firstSeen, long lastSeen) {
        this(mac, ip, name, isBanned, isOnline, isProtected, firstSeen, lastSeen, false);
    }

    public Device(String mac, String ip, String name, boolean isBanned, boolean isOnline,
                  boolean isProtected, long firstSeen, long lastSeen, boolean isSaved) {
        this.mac = normalizeMac(mac);
        this.ip = ip != null ? ip.trim() : "";
        this.name = name;
        this.isBanned = isBanned;
        this.isOnline = isOnline;
        this.isProtected = isProtected;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.isSaved = isSaved;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = normalizeMac(mac);
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip != null ? ip.trim() : "";
    }

    public String getName() {
        return name == null || name.isEmpty() ? "Unnamed" : name;
    }

    public String getRawName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Hardware vendor/brand inferred from the MAC (OUI), or null if unknown. */
    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public boolean isBanned() {
        return isBanned;
    }

    public void setBanned(boolean banned) {
        isBanned = banned;
    }

    public boolean isOnline() {
        return isOnline;
    }

    public void setOnline(boolean online) {
        isOnline = online;
    }

    public boolean isProtected() {
        return isProtected;
    }

    public void setProtected(boolean aProtected) {
        isProtected = aProtected;
    }

    public boolean isSaved() {
        return isSaved;
    }

    public void setSaved(boolean saved) {
        isSaved = saved;
    }

    public long getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(long firstSeen) {
        this.firstSeen = firstSeen;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public static String formatLastSeen(long time) {
        if (time <= 0) return "Never";
        long diff = System.currentTimeMillis() - time;
        if (diff < 0) diff = 0;
        long seconds = diff / 1000;
        if (seconds < 5) return "Just now";
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }

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
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidMac(String mac) {
        if (mac == null) return false;
        return mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");
    }
}