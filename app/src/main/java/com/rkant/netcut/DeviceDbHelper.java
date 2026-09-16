package com.rkant.netcut;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class DeviceDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "netcut.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "devices";
    private static final String COL_MAC = "mac_address";
    private static final String COL_IP = "ip_address";
    private static final String COL_NAME = "custom_name";
    private static final String COL_BANNED = "is_banned";

    public DeviceDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE + " (" +
                COL_MAC + " TEXT PRIMARY KEY, " +
                COL_IP + " TEXT, " +
                COL_NAME + " TEXT, " +
                COL_BANNED + " INTEGER DEFAULT 0)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    public synchronized List<Device> getSavedDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String selection = COL_BANNED + " = 1 OR (" +
                COL_NAME + " IS NOT NULL AND " + COL_NAME + " != '')";
        try (Cursor c = db.query(TABLE, null, selection, null, null, null,
                COL_BANNED + " DESC, " + COL_IP + " ASC")) {
            while (c.moveToNext()) { list.add(cursorToDevice(c)); }
        } catch (Exception ignored) {}
        return list;
    }

    public synchronized Device getDevice(String mac) {
        if (mac == null || mac.trim().isEmpty()) return null;
        mac = Device.normalizeMac(mac);
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, COL_MAC + "=?",
                new String[]{mac}, null, null, null)) {
            if (c.moveToFirst()) return cursorToDevice(c);
        } catch (Exception ignored) {}
        return null;
    }

    public synchronized void updateIp(String mac, String ip) {
        if (mac == null || mac.trim().isEmpty() || ip == null || ip.trim().isEmpty()) return;
        mac = Device.normalizeMac(mac);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_IP, ip.trim());
        db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
    }

    public synchronized void setBanned(String mac, String ip, boolean isBanned) {
        if (mac == null || mac.trim().isEmpty()) return;
        mac = Device.normalizeMac(mac);
        SQLiteDatabase db = getWritableDatabase();

        if (!isBanned && !deviceExists(db, mac)) return;

        ContentValues values = new ContentValues();
        values.put(COL_BANNED, isBanned ? 1 : 0);
        if (ip != null && !ip.trim().isEmpty()) values.put(COL_IP, ip.trim());

        try (Cursor c = db.query(TABLE, new String[]{COL_NAME},
                COL_MAC + "=?", new String[]{mac}, null, null, null)) {
            if (c.moveToFirst()) {
                String existingName = c.getString(c.getColumnIndexOrThrow(COL_NAME));
                if (existingName == null) values.putNull(COL_NAME);
                else values.put(COL_NAME, existingName);
                int updated = db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
                if (updated > 0) return;
            }
        } catch (Exception ignored) {}

        values.put(COL_MAC, mac);
        if (!values.containsKey(COL_IP)) values.put(COL_IP, "");
        if (!values.containsKey(COL_NAME)) values.putNull(COL_NAME);
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void setName(String mac, String ip, String name) {
        if (mac == null || mac.trim().isEmpty()) return;
        mac = Device.normalizeMac(mac);
        String safeName = name == null ? "" : name.trim();
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COL_NAME, safeName);
        if (ip != null && !ip.trim().isEmpty()) values.put(COL_IP, ip.trim());

        try (Cursor c = db.query(TABLE, new String[]{COL_BANNED},
                COL_MAC + "=?", new String[]{mac}, null, null, null)) {
            if (c.moveToFirst()) {
                int existingBanned = c.getInt(c.getColumnIndexOrThrow(COL_BANNED));
                values.put(COL_BANNED, existingBanned);
                int updated = db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
                if (updated > 0) return;
            }
        } catch (Exception ignored) {}

        values.put(COL_MAC, mac);
        values.put(COL_BANNED, 0);
        if (!values.containsKey(COL_IP)) values.put(COL_IP, "");
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<Device> getBannedDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, COL_BANNED + "=?",
                new String[]{"1"}, null, null, null)) {
            while (c.moveToNext()) {
                Device d = cursorToDevice(c);
                d.setOnline(false);
                list.add(d);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public synchronized void unbanAll() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_BANNED, 0);
        db.update(TABLE, values, COL_BANNED + "=?", new String[]{"1"});
    }

    private Device cursorToDevice(Cursor c) {
        String name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
        return new Device(
                c.getString(c.getColumnIndexOrThrow(COL_MAC)),
                c.getString(c.getColumnIndexOrThrow(COL_IP)),
                name,
                c.getInt(c.getColumnIndexOrThrow(COL_BANNED)) == 1,
                false
        );
    }

    private boolean deviceExists(SQLiteDatabase db, String mac) {
        try (Cursor c = db.query(TABLE, new String[]{COL_MAC},
                COL_MAC + "=?", new String[]{mac}, null, null, null)) {
            return c.moveToFirst();
        }
    }
}