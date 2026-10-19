package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;

/**
 * METUBE(nav tabs, issue #7): the bottom bar is Home | tab | Search | tab | You. Home, Search and
 * You are fixed; the two tabs between them are picked in Settings > Customization > Tabs, each one
 * of {@link #CHOICES} or {@link #NONE}. Picking the tab the other slot holds swaps the two.
 */
public final class NavTabs {
    public static final int NONE = -1;
    public static final int BEFORE_SEARCH = 0;
    public static final int AFTER_SEARCH = 1;
    /** What a slot can hold, in the order Settings lists them. */
    public static final int[] CHOICES = {MediaGroup.TYPE_SHORTS, MediaGroup.TYPE_SUBSCRIPTIONS,
            VideoDownloads.SECTION_ID, MediaGroup.TYPE_HISTORY};
    private static final String PREFS = "metube_nav_tabs";
    private static final String[] KEYS = {"before_search", "after_search"};
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

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
