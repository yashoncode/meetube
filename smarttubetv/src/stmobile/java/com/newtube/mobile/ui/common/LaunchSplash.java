package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.graphics.drawable.Animatable;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(launch): the launch animation (avd_splash_glass, a play symbol turning to frosted glass),
 * played over Home's first frame once per process. Not the system splash's animated icon: vivo's
 * launcher shows its own starting window and never plays it (YouTube animates in-app the same way).
 */
public final class LaunchSplash {
    private static final long PLAY_MS = 800; // avd_splash_glass's length
    private static final long FADE_MS = 250;
    private static boolean sPlayed;

    private LaunchSplash() {
    }

    public static void play(Activity activity) {
        if (sPlayed) {
            return;
        }
        sPlayed = true;
        ImageView splash = new ImageView(activity);
        splash.setBackgroundColor(ContextCompat.getColor(activity, R.color.mobile_color_background));
        splash.setScaleType(ImageView.ScaleType.CENTER);
        splash.setImageResource(R.drawable.avd_splash_glass);
        splash.setClickable(true); // nothing under it takes a tap before it is gone
        ((ViewGroup) activity.getWindow().getDecorView()).addView(splash,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((Animatable) splash.getDrawable()).start();
        splash.animate().alpha(0f).setStartDelay(PLAY_MS).setDuration(FADE_MS).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> ((ViewGroup) splash.getParent()).removeView(splash));
    }
}
