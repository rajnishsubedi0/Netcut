package com.example.netcutapp;

public class Device {
    private String mac;
    private String ip;
    private String name;
    private boolean isBanned;
    private boolean isOnline;

    public Device(String mac, String ip, String name, boolean isBanned, boolean isOnline) {
        this.mac = mac;
        this.ip = ip;
        this.name = name;
        this.isBanned = isBanned;
        this.isOnline = isOnline;
    }

    public String getMac() { return mac; }
    public void setMac(String mac) { this.mac = mac; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getName() { return name == null || name.isEmpty() ? "Unnamed" : name; }
    public void setName(String name) { this.name = name; }
    public boolean isBanned() { return isBanned; }
    public void setBanned(boolean banned) { isBanned = banned; }
    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }
}