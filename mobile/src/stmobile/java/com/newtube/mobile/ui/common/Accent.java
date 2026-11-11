package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(accent): the app's accent palette. Each palette is a slight gradient (START to END) with
 * a name; the accent itself (section titles, switches, radios, the nav tab, badges) is its START,
 * through the theme attributes meetubeAccent / meetubeAccentDeep that the accent colour resources
 * read. Ember (the red) is the base theme's own; another is a theme overlay put on each screen,
 * like the loader's. The progress lines pick from the same palette ({@link ProgressColor}).
 */
public final class Accent {
    public static final int[] START = {0xFFFF453A, 0xFFFF2D87, 0xFFFF7A00, 0xFFFFC300, 0xFF1FD67A, 0xFF0A84FF, 0xFF8E5CFF, 0xFFF2F2F7};
    public static final int[] END = {0xFFFF2791, 0xFFFF7AC6, 0xFFFFB340, 0xFFFFE066, 0xFF7BF1A8, 0xFF40C8FF, 0xFFD17BFF, 0xFFC7D2FE};
    public static final int[] NAMES = {R.string.mobile_accent_ember, R.string.mobile_accent_sakura, R.string.mobile_accent_sunset, R.string.mobile_accent_honey, R.string.mobile_accent_jade, R.string.mobile_accent_ocean, R.string.mobile_accent_amethyst, R.string.mobile_accent_pearl};
    private static final int[] OVERLAYS = {0, R.style.ThemeOverlay_MeeTube_Accent_Sakura, R.style.ThemeOverlay_MeeTube_Accent_Sunset, R.style.ThemeOverlay_MeeTube_Accent_Honey, R.style.ThemeOverlay_MeeTube_Accent_Jade, R.style.ThemeOverlay_MeeTube_Accent_Ocean, R.style.ThemeOverlay_MeeTube_Accent_Amethyst, R.style.ThemeOverlay_MeeTube_Accent_Pearl};

    private static int sIndex = -1;

    private Accent() {
    }

    public static int index(Context context) {
        if (sIndex < 0) {
            int i = prefs(context).getInt("accent_palette", 0);
            sIndex = i >= 0 && i < START.length ? i : 0;
        }
        return sIndex;
    }

    public static void set(Context context, int index) {
        sIndex = index;
        prefs(context).edit().putInt("accent_palette", index).apply();
    }

    /** Puts the picked palette into {@code theme}; Ember is the base theme's own. */
    static void applyTo(Context context, Resources.Theme theme) {
        int i = index(context);
        if (i > 0) {
            theme.applyStyle(OVERLAYS[i], true);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("meetube_app", Context.MODE_PRIVATE);
    }
}
