package com.newtube.mobile.ui.common;

import android.content.Context;

/** MEETUBE(seek pill): double-tap seek shows the count pill (default) or the original ripple. */
public final class SeekStyle {
    private static final String PREFS = "meetube_app";
    private static final String KEY_PILL = "seek_pill";

    private SeekStyle() {
    }

    public static boolean isPill(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PILL, true);
    }

    public static void setPill(Context context, boolean pill) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PILL, pill).apply();
    }
}
