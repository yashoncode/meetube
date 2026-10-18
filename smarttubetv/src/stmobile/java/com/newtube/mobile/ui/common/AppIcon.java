package com.newtube.mobile.ui.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * METUBE(app icon): the launcher icon picked in Settings > Customization. Each icon is an
 * {@code activity-alias} of SplashActivity (stmobile AndroidManifest, com.newtube.mobile.icon.*) and
 * exactly one is enabled.
 *
 * <p>Switching aliases makes Android close the task the old one started, and the system then refuses
 * to reopen the app from the background. So a pick is only remembered here and applied when the app
 * leaves the screen ({@link #applyPending}, from onTrimMemory(UI_HIDDEN)) - nothing closes in front
 * of the user.
 */
public final class AppIcon {
    /** Alias names; the first is the default (the 2.9.9 icon). */
    public static final String DEFAULT = "Default";
    public static final String CLASSIC = "Classic";
    private static final String[] ICONS = {DEFAULT, CLASSIC};
    private static final String PREFIX = "com.newtube.mobile.icon.";
    private static final String PREFS = "metube_app_icon";
    private static final String KEY_PENDING = "pending";

    private AppIcon() {
    }

    /** The icon Settings shows: a pick waiting to be applied, else the enabled alias. */
    public static String current(Context context) {
        String pending = prefs(context).getString(KEY_PENDING, null);
        return pending != null ? pending : enabled(context);
    }

    public static void set(Context context, String icon) {
        if (icon.equals(current(context))) {
            return;
        }
        prefs(context).edit().putString(KEY_PENDING, icon.equals(enabled(context)) ? null : icon).apply();
        Toast.makeText(context, R.string.mobile_app_icon_pending, Toast.LENGTH_LONG).show();
    }

    /** Enables the picked alias first, then disables the other, so a launcher entry always exists. */
    public static void applyPending(Context context) {
        String icon = prefs(context).getString(KEY_PENDING, null);
        if (icon == null) {
            return;
        }
        prefs(context).edit().remove(KEY_PENDING).apply();
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(component(context, icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (String other : ICONS) {
            if (!other.equals(icon)) {
                pm.setComponentEnabledSetting(component(context, other),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
    }

    private static String enabled(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String icon : ICONS) {
            int state = pm.getComponentEnabledSetting(component(context, icon));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? DEFAULT.equals(icon)
                    : state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return icon;
            }
        }
        return DEFAULT;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ComponentName component(Context context, String icon) {
        return new ComponentName(context.getPackageName(), PREFIX + icon);
    }
}
