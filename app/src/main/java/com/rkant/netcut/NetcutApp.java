package com.rkant.netcut;

import android.app.Application;

public class NetcutApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.applySavedTheme(this);
    }
}