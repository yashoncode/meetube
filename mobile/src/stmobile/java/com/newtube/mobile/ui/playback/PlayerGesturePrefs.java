package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE(gestures): the switches for the player's optional swipes (issue #12), both on by default:
 * up and down on the sides of the fullscreen video set the brightness (left) and the volume
 * (right), and sideways anywhere on the video seeks. The fullscreen swipes (up into it, down out
 * of it) and swipe down to minimize are YouTube's own and always on.
 *
 * <p>A private preferences file, like the mobile flavor's other one-off stores (CastPrefs,
 * ThemeMode). The player reads the switches when a swipe starts, so a change applies to the next
 * swipe without a restart.</p>
 */
public final class PlayerGesturePrefs {

    private static final String PREFS_NAME = "newtube_gestures";
    private static final String KEY_LEVEL_SWIPES = "level_swipes";
    private static final String KEY_SEEK_SWIPE = "seek_swipe";
    private static final String KEY_BRIGHTNESS_SWIPE = "brightness_swipe";
    private static final String KEY_VOLUME_SWIPE = "volume_swipe";

    private PlayerGesturePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * MEETUBE(issue #333): swipe up and down on the left of the fullscreen video for brightness, on
     * the right for volume, each its own switch. Both start from the old shared one (KEY_LEVEL_SWIPES).
     */
    public static boolean isBrightnessSwipeOn(Context context) {
        return prefs(context).getBoolean(KEY_BRIGHTNESS_SWIPE, levelSwipes(context));
    }

    public static void setBrightnessSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_BRIGHTNESS_SWIPE, on).apply();
    }

    public static boolean isVolumeSwipeOn(Context context) {
        return prefs(context).getBoolean(KEY_VOLUME_SWIPE, levelSwipes(context));
    }

    public static void setVolumeSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_VOLUME_SWIPE, on).apply();
    }

    private static boolean levelSwipes(Context context) {
        return prefs(context).getBoolean(KEY_LEVEL_SWIPES, true);
    }

    /** Swipe sideways on the video to seek. */
    public static boolean isSeekSwipeOn(Context context) {
        return prefs(context).getBoolean(KEY_SEEK_SWIPE, true);
    }

    public static void setSeekSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SEEK_SWIPE, on).apply();
    }
}
