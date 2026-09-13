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

    // Fetch a specific device (only exists if banned or named)
    public Device getDevice(String mac) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, COL_MAC + "=?", new String[]{mac}, null, null, null);
        Device d = null;
        if (c.moveToFirst()) {
            d = cursorToDevice(c);
        }
        c.close();
        return d;
    }

    // Update IP for an existing DB device
    public void updateIp(String mac, String ip) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_IP, ip);
        db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
    }

    // Ban/Unban (Ensures device is saved to DB)
    public void setBanned(String mac, String ip, boolean isBanned) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_MAC, mac);
        values.put(COL_IP, ip);
        values.put(COL_BANNED, isBanned ? 1 : 0);
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // Name device (Ensures device is saved to DB)
    public void setName(String mac, String ip, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_MAC, mac);
        values.put(COL_IP, ip);
        values.put(COL_NAME, name);
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
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

    private Device cursorToDevice(Cursor c) {
        String name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
        return new Device(
                c.getString(c.getColumnIndexOrThrow(COL_MAC)),
                c.getString(c.getColumnIndexOrThrow(COL_IP)),
                name,
                c.getInt(c.getColumnIndexOrThrow(COL_BANNED)) == 1,
                true // If it's in the DB, we consider it known
        );
    }
}