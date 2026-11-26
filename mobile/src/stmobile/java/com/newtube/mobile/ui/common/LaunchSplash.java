package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Animatable;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(launch): the launch animation (one of the avd_splash_* drawables, picked in Settings >
 * Customize theme > Launch animation), played over Home's first frame once per process. Not the
 * system splash's animated icon: vivo's launcher shows its own starting window and never plays it
 * (YouTube animates in-app the same way).
 */
public final class LaunchSplash {
    public static final int OFF = 0;
    public static final int GLASS_TILE = 1;
    public static final int GLASS_SHADOW = 2;
    public static final int TRACE = 3;
    public static final int PILL = 4;
    public static final int RIPPLE = 5;
    private static final String PREFS = "launch_splash";
    private static final String STYLE = "style";
    private static final long PLAY_MS = 480; // every avd_splash_*'s length
    private static final long FADE_MS = 180;
    private static boolean sPlayed;

    private LaunchSplash() {
    }

    public static int style(Context context) {
        return prefs(context).getInt(STYLE, GLASS_TILE);
    }

    public static void setStyle(Context context, int style) {
        prefs(context).edit().putInt(STYLE, style).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int drawable(int style) {
        switch (style) {
            case GLASS_SHADOW: return R.drawable.avd_splash_shadow;
            case TRACE: return R.drawable.avd_splash_trace;
            case PILL: return R.drawable.avd_splash_pill;
            case RIPPLE: return R.drawable.avd_splash_ripple;
            default: return R.drawable.avd_splash_glass;
        }
    }

    public static void play(Activity activity) {
        if (sPlayed) {
            return;
        }
        sPlayed = true;
        int style = style(activity);
        if (style == OFF) {
            return;
        }
        ImageView splash = new ImageView(activity);
        splash.setBackgroundColor(ContextCompat.getColor(activity, R.color.mobile_color_background));
        splash.setScaleType(ImageView.ScaleType.CENTER);
        splash.setImageResource(drawable(style));
        splash.setClickable(true); // nothing under it takes a tap before it is gone
        ((ViewGroup) activity.getWindow().getDecorView()).addView(splash,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((Animatable) splash.getDrawable()).start();
        splash.animate().alpha(0f).setStartDelay(PLAY_MS).setDuration(FADE_MS).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> ((ViewGroup) splash.getParent()).removeView(splash));
    }
}
