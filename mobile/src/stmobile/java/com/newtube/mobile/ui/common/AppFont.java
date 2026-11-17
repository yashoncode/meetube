package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;

import androidx.core.content.res.ResourcesCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(font): Settings > Customization > Font, for all of MeeTube and MeeTune. The pick is a
 * theme overlay (ThemeOverlay.MeeTube.Font.*) on every screen: text with no family of its own takes
 * its android:fontFamily, and styles and layouts that want medium or black text name
 * ?attr/appFontMedium / ?attr/appFontBlack. Text set or drawn in code asks {@link #regular},
 * {@link #medium} or {@link #bold}. Android 7 keeps the system font: its TextView can't read a font
 * resource from a theme.
 */
public final class AppFont {
    public static final String SYSTEM = "system";
    public static final String GOOGLE_SANS = "google_sans";
    public static final String MINECRAFT = "minecraft";
    public static final String VAZIRMATN = "vazirmatn";

    private static final String PREFS = "meetube_font";

    private AppFont() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    public static String current(Context context) {
        String font = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("font", SYSTEM);
        // A font no longer offered (SF Pro Display) reads as System default.
        return GOOGLE_SANS.equals(font) || MINECRAFT.equals(font) || VAZIRMATN.equals(font) ? font : SYSTEM;
    }

    public static void set(Context context, String font) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("font", font).apply();
    }

    public static void applyTo(Context context, Resources.Theme theme) {
        if (!isSupported()) {
            return;
        }
        String font = current(context);
        int overlay = GOOGLE_SANS.equals(font) ? R.style.ThemeOverlay_MeeTube_Font_GoogleSans
                : MINECRAFT.equals(font) ? R.style.ThemeOverlay_MeeTube_Font_Minecraft
                : VAZIRMATN.equals(font) ? R.style.ThemeOverlay_MeeTube_Font_Vazirmatn : 0;
        if (overlay != 0) {
            theme.applyStyle(overlay, true);
        }
    }

    public static Typeface regular(Context context) {
        return resolve(context, R.attr.appFontRegular, "sans-serif");
    }

    public static Typeface medium(Context context) {
        return resolve(context, R.attr.appFontMedium, "sans-serif-medium");
    }

    public static Typeface bold(Context context) {
        return Typeface.create(regular(context), Typeface.BOLD);
    }

    private static Typeface resolve(Context context, int attr, String system) {
        TypedValue value = new TypedValue();
        // A font resource resolves to its file path with a resource id; a system family to a bare string.
        if (isSupported() && context.getTheme().resolveAttribute(attr, value, true) && value.resourceId != 0) {
            try {
                Typeface font = ResourcesCompat.getFont(context, value.resourceId);
                if (font != null) {
                    return font;
                }
            } catch (Resources.NotFoundException ignored) {
                // fall through to the system family
            }
        }
        return Typeface.create(system, Typeface.NORMAL);
    }
}
