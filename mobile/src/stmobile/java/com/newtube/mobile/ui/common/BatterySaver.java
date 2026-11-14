package com.newtube.mobile.ui.common;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(issue #93): with the phone's Battery Saver on, the effects can go - no animations (as the
 * phone's own "Remove animations": every ValueAnimator and spring jumps to its end), and glass is a
 * plain tint (no live blur, lens or frosted player buttons). The first time Battery Saver is on, a
 * message asks once; Settings > Battery Saver changes the answer.
 */
public final class BatterySaver {
    private static final String PREFS = "meetube_app";
    private static final String KEY_MODE = "battery_saver_effects";
    private static final String KEY_ASKED = "battery_saver_asked";
    private static final String KEY_MANUAL = "battery_saver_manual";
    /** Battery Saver on: effects off. */
    public static final int LIGHT = 1;
    /** Battery Saver changes nothing (the default until the person says otherwise). */
    public static final int KEEP = 0;

    private static volatile boolean sLite;
    @Nullable private static Activity sResumed; // asks only on a screen in front, never as a bare Toast

    private BatterySaver() {
    }

    /** Effects are off right now: Battery Saver is on and the person chose that. */
    public static boolean lite() {
        return sLite;
    }

    public static int mode(@NonNull Context context) {
        return prefs(context).getInt(KEY_MODE, KEEP);
    }

    public static void setMode(@NonNull Context context, int mode) {
        prefs(context).edit().putInt(KEY_MODE, mode).putBoolean(KEY_ASKED, true).apply();
        update(context);
    }

    /** The app's own Battery saving mode: effects off whatever the phone's Battery Saver is. */
    public static boolean isManual(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_MANUAL, false);
    }

    public static void setManual(@NonNull Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_MANUAL, on).apply();
        update(context);
    }

    public static void install(@NonNull Application app) {
        update(app);
        androidx.core.content.ContextCompat.registerReceiver(app, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                update(context);
                if (sResumed != null) {
                    askOnce(sResumed); // turned on with the app open
                }
            }
        }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity activity) {
                sResumed = activity;
                askOnce(activity);
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityPaused(@NonNull Activity activity) {
                if (sResumed == activity) {
                    sResumed = null;
                }
            }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }

    /** The first time Battery Saver is on: offer to turn the effects off; never asked again. */
    private static void askOnce(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!powerSave(context) || sLite || prefs.getBoolean(KEY_ASKED, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply();
        Context app = context.getApplicationContext();
        MobileSnackbar.show(context, app.getString(R.string.mobile_battery_saver_ask),
                app.getString(R.string.mobile_battery_saver_ask_action),
                () -> setMode(app, LIGHT), MobileSnackbar.NOTICE_DURATION_MS);
    }

    private static void update(Context context) {
        boolean lite = isManual(context) || (powerSave(context) && mode(context) == LIGHT);
        if (lite == sLite) {
            return;
        }
        sLite = lite;
        // Off: back to the phone's own scale (its Developer options setting, 1 by default).
        float scale = lite ? 0f : Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        try {
            // ponytail: hidden API (unsupported list, open to apps); the springs and glass checks hold if it ever closes.
            ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, scale);
        } catch (Throwable e) {
            android.util.Log.w("BatterySaver", "duration scale: " + e);
        }
    }

    private static boolean powerSave(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isPowerSaveMode();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
