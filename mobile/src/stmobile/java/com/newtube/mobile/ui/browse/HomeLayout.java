package com.newtube.mobile.ui.browse;

import android.content.Context;

/** MEETUBE(home-rows): Settings > General > Home layout - one feed (default) or SmartTube's rows. */
public final class HomeLayout {
    private static final String PREFS = "meetube_ui";
    private static final String KEY_ROWS = "home_rows";
    private static final String KEY_VIDEO_LIST = "video_list";
    private static final String KEY_SHORTS_SHELF = "home_shorts_shelf";

    private HomeLayout() {
    }

    public static boolean isRows(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROWS, false);
    }

    public static void setRows(Context context, boolean rows) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROWS, rows).apply();
    }

    /** MEETUBE(list layout, issue #82): Settings > Video layout - videos as cards (default) or as list rows. */
    public static boolean isVideoList(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VIDEO_LIST, false);
    }

    public static void setVideoList(Context context, boolean list) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VIDEO_LIST, list).apply();
    }

    /** Issue #92: pages that can have their own video layout, else follow {@link #isVideoList(Context)}. */
    public static final String PAGE_HOME = "home";
    public static final String PAGE_SUBSCRIPTIONS = "subscriptions";
    /** History, Downloads and the other tabs. */
    public static final String PAGE_LIBRARY = "library";
    /** Watch later, Liked videos and every other playlist. */
    public static final String PAGE_PLAYLISTS = "playlists";
    public static final String PAGE_CHANNELS = "channels";
    public static final String PAGE_SEARCH = "search";
    public static final String PAGE_UP_NEXT = "up_next";

    public static final int FOLLOW = -1;
    public static final int CARDS = 0;
    public static final int LIST = 1;

    /** This page's layout: its own when set, else the app-wide one. */
    public static boolean isVideoList(Context context, String page) {
        int own = videoLayout(context, page);
        return own == FOLLOW ? isVideoList(context) : own == LIST;
    }

    /** {@link #FOLLOW}, {@link #CARDS} or {@link #LIST}. */
    public static int videoLayout(Context context, String page) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VIDEO_LIST + "_" + page, FOLLOW);
    }

    public static void setVideoLayout(Context context, String page, int layout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_VIDEO_LIST + "_" + page, layout).apply();
    }

    /** MEETUBE(issue #43): Settings > Shorts on Home - Home's Shorts rows as a sideways shelf (default) or left out. */
    public static boolean isShortsShelf(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHORTS_SHELF, true);
    }

    public static void setShortsShelf(Context context, boolean shelf) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHORTS_SHELF, shelf).apply();
    }
}
