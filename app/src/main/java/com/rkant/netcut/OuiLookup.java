package com.rkant.netcut;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a hardware vendor (brand) from a MAC address using its OUI
 * (the first three octets, assigned by the IEEE to each manufacturer).
 *
 * <p>This is a bundled, offline subset of the most common consumer vendors,
 * not the full IEEE registry, so some devices will not match. Modern phones
 * often use a randomized (locally administered) MAC, which has no vendor at
 * all; {@link #isRandomized(String)} detects that case so the UI can explain
 * why a device cannot be identified.
 */
public final class OuiLookup {

    private OuiLookup() {}

    /** OUI (first 3 octets, "AABBCC") -> vendor label. */
    private static final Map<String, String> VENDORS = new HashMap<>();

    static {
        // Apple
        put("Apple", "000393", "000A27", "001B63", "001EC2", "002312", "002500",
                "0026BB", "3C0754", "406C8F", "68ABBC", "A483E7", "ACBC32",
                "F01898", "DCA904", "F82793", "8866A5", "BCD074");
        // Samsung
        put("Samsung", "001247", "001599", "001632", "0017C9", "001A8A", "002119",
                "002339", "08373D", "3423BA", "5C0A5B", "781FDB", "8C7712",
                "C819F7", "E8508B", "8425DB", "F409D8", "D0176A", "1C62B8");
        // Google (incl. Nest / Chromecast)
        put("Google", "001A11", "3C5AB4", "546009", "94EB2C", "F4F5D8", "F88FCA",
                "1CF29A", "6466B3", "A47733");
        // Xiaomi / Redmi
        put("Xiaomi", "009EC8", "0C1DAF", "102AB3", "14F65A", "286C07", "34CE00",
                "3CBD3E", "50642B", "640980", "64B473", "742344", "7811DC",
                "8CBEBE", "98FAE3", "F0B429", "F8A45F", "A4DA22", "286D97");
        // Huawei / Honor
        put("Huawei", "001882", "001E10", "00259E", "04BD70", "104780", "10C61F",
                "200BC7", "240995", "283152", "480031", "4C5499", "5C7D5E",
                "70723C", "8038BC", "8853D4", "ACE215", "D40B1A", "E0247F");
        // Amazon (Echo, Fire, Kindle)
        put("Amazon", "007147", "08849D", "0C47C9", "34D270", "40B4CD", "44650D",
                "50DCE7", "6837E9", "6C5697", "747548", "74C246", "84D6D0",
                "A002DC", "AC63BE", "F0272D", "FCA183", "4CEFC0", "68DBF5");
        // Intel (Wi-Fi cards in laptops)
        put("Intel", "001B21", "001E64", "00216A", "0024D7", "3CA9F4", "3413E8",
                "448500", "4851B7", "5CE0C5", "7C5CF8", "8C705A", "94659C",
                "A08869", "AC7BA1", "E4A471", "34E12D", "9CB6D0", "50EB71");
        // Realtek
        put("Realtek", "00E04C", "00E070", "1C1B0D", "70F11C");
        // Espressif (ESP32 / ESP8266 - common in smart-home / IoT)
        put("Espressif (IoT)", "240AC4", "24B2DE", "30AEA4", "3C71BF", "483FDA",
                "5CCF7F", "840D8E", "84F3EB", "90380C", "A020A6", "A4CF12",
                "B4E62D", "BCDDC2", "CC50E3", "D8A01D", "DC4F22", "ECFABC");
        // Raspberry Pi
        put("Raspberry Pi", "B827EB", "DCA632", "E45F01", "28CDC1", "D83ADD");
        // TP-Link
        put("TP-Link", "002719", "14CC20", "30B5C2", "50C7BF", "54C80F", "6032B1",
                "98DED0", "A42BB0", "AC84C6", "B0487A", "C006C3", "EC086B",
                "F4F26D", "003192", "5C628B");
        // Netgear
        put("Netgear", "00095B", "000FB5", "00146C", "001B2F", "001E2A", "00223F",
                "08BD43", "200CC8", "28C68E", "4494FC", "A00460", "C03F0E");
        // D-Link
        put("D-Link", "00055D", "000D88", "000F3D", "001346", "0015E9", "00179A",
                "001B11", "001CF0", "001E58", "002191", "0022B0", "1C7EE5",
                "28107B", "84C9B2", "C8BE19");
        // Ubiquiti
        put("Ubiquiti", "00156D", "002722", "0418D6", "24A43C", "44D9E7", "687251",
                "7483C2", "788A20", "802AA8", "B4FBE4", "DC9FDB", "F09FC2", "FCECDA");
        // Cisco
        put("Cisco", "000142", "000163", "0001C9", "00040D", "000BBE", "001121",
                "0014A9", "001B54", "00224D", "3037A6", "6400F1", "F09E63");
        // Sony (incl. PlayStation)
        put("Sony", "0013A9", "001DBA", "30F9ED", "544249", "FC0FE6", "0C6318",
                "AC8995", "78843C", "00041F", "001315");
        // Nintendo
        put("Nintendo", "0009BF", "001656", "0017AB", "001AE9", "001BEA", "001E35",
                "002147", "00224C", "002331", "0024F3", "0403D6", "182A7B",
                "34AF2C", "40F407", "58BDA3", "78A2A0", "8C56C5", "98B6E9",
                "9CE635", "A45C27", "B88AEC", "CC9E00", "E00C7F", "E84ECE");
        // Microsoft (Surface, Xbox, Hyper-V)
        put("Microsoft", "0003FF", "00125A", "00155D", "0017FA", "001DD8", "002248",
                "0050F2", "281878", "3059B7", "501AC5", "6045BD", "7C1E52",
                "985FD3", "C83F26", "F01DBC");
        // LG Electronics
        put("LG", "001C62", "001E75", "00E091", "10F1F2", "3CBDD8", "88C9D0",
                "A039F7", "C4366C", "58A2B5", "A816B2");
        // Motorola
        put("Motorola", "000CE5", "149FE8", "24DA9B", "40786A", "5C5188", "CCC3EA",
                "3C2EF9", "B07994", "F4F5A5");
        // OnePlus
        put("OnePlus", "94652D", "C0EEFB", "64A2F9");
        // ASUS
        put("ASUS", "001BFC", "002215", "04D9F5", "1C872C", "2C56DC", "305A3A",
                "38D547", "50465D", "704D7B", "AC220B", "D850E6", "F832E4");
        // HP / Hewlett Packard
        put("HP", "001321", "0017A4", "001CC4", "0021F7", "002481", "3822D6",
                "3CD92B", "5C8A38", "9457A5", "A08CFD", "B00CD1");
        // Dell
        put("Dell", "000874", "000BDB", "001143", "0015C5", "0018FE", "001AA0",
                "0021B7", "002564", "18DBF2", "B083FE", "D067E5", "F04DA2");
        // Lenovo
        put("Lenovo", "008CFA", "1436C6", "50B7C3", "6C0B84", "8CDCD4", "A48CDB",
                "3480B3");
        // Roku
        put("Roku", "080627", "8C49E2", "AC3A7A", "B0A737", "CC6DA0", "D0004B", "DC3A5E");
        // TCL / Alcatel
        put("TCL", "F859C7", "5CF8A1", "B01F81");
    }

    private static void put(String vendor, String... ouis) {
        for (String oui : ouis) {
            if (oui != null && oui.length() == 6) {
                VENDORS.put(oui.toUpperCase(), vendor);
            }
        }
    }

    /** First 3 octets of the MAC as "AABBCC", or null if the MAC is malformed. */
    private static String ouiOf(String mac) {
        if (mac == null) return null;
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() < 6) return null;
        return hex.substring(0, 6);
    }

    /**
     * @return the vendor/brand for the MAC, or null if it is unknown or
     *         randomized. Callers that want a user-facing string for every
     *         MAC should use {@link #describe(String)}.
     */
    public static String lookupVendor(String mac) {
        if (isRandomized(mac)) return null;
        String oui = ouiOf(mac);
        return oui == null ? null : VENDORS.get(oui);
    }

    /**
     * A locally administered (randomized/private) MAC has bit 0x02 set in its
     * first octet. Modern phones use these for privacy, and they carry no
     * vendor information.
     */
    public static boolean isRandomized(String mac) {
        String oui = ouiOf(mac);
        if (oui == null) return false;
        try {
            int firstOctet = Integer.parseInt(oui.substring(0, 2), 16);
            return (firstOctet & 0x02) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A user-facing brand label for any MAC: the vendor when known,
     * "Randomized MAC" for private/randomized addresses, or null when the
     * vendor is simply not in the bundled table.
     */
    public static String describe(String mac) {
        if (isRandomized(mac)) return "Randomized MAC";
        return lookupVendor(mac);
    }
}
