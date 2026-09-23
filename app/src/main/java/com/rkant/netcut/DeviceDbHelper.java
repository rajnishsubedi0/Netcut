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
    private static final int DB_VERSION = 3;

    private static final String TABLE = "devices";
    private static final String COL_MAC = "mac_address";
    private static final String COL_IP = "ip_address";
    private static final String COL_NAME = "custom_name";
    private static final String COL_BANNED = "is_banned";
    private static final String COL_PROTECTED = "is_protected";
    private static final String COL_FIRST_SEEN = "first_seen";
    private static final String COL_LAST_SEEN = "last_seen";
    private static final String COL_SAVED = "is_saved";

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
                COL_PROTECTED + " INTEGER DEFAULT 0, " +
                COL_SAVED + " INTEGER DEFAULT 0, " +
                COL_FIRST_SEEN + " INTEGER DEFAULT 0, " +
                COL_LAST_SEEN + " INTEGER DEFAULT 0)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_PROTECTED + " INTEGER DEFAULT 0");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_FIRST_SEEN + " INTEGER DEFAULT 0");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_LAST_SEEN + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {
                db.execSQL("DROP TABLE IF EXISTS " + TABLE);
                onCreate(db);
            }
        }

        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_SAVED + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    public synchronized Device getDevice(String mac) {
        if (mac == null || mac.trim().isEmpty()) return null;
        mac = Device.normalizeMac(mac);

        SQLiteDatabase db = getReadableDatabase();
        return getDeviceInternal(db, mac);
    }

    private Device getDeviceInternal(SQLiteDatabase db, String mac) {
        try (Cursor c = db.query(TABLE, null, COL_MAC + "=?",
                new String[]{mac}, null, null, null)) {
            if (c.moveToFirst()) {
                return cursorToDevice(c);
            }
        } catch (Exception ignored) {
        }
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

    public synchronized void touchDevice(String mac, String ip, long firstSeen, long lastSeen) {
        if (mac == null || mac.trim().isEmpty()) return;
        mac = Device.normalizeMac(mac);

        SQLiteDatabase db = getWritableDatabase();
        Device existing = getDeviceInternal(db, mac);

        if (existing == null) {
            ContentValues values = new ContentValues();
            values.put(COL_MAC, mac);
            values.put(COL_IP, ip != null ? ip.trim() : "");
            values.putNull(COL_NAME);
            values.put(COL_BANNED, 0);
            values.put(COL_PROTECTED, 0);
            values.put(COL_FIRST_SEEN, firstSeen);
            values.put(COL_LAST_SEEN, lastSeen);

            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } else {
            ContentValues values = new ContentValues();

            if (ip != null && !ip.trim().isEmpty()) {
                values.put(COL_IP, ip.trim());
            }

            values.put(COL_LAST_SEEN, lastSeen);

            if (existing.getFirstSeen() <= 0) {
                values.put(COL_FIRST_SEEN, firstSeen);
            }

            db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
        }
    }

    public synchronized void setBanned(String mac, String ip, boolean isBanned) {
        if (mac == null || mac.trim().isEmpty()) return;
        mac = Device.normalizeMac(mac);

        SQLiteDatabase db = getWritableDatabase();

        if (!isBanned && !deviceExists(db, mac)) return;

        ContentValues values = new ContentValues();
        values.put(COL_BANNED, isBanned ? 1 : 0);

        if (ip != null && !ip.trim().isEmpty()) {
            values.put(COL_IP, ip.trim());
        }

        Device existing = getDeviceInternal(db, mac);
        if (existing != null) {
            if (existing.getRawName() == null) {
                values.putNull(COL_NAME);
            } else {
                values.put(COL_NAME, existing.getRawName());
            }

            values.put(COL_PROTECTED, existing.isProtected() ? 1 : 0);

            int updated = db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
            if (updated > 0) return;
        }

        values.put(COL_MAC, mac);
        if (!values.containsKey(COL_IP)) values.put(COL_IP, "");
        if (!values.containsKey(COL_NAME)) values.putNull(COL_NAME);
        values.put(COL_PROTECTED, 0);
        values.put(COL_FIRST_SEEN, 0);
        values.put(COL_LAST_SEEN, 0);
        values.put(COL_SAVED, 0);

        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void setName(String mac, String ip, String name) {
        if (mac == null || mac.trim().isEmpty()) return;
        mac = Device.normalizeMac(mac);

        String safeName = name == null ? "" : name.trim();

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_NAME, safeName);

        if (ip != null && !ip.trim().isEmpty()) {
            values.put(COL_IP, ip.trim());
        }

        Device existing = getDeviceInternal(db, mac);
        if (existing != null) {
            values.put(COL_BANNED, existing.isBanned() ? 1 : 0);
            values.put(COL_PROTECTED, existing.isProtected() ? 1 : 0);

            int updated = db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
            if (updated > 0) return;
        }

        values.put(COL_MAC, mac);
        values.put(COL_BANNED, 0);
        values.put(COL_PROTECTED, 0);
        if (!values.containsKey(COL_IP)) values.put(COL_IP, "");
        values.put(COL_FIRST_SEEN, 0);
        values.put(COL_LAST_SEEN, 0);

        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void setProtected(String mac, boolean isProtected) {
        if (mac == null || mac.trim().isEmpty()) return;
        mac = Device.normalizeMac(mac);

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_PROTECTED, isProtected ? 1 : 0);

        Device existing = getDeviceInternal(db, mac);
        if (existing != null) {
            if (existing.getRawName() == null) values.putNull(COL_NAME);
            else values.put(COL_NAME, existing.getRawName());

            values.put(COL_BANNED, existing.isBanned() ? 1 : 0);

            if (existing.getIp() != null && !existing.getIp().trim().isEmpty()) {
                values.put(COL_IP, existing.getIp());
            }

            db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
        } else {
            values.put(COL_MAC, mac);
            values.put(COL_IP, "");
            values.putNull(COL_NAME);
            values.put(COL_BANNED, 0);
            values.put(COL_FIRST_SEEN, 0);
            values.put(COL_LAST_SEEN, 0);

            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public synchronized List<Device> getBannedDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.query(TABLE, null, COL_BANNED + "=?",
                new String[]{"1"}, null, null, COL_LAST_SEEN + " DESC")) {
            while (c.moveToNext()) {
                Device d = cursorToDevice(c);
                d.setOnline(false);
                list.add(d);
            }
        } catch (Exception ignored) {
        }

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
                false,
                c.getInt(c.getColumnIndexOrThrow(COL_PROTECTED)) == 1,
                c.getLong(c.getColumnIndexOrThrow(COL_FIRST_SEEN)),
                c.getLong(c.getColumnIndexOrThrow(COL_LAST_SEEN)),
                c.getInt(c.getColumnIndexOrThrow(COL_SAVED)) == 1
        );
    }
    public synchronized List<Device> getSavedDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.query(
                TABLE,
                null,
                COL_SAVED + "=?",
                new String[]{"1"},
                null,
                null,
                COL_LAST_SEEN + " DESC")) {

            while (c.moveToNext()) {
                Device d = cursorToDevice(c);
                d.setOnline(false);
                list.add(d);
            }
        } catch (Exception ignored) {
        }

        return list;
    }

    public synchronized void setSaved(String mac, String ip, String name,
                                      long firstSeen, long lastSeen, boolean saved) {
        if (mac == null || mac.trim().isEmpty()) return;

        mac = Device.normalizeMac(mac);
        SQLiteDatabase db = getWritableDatabase();
        Device existing = getDeviceInternal(db, mac);

        ContentValues values = new ContentValues();
        values.put(COL_SAVED, saved ? 1 : 0);

        if (ip != null && !ip.trim().isEmpty()) {
            values.put(COL_IP, ip.trim());
        }

        if (name != null) {
            String safeName = name.trim();
            if (safeName.isEmpty()) {
                values.putNull(COL_NAME);
            } else {
                values.put(COL_NAME, safeName);
            }
        }

        if (existing != null) {
            if (name == null) {
                if (existing.getRawName() == null) {
                    values.putNull(COL_NAME);
                } else {
                    values.put(COL_NAME, existing.getRawName());
                }
            }

            values.put(COL_BANNED, existing.isBanned() ? 1 : 0);
            values.put(COL_PROTECTED, existing.isProtected() ? 1 : 0);

            long newFirstSeen = firstSeen > 0 ? firstSeen : existing.getFirstSeen();
            long newLastSeen = lastSeen > 0 ? lastSeen : existing.getLastSeen();

            values.put(COL_FIRST_SEEN, newFirstSeen);
            values.put(COL_LAST_SEEN, newLastSeen);

            db.update(TABLE, values, COL_MAC + "=?", new String[]{mac});
        } else {
            values.put(COL_MAC, mac);

            if (!values.containsKey(COL_IP)) {
                values.put(COL_IP, "");
            }

            if (!values.containsKey(COL_NAME)) {
                values.putNull(COL_NAME);
            }

            values.put(COL_BANNED, 0);
            values.put(COL_PROTECTED, 0);
            values.put(COL_FIRST_SEEN, firstSeen > 0 ? firstSeen : 0);
            values.put(COL_LAST_SEEN, lastSeen > 0 ? lastSeen : 0);

            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private boolean deviceExists(SQLiteDatabase db, String mac) {
        try (Cursor c = db.query(TABLE, new String[]{COL_MAC},
                COL_MAC + "=?", new String[]{mac}, null, null, null)) {
            return c.moveToFirst();
        }
    }
    public synchronized List<Device> getProtectedDevices() {
        List<Device> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.query(
                TABLE,
                null,
                COL_PROTECTED + "=?",
                new String[]{"1"},
                null,
                null,
                COL_LAST_SEEN + " DESC")) {

            while (c.moveToNext()) {
                Device d = cursorToDevice(c);
                d.setOnline(false);
                list.add(d);
            }
        } catch (Exception ignored) {
        }

        return list;
    }
}