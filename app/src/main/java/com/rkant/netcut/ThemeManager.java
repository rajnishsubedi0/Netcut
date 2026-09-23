package com.rkant.netcut;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public class ThemeManager {
    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREFS_NAME = "netcut_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";

    public static int getSavedMode(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // ✅ DEFAULT THEME IS NOW LIGHT (was MODE_SYSTEM)
        return prefs.getInt(KEY_THEME_MODE, MODE_LIGHT);
    }

    public static void setThemeMode(Context context, int mode) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_THEME_MODE, mode)
                .apply();
        applyThemeMode(mode);
    }


    public static void applySavedTheme(Context context) {
        applyThemeMode(getSavedMode(context));
    }

    private static void applyThemeMode(int mode) {
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static String getModeLabel(int mode) {
        switch (mode) {
            case MODE_LIGHT: return "Light";
            case MODE_DARK:  return "Dark";
            default:         return "System";
        }
    }
}