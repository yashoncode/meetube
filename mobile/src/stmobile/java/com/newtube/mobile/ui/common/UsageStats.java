package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.newtube.mobile.ui.meetune.MeeTune;
import com.newtube.mobile.ui.meetune.MeeTuneActivity;
import com.newtube.mobile.ui.playback.MobilePlaybackActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MEETUBE(stats): daily on-screen time, playback time and opens for MeeTube and MeeTune.
 * Kept on this phone only (prefs "meetube_usage", the last 60 days); each greeting shows its side.
 */
public final class UsageStats {
    private static final String PREFS = "meetube_usage";
    private static final String APP = "a";
    private static final String WATCH = "w";
    private static final String VIDEOS = "v";
    private static final String MEETUNE = "m";
    private static final String LISTEN = "l";
    private static final String SONGS = "s";
    private static final int KEEP_DAYS = 60;

    private static SharedPreferences sPrefs;
    // Started activities only, most recently started or resumed last (including PiP transitions).
    private static final List<Activity> sStarted = new ArrayList<>();
    private static final Session sScreen = new Session();
    private static final Session sPlayback = new Session();

    private static final class Session {
        private String kind;
        private long since;

        void setKind(@Nullable String next) {
            if (Objects.equals(kind, next)) return;
            checkpoint(SystemClock.elapsedRealtime());
            kind = next;
        }

        void checkpoint(long now) {
            if (kind != null) add(kind, now - since);
            since = now;
        }

        long ms(String bucket, int daysAgo) {
            long running = daysAgo == 0 && bucket.equals(kind) ? SystemClock.elapsedRealtime() - since : 0;
            return get(bucket, daysAgo) + running;
        }
    }

    private UsageStats() {
    }

    public static void install(Application app) {
        sPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prune();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(@NonNull Activity activity) {
                sStarted.add(activity);
                updateScreen();
            }

            @Override public void onActivityStopped(@NonNull Activity activity) {
                sStarted.remove(activity);
                updateScreen();
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivityResumed(@NonNull Activity activity) {
                sStarted.remove(activity);
                sStarted.add(activity);
                updateScreen();
            }
            @Override public void onActivityPaused(@NonNull Activity activity) {
                checkpoint();
            }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }

    /** MEETUNE(stats): the same player activity can switch sides without a lifecycle callback. */
    public static void updateScreen() {
        if (sStarted.isEmpty()) {
            sScreen.setKind(null);
            return;
        }
        Activity activity = sStarted.get(sStarted.size() - 1);
        boolean music = activity instanceof MeeTuneActivity || (activity instanceof MobilePlaybackActivity
                && MeeTune.isMusic(((MobilePlaybackActivity) activity).getVideo()));
        sScreen.setKind(music ? MEETUNE : APP);
    }

    /** The player started or stopped playing (background and PiP included). */
    public static void playing(boolean playing, boolean song) {
        sPlayback.setKind(playing ? (song ? LISTEN : WATCH) : null);
    }

    public static void videoOpened(boolean song) {
        updateScreen(); // The player may have learned this is a MeeTune song since setVideo.
        checkpoint();
        // A source change can leave isPlaying true. The checkpoint booked the previous item.
        if (sPlayback.kind != null) sPlayback.kind = song ? LISTEN : WATCH;
        add(song ? SONGS : VIDEOS, 1);
    }

    /**
     * Books the running app and watch sessions so far and keeps them running. Without it a process
     * killed in the foreground (an update, a crash, the system) lost the whole session: 4 videos, 0m.
     */
    private static void checkpoint() {
        long now = SystemClock.elapsedRealtime();
        sScreen.checkpoint(now);
        sPlayback.checkpoint(now);
    }

    // ponytail: a session is booked to the day it ends on; split at midnight if that ever matters.
    private static void add(String kind, long value) {
        if (sPrefs == null || value <= 0) return;
        String key = kind + day(0);
        sPrefs.edit().putLong(key, sPrefs.getLong(key, 0) + value).apply();
    }

    /** App time in ms, {@code daysAgo} days back (0 = today), the running session included. */
    public static long appMs(int daysAgo) {
        return sScreen.ms(APP, daysAgo);
    }

    public static long meeTuneMs(int daysAgo) {
        return sScreen.ms(MEETUNE, daysAgo);
    }

    public static long watchMs(int daysAgo) {
        return sPlayback.ms(WATCH, daysAgo);
    }

    public static long listenMs(int daysAgo) {
        return sPlayback.ms(LISTEN, daysAgo);
    }

    public static long videos(int daysAgo) {
        return get(VIDEOS, daysAgo);
    }

    public static long songs(int daysAgo) {
        return get(SONGS, daysAgo);
    }

    private static long get(String kind, int daysAgo) {
        return sPrefs != null ? sPrefs.getLong(kind + day(daysAgo), 0) : 0;
    }

    /** "1h 12m", "12m", "40s": under a minute in seconds, so a fresh day never reads 0m next to its videos. */
    public static String duration(long ms) {
        if (ms < 60000) return (ms / 1000) + "s";
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
        sScreen.since = now;
        sPlayback.since = now;
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
