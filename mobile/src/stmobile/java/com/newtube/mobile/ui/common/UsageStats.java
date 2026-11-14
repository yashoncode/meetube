package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * MEETUBE(stats): how long MeeTube is on screen, how long videos play and how many open, per day.
 * Kept on this phone only (prefs "meetube_usage", the last 60 days); the greeting shows it.
 */
public final class UsageStats {
    private static final String PREFS = "meetube_usage";
    private static final String APP = "a";
    private static final String WATCH = "w";
    private static final String VIDEOS = "v";
    private static final int KEEP_DAYS = 60;

    private static SharedPreferences sPrefs;
    private static int sStarted;
    private static long sAppSince;
    private static long sWatchSince;

    private UsageStats() {
    }

    public static void install(Application app) {
        sPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prune();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(@NonNull Activity activity) {
                if (sStarted++ == 0) sAppSince = SystemClock.elapsedRealtime();
            }

            @Override public void onActivityStopped(@NonNull Activity activity) {
                if (sStarted > 0 && --sStarted == 0) {
                    add(APP, SystemClock.elapsedRealtime() - sAppSince);
                    sAppSince = 0;
                }
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivityResumed(@NonNull Activity activity) { }
            @Override public void onActivityPaused(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }

    /** The player started or stopped playing (background and PiP included). */
    public static void playing(boolean playing) {
        long now = SystemClock.elapsedRealtime();
        if (playing && sWatchSince == 0) {
            sWatchSince = now;
        } else if (!playing && sWatchSince != 0) {
            add(WATCH, now - sWatchSince);
            sWatchSince = 0;
        }
    }

    public static void videoOpened() {
        add(VIDEOS, 1);
    }

    // ponytail: a session is booked to the day it ends on; split at midnight if that ever matters.
    private static void add(String kind, long value) {
        if (sPrefs == null || value <= 0) return;
        String key = kind + day(0);
        sPrefs.edit().putLong(key, sPrefs.getLong(key, 0) + value).apply();
    }

    /** App time in ms, {@code daysAgo} days back (0 = today), the running session included. */
    public static long appMs(int daysAgo) {
        return get(APP, daysAgo) + (daysAgo == 0 && sAppSince != 0 ? SystemClock.elapsedRealtime() - sAppSince : 0);
    }

    public static long watchMs(int daysAgo) {
        return get(WATCH, daysAgo) + (daysAgo == 0 && sWatchSince != 0 ? SystemClock.elapsedRealtime() - sWatchSince : 0);
    }

    public static long videos(int daysAgo) {
        return get(VIDEOS, daysAgo);
    }

    private static long get(String kind, int daysAgo) {
        return sPrefs != null ? sPrefs.getLong(kind + day(daysAgo), 0) : 0;
    }

    /** "1h 12m", "12m". */
    public static String duration(long ms) {
        long minutes = ms / 60000;
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    static String day(int daysAgo) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(calendar.getTime());
    }

    /** One-letter weekday for a bar, {@code daysAgo} days back, in the phone's language. */
    public static String weekday(int daysAgo) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return new SimpleDateFormat("EEEEE", Locale.getDefault()).format(new Date(calendar.getTimeInMillis()));
    }

    /** Days with anything recorded. */
    public static int daysKept() {
        if (sPrefs == null) return 0;
        java.util.Set<String> days = new java.util.HashSet<>();
        for (String key : sPrefs.getAll().keySet()) days.add(key.substring(1));
        return days.size();
    }

    /** Deletes every stat; a running session starts counting again from now. */
    public static void clear() {
        if (sPrefs == null) return;
        sPrefs.edit().clear().apply();
        long now = SystemClock.elapsedRealtime();
        if (sAppSince != 0) sAppSince = now;
        if (sWatchSince != 0) sWatchSince = now;
    }

    private static void prune() {
        String oldest = day(KEEP_DAYS);
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : sPrefs.getAll().entrySet()) {
            if (entry.getKey().substring(1).compareTo(oldest) < 0) {
                if (editor == null) editor = sPrefs.edit();
                editor.remove(entry.getKey());
            }
        }
        if (editor != null) editor.apply();
    }
}
