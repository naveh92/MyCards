package com.mycards.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Light/dark preference.
 *
 * <p>Defaults to following the device setting, which is what most people expect and means
 * the app respects a system-wide schedule without being told to.
 */
public final class ThemePrefs {

    private ThemePrefs() {
    }

    private static final String PREFS = "mycards_theme";
    private static final String KEY_MODE = "night_mode";

    public static int getMode(Context context) {
        return prefs(context).getInt(KEY_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** Persists the choice and applies it immediately; open activities are recreated. */
    public static void setMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /** Re-applies the stored choice at startup, before any activity is created. */
    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(getMode(context));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
