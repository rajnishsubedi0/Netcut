package com.rkant.netcut;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogStore {

    public static class LogItem {
        public final long time;
        public final String level;
        public final String message;

        public LogItem(long time, String level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }

        public String getFormatted() {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(time));
            return ts + " [" + level + "] " + message;
        }
    }

    private static final int MAX_LOGS = 1000;
    private static final ArrayList<LogItem> logs = new ArrayList<>();

    public static synchronized void i(String message) {
        add("INFO", message);
        Log.i("Netcut", message);
    }

    public static synchronized void w(String message) {
        add("WARN", message);
        Log.w("Netcut", message);
    }

    public static synchronized void e(String message) {
        add("ERROR", message);
        Log.e("Netcut", message);
    }

    private static void add(String level, String message) {
        logs.add(new LogItem(System.currentTimeMillis(), level, message));
        while (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
    }

    public static synchronized List<LogItem> getLogs() {
        return new ArrayList<>(logs);
    }

    public static synchronized void clear() {
        logs.clear();
    }
}