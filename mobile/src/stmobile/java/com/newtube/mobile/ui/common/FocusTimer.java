package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(focus): a Pomodoro-style focus timer, started from the greeting's Focus pill. When the
 * minutes run out, the screen in front asks "Are ya winning, son?": back to work, or 5 more min.
 * The end is a wall-clock time in prefs, so it survives the app being closed; one that ran out
 * while MeeTube was away asks on the next screen that comes up.
 */
public final class FocusTimer {
    private static final String PREFS = "meetube_app";
    private static final String KEY_END = "focus_end";
    private static final String KEY_MINUTES = "focus_minutes";
    private static final String KEY_SNOOZES = "focus_snoozes";
    private static final int[] MINUTES = {15, 25, 45, 60};
    private static final int SNOOZE_MIN = 5;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sFire = FocusTimer::fireIfDue;
    @Nullable private static Activity sResumed;
    private static Context sApp;

    private FocusTimer() {
    }

    public static void install(@NonNull Application app) {
        sApp = app;
        schedule();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity activity) {
                sResumed = activity;
                fireIfDue();
            }

            @Override public void onActivityPaused(@NonNull Activity activity) {
                if (sResumed == activity) {
                    sResumed = null;
                }
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }

    /** The picked length, in minutes. */
    public static int minutes() {
        return prefs().getInt(KEY_MINUTES, 25);
    }

    /** 15 -> 25 -> 45 -> 60 -> 15. */
    public static void nextMinutes() {
        int now = minutes();
        int next = MINUTES[0];
        for (int i = 0; i < MINUTES.length - 1; i++) {
            if (MINUTES[i] == now) {
                next = MINUTES[i + 1];
            }
        }
        prefs().edit().putInt(KEY_MINUTES, next).apply();
    }

    /** A length the person typed (long press on the pill), 1 to 600 min. */
    public static void setMinutes(int minutes) {
        prefs().edit().putInt(KEY_MINUTES, Math.max(1, Math.min(600, minutes))).apply();
    }

    /** Milliseconds left, or -1 when no timer runs. */
    public static long remainingMs() {
        long end = prefs().getLong(KEY_END, 0);
        return end == 0 ? -1 : Math.max(0, end - System.currentTimeMillis());
    }

    public static void start() {
        prefs().edit().putInt(KEY_SNOOZES, 0).apply();
        run(minutes());
    }

    public static void stop() {
        prefs().edit().putLong(KEY_END, 0).apply();
        sHandler.removeCallbacks(sFire);
    }

    private static void run(int minutes) {
        prefs().edit().putLong(KEY_END, System.currentTimeMillis() + minutes * 60_000L).apply();
        schedule();
    }

    private static void schedule() {
        sHandler.removeCallbacks(sFire);
        long left = remainingMs();
        if (left >= 0) {
            sHandler.postDelayed(sFire, left);
        }
    }

    private static void fireIfDue() {
        Activity activity = sResumed;
        if (remainingMs() != 0 || activity == null || activity.isFinishing()) {
            return; // not running, not yet, or nothing in front: the next resumed screen asks
        }
        stop();
        int snoozes = prefs().getInt(KEY_SNOOZES, 0);
        int minutes = snoozes > 0 ? SNOOZE_MIN : minutes();
        frosted(new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_focus_title)
                .setMessage(activity.getResources().getQuantityString(R.plurals.mobile_focus_message, minutes, minutes))
                .setPositiveButton(R.string.mobile_focus_win, (d, w) ->
                        MobileSnackbar.show(activity, R.string.mobile_focus_won))
                .setNegativeButton(R.string.mobile_focus_snooze, (d, w) -> {
                    prefs().edit().putInt(KEY_SNOOZES, snoozes + 1).apply();
                    run(SNOOZE_MIN);
                    MobileSnackbar.show(activity, snoozes > 0
                            ? R.string.mobile_focus_snoozed_again : R.string.mobile_focus_snoozed);
                })).show();
    }

    /**
     * The Timer's dialogs as frosted glass: the page behind blurs and the card lets it through.
     * Where the system can't blur across windows (before Android 12, its own battery saver) or
     * our Battery saving mode is on, a plain dialog.
     */
    public static androidx.appcompat.app.AlertDialog frosted(MaterialAlertDialogBuilder builder) {
        Context context = builder.getContext();
        boolean blur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !BatterySaver.lite()
                && context.getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled();
        float dp = context.getResources().getDisplayMetrics().density;
        if (blur) {
            android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
            card.setCornerRadius(28 * dp);
            // MEETUBE(glass): the grey surface at 72% washed the blurred page out to milk on the dark
            // theme; a black tint keeps it dark glass, as the search bar.
            card.setColor(androidx.core.content.ContextCompat.getColor(context, R.color.meetube_dialog_glass_tint));
            card.setStroke(Math.max(1, Math.round(dp)), androidx.core.content.ContextCompat.getColor(context,
                    R.color.meetube_frost_tint));
            builder.setBackground(card);
        }
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        if (blur && dialog.getWindow() != null) {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            dialog.getWindow().getAttributes().setBlurBehindRadius(Math.round(24 * dp));
            boolean night = (context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            dialog.getWindow().setDimAmount(night ? 0.4f : 0.2f);
        }
        return dialog;
    }

    private static SharedPreferences prefs() {
        return sApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
