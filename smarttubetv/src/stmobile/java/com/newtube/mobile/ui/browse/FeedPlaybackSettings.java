package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

/** Persistent policy for autoplaying full videos inside feed thumbnails. */
public final class FeedPlaybackSettings {
    public static final int ALWAYS = 0;
    public static final int WIFI_ONLY = 1;
    public static final int OFF = 2;

    private static final String KEY_MODE = "mobile_feed_playback_mode";

    private FeedPlaybackSettings() {
    }

    public static int getMode(Context context) {
        return preferences(context).getInt(KEY_MODE, WIFI_ONLY);
    }

    public static void setMode(Context context, int mode) {
        if (mode < ALWAYS || mode > OFF) {
            throw new IllegalArgumentException("Unknown feed playback mode: " + mode);
        }
        preferences(context).edit().putInt(KEY_MODE, mode).apply();
    }

    public static boolean isAllowed(Context context) {
        int mode = getMode(context);
        if (mode == OFF) {
            return false;
        }
        return mode == ALWAYS || isWifiConnected(context);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    private static boolean isWifiConnected(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active != null ? manager.getNetworkCapabilities(active) : null;
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
}
