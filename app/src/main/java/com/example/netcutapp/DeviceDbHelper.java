package com.example.netcutapp;

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
    private static final String COL_LAST_SEEN = "last_seen";

    public DeviceDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE + " (" +
                COL_MAC + " TEXT PRIMARY KEY, " +
                COL_IP + " TEXT, " +
                COL_NAME + " TEXT, " +
                COL_BANNED + " INTEGER DEFAULT 0, " +
                COL_LAST_SEEN + " INTEGER DEFAULT 0)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void updateDeviceStatus(String mac, String ip, boolean isOnline) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_IP, ip);
        values.put(COL_LAST_SEEN, isOnline ? System.currentTimeMillis() : 0);

        Cursor c = db.query(TABLE, new String[]{COL_MAC}, COL_MAC + "=?", new String[]{mac}, null, null, null);
        if (c.getCount() == 0) {
            values.put(COL_MAC, mac);
            values.put(COL_BANNED, 0);
            values.put(COL_NAME, "");
            db.insert(TABLE, null, values);
        } else {
            db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
        }
        c.close();
    }

    public void setBanned(String mac, boolean isBanned) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_BANNED, isBanned ? 1 : 0);
        db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
    }

    public void setName(String mac, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_NAME, name);
        db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
    }

    public List<Device> getAllDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, COL_LAST_SEEN + " DESC");
        while (c.moveToNext()) {
            list.add(cursorToDevice(c));
        }
        c.close();
        return list;
    }

    public List<Device> getBannedDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, COL_BANNED + "=?", new String[]{"1"}, null, null, null);
        while (c.moveToNext()) {
            list.add(cursorToDevice(c));
        }
        c.close();
        return list;
    }

    public List<Device> getOnlineBannedDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        // Consider online if last_seen is within the last 30 seconds
        long threshold = System.currentTimeMillis() - 30000;
        Cursor c = db.query(TABLE, null, COL_BANNED + "=? AND " + COL_LAST_SEEN + " > ?",
                new String[]{"1", String.valueOf(threshold)}, null, null, null);
        while (c.moveToNext()) {
            list.add(cursorToDevice(c));
        }
        c.close();
        return list;
    }

    private Device cursorToDevice(Cursor c) {
        return new Device(
                c.getString(c.getColumnIndexOrThrow(COL_MAC)),
                c.getString(c.getColumnIndexOrThrow(COL_IP)),
                c.getString(c.getColumnIndexOrThrow(COL_NAME)),
                c.getInt(c.getColumnIndexOrThrow(COL_BANNED)) == 1,
                c.getLong(c.getColumnIndexOrThrow(COL_LAST_SEEN)) > 0
        );
    }
}