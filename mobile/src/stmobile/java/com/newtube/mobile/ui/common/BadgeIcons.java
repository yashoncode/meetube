package com.newtube.mobile.ui.common;

import android.content.Context;

/** MEETUBE(badge icons): whether time pills show the Mix waves and the music note. On by default. */
public final class BadgeIcons {
    private static final String PREFS = "meetube_app";
    private static final String KEY_SHOWN = "badge_icons";

    private BadgeIcons() {
    }

    public static boolean isShown(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, true);
    }

    public static void setShown(Context context, boolean shown) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, shown).apply();
    }
}
