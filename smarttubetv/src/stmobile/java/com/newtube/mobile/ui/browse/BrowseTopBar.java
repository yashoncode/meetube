package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(you-subscreen): Browse's top bar. On a bottom-nav tab it is the "NewTube" wordmark; a
 * section opened from a You row (Sports, Playlists, ...) is a sub-screen of You, so it gets a back
 * arrow and the section's name - like YouTube's You sub-pages - instead of a wordmark that gave
 * no hint where the user was or that back returns to You.
 */
final class BrowseTopBar {
    private static final float WORDMARK_SP = 34f;
    private static final float SUBSCREEN_TITLE_SP = 28f;

    private final TextView mTitle;
    private final View mBack;
    private final int mWordmarkInset;
    private final int mBesideBackInset;

    BrowseTopBar(Activity activity, Runnable onBack) {
        mTitle = activity.findViewById(R.id.mobile_title_bar);
        mBack = activity.findViewById(R.id.mobile_title_back);
        mBack.setOnClickListener(v -> onBack.run());
        mWordmarkInset = mTitle.getPaddingStart();
        mBesideBackInset = activity.getResources()
                .getDimensionPixelSize(R.dimen.mobile_dialog_title_inset_with_back);
    }

    /**
     * A tab shows the wordmark; a sub-screen shows the back arrow and {@code title} (the wordmark
     * text if the section came without one - the arrow is what matters).
     */
    void show(boolean subScreen, CharSequence title) {
        mBack.setVisibility(subScreen ? View.VISIBLE : View.GONE);
        // METUBE: tabs carry no page title (the owner wants just the brand row and the nav bar);
        // a sub-screen keeps its back arrow and name, the only way to tell where back goes.
        ((View) mTitle.getParent()).setVisibility(subScreen ? View.VISIBLE : View.GONE);
        // METUBE: an iOS-style large title naming the tab (the brand sits in the row above).
        mTitle.setText(title != null && title.length() > 0
                ? title : mTitle.getContext().getString(R.string.app_name));
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subScreen ? SUBSCREEN_TITLE_SP : WORDMARK_SP);
        mTitle.setPaddingRelative(subScreen ? mBesideBackInset : mWordmarkInset,
                mTitle.getPaddingTop(), mTitle.getPaddingEnd(), mTitle.getPaddingBottom());
    }
}
