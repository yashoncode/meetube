package com.newtube.mobile.ui.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * MEETUBE(sleep-timer): the player sheet's YouTube-style sleep timer - pause after N minutes, or at
 * the end of the current video. Session-only (never persisted) and process-wide, so it outlives a
 * recreated player screen. The countdown runs on the main looper: the player's wake lock keeps the
 * CPU up while audio plays, and once nothing plays there is nothing left to pause.
 */
public final class SleepTimer {
    public static final int OFF = 0;
    static final int END_OF_VIDEO = -1;
    /** The sheet's choices, in minutes. */
    static final int[] PRESETS = {OFF, 10, 15, 20, 30, 45, 60, 90, END_OF_VIDEO};

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable EXPIRE = SleepTimer::expire;

    private static long sDeadlineMs; // elapsedRealtime; 0 = no countdown
    private static boolean sEndOfVideo;
    private static int sPicked = OFF;
    /** MEETUNE(greeting): the last length picked anywhere (song menu or greeting), the greeting pill's idle pick. */
    private static int sLastMinutes = 30;
    @Nullable private static ExoPlayer sPlayer;
    @Nullable private static Runnable sPause; // the live player's pause

    private SleepTimer() {
    }

    static void attach(ExoPlayer player, Runnable pause) {
        sPlayer = player;
        sPause = pause;
        player.setPauseAtEndOfMediaItems(sEndOfVideo);
    }

    static void detach(ExoPlayer player) {
        if (sPlayer == player) {
            sPlayer = null;
            sPause = null;
        }
    }

    /** Minutes, {@link #OFF} or {@link #END_OF_VIDEO}; replaces any running timer. */
    public static void set(int minutes) {
        HANDLER.removeCallbacks(EXPIRE);
        sDeadlineMs = 0;
        sEndOfVideo = minutes == END_OF_VIDEO;
        sPicked = minutes;
        if (minutes > 0) {
            sLastMinutes = minutes;
        }
        // MEETUNE(sleep-timer): the greeting can cancel an end-of-song pick while the player is docked.
        if (sPlayer != null) {
            sPlayer.setPauseAtEndOfMediaItems(sEndOfVideo);
        }
        if (minutes > 0) {
            long delayMs = minutes * 60_000L;
            sDeadlineMs = SystemClock.elapsedRealtime() + delayMs;
            HANDLER.postDelayed(EXPIRE, delayMs);
        }
    }

    public static int lastMinutes() {
        return sLastMinutes;
    }

    public static void setLastMinutes(int minutes) {
        sLastMinutes = minutes;
    }

    public static boolean isEndOfVideo() {
        return sEndOfVideo;
    }

    /** What the sheet marks as selected: the running preset, {@link #END_OF_VIDEO} or {@link #OFF}. */
    static int picked() {
        return sEndOfVideo || sDeadlineMs > 0 ? sPicked : OFF;
    }

    /** Milliseconds left on a timed countdown, or -1 when there is none. */
    public static long remainingMs() {
        return sDeadlineMs == 0 ? -1 : Math.max(0, sDeadlineMs - SystemClock.elapsedRealtime());
    }

    /** Whole minutes left (rounded up), 0 when no countdown runs. */
    static int minutesLeft() {
        long left = remainingMs();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** The player paused at the end of the video for {@link #END_OF_VIDEO}: the timer is done. */
    static void onPausedAtEnd() {
        if (sEndOfVideo) {
            expire();
        }
    }

    private static void expire() {
        set(OFF);
        if (sPause != null) {
            sPause.run();
        }
    }
}
