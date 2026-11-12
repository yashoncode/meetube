package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * MEETUBE(progress colour): the played line's palette ({@link Accent}'s), one for videos (the
 * player's seek bar and the Home preview) and one for Shorts. {@link #DEFAULT}: videos follow the
 * accent, Shorts stay white.
 */
public final class ProgressColor {
    public static final int DEFAULT = -1;

    private static Integer sVideo;
    private static Integer sShorts;

    private ProgressColor() {
    }

    public static int video(Context context) {
        if (sVideo == null) {
            sVideo = valid(prefs(context).getInt("progress_palette_video", DEFAULT));
        }
        return sVideo;
    }

    public static void setVideo(Context context, int palette) {
        sVideo = palette;
        prefs(context).edit().putInt("progress_palette_video", palette).apply();
    }

    public static int shorts(Context context) {
        if (sShorts == null) {
            sShorts = valid(prefs(context).getInt("progress_palette_shorts", DEFAULT));
        }
        return sShorts;
    }

    public static void setShorts(Context context, int palette) {
        sShorts = palette;
        prefs(context).edit().putInt("progress_palette_shorts", palette).apply();
    }

    /** The video line's start and end colours. */
    public static int[] videoColors(Context context) {
        int i = video(context);
        if (i == DEFAULT) {
            i = Accent.index(context);
        }
        return new int[] {Accent.START[i], Accent.END[i]};
    }

    /** The Shorts line's start and end colours. */
    public static int[] shortsColors(Context context) {
        int i = shorts(context);
        return i == DEFAULT ? new int[] {Color.WHITE, Color.WHITE} : new int[] {Accent.START[i], Accent.END[i]};
    }

    /** Changes whenever the video line's colours do (its pick, or the accent it follows). */
    public static int videoKey(Context context) {
        return video(context) * 31 + Accent.key(context);
    }

    private static int valid(int i) {
        return i >= 0 && i < Accent.CUSTOM ? i : DEFAULT; // the custom colour is the accent's only
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("meetube_app", Context.MODE_PRIVATE);
    }
}
