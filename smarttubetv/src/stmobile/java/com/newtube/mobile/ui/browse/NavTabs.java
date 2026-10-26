package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;

/**
 * METUBE(nav tabs, issue #7): the bottom bar is Home | tab | tab | You with Search before, between
 * or after the two tabs ({@link #searchPosition}), or up in the top bar ({@link #searchOnTop}).
 * Home and You are fixed; the tabs are picked and the order dragged in Settings > Customization >
 * Tabs (BarEditor), each tab one of {@link #CHOICES} or {@link #NONE}. Picking the tab the other
 * slot holds swaps the two.
 */
public final class NavTabs {
    public static final int NONE = -1;
    public static final int FIRST = 0;
    public static final int SECOND = 1;
    /** What a slot can hold, in the order Settings lists them. */
    public static final int[] CHOICES = {MediaGroup.TYPE_SHORTS, MediaGroup.TYPE_SUBSCRIPTIONS,
            VideoDownloads.SECTION_ID, MediaGroup.TYPE_HISTORY};
    private static final String PREFS = "metube_nav_tabs";
    private static final String[] KEYS = {"before_search", "after_search"}; // the names from when Search sat between
    private static final String KEY_LABELS = "labels";
    private static final String KEY_SEARCH_ON_TOP = "search_on_top";
    private static final String KEY_SEARCH_POSITION = "search_position";
    /** The bar as it was before the tabs could be picked. */
    private static final int[] DEFAULTS = {MediaGroup.TYPE_SHORTS, VideoDownloads.SECTION_ID};

    private NavTabs() {
    }

    /** The section id in {@code slot}, or {@link #NONE}. */
    public static int get(Context context, int slot) {
        return prefs(context).getInt(KEYS[slot], DEFAULTS[slot]);
    }

    public static void set(Context context, int slot, int sectionId) {
        int other = 1 - slot;
        SharedPreferences.Editor editor = prefs(context).edit().putInt(KEYS[slot], sectionId);
        if (sectionId != NONE && get(context, other) == sectionId) {
            editor.putInt(KEYS[other], get(context, slot));
        }
        editor.apply();
    }

    /** METUBE(nav labels): Settings > Customization > Tab names - the names under the bar's icons. */
    public static boolean showLabels(Context context) {
        return prefs(context).getBoolean(KEY_LABELS, true);
    }

    public static void setShowLabels(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_LABELS, show).apply();
    }

    /** METUBE(search place): Settings > Customization > Tabs - Search in the top bar instead of the bottom one. */
    public static boolean searchOnTop(Context context) {
        return prefs(context).getBoolean(KEY_SEARCH_ON_TOP, false);
    }

    /** Where Search sits in the bottom bar: how many of the two tabs come before it (0 to 2). */
    public static int searchPosition(Context context) {
        return prefs(context).getInt(KEY_SEARCH_POSITION, 1);
    }

    /** The whole bar at once, as BarEditor's stack reads after a drag. */
    public static void setLayout(Context context, int first, int second, int searchPosition, boolean searchOnTop) {
        prefs(context).edit().putInt(KEYS[FIRST], first).putInt(KEYS[SECOND], second)
                .putInt(KEY_SEARCH_POSITION, searchPosition).putBoolean(KEY_SEARCH_ON_TOP, searchOnTop).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
