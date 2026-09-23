package com.rkant.netcut;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionLogManager {

    private static final SessionLogManager INSTANCE = new SessionLogManager();

    private final List<String> logs = new ArrayList<>();
    private final SimpleDateFormat formatter =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    private SessionLogManager() {
    }

    public static SessionLogManager getInstance() {
        return INSTANCE;
    }

    public synchronized void log(String message) {
        if (message == null || message.trim().isEmpty()) return;

        String line = formatter.format(new Date()) + " - " + message;

        logs.add(0, line);

        // Keep only latest 500 logs in memory
        if (logs.size() > 500) {
            logs.remove(logs.size() - 1);
        }
    }

    public synchronized List<String> getLogs() {
        return new ArrayList<>(logs);
    }

    public synchronized void clear() {
        logs.clear();
    }
}