package com.newtube.mobile.ui.common;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * METUBE(nav hide, issue #37): YouTube's bottom bar. Scrolling a list down slides the floating nav
 * pill off the bottom; scrolling back up, or reaching the top, brings it back. Only a translation:
 * the pill keeps its layout, so nothing under it moves and its taps and gestures stay as they were.
 */
public final class NavHider extends RecyclerView.OnScrollListener {
    private static final long DURATION_MS = 220;

    private final View mBar;
    private final int mSlopPx;
    private boolean mHidden;
    private int mTravel; // signed scroll distance since the last change of direction

    public NavHider(View bar) {
        mBar = bar;
        mSlopPx = Math.round(24 * bar.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
        if (!list.canScrollVertically(-1)) {
            show();
            return;
        }
        if (dy == 0) {
            return;
        }
        mTravel = Integer.signum(dy) == Integer.signum(mTravel) ? mTravel + dy : dy;
        if (mTravel > mSlopPx) {
            hide();
        } else if (mTravel < -mSlopPx) {
            show();
        }
    }

    /** A new page, or the list back at its top: the bar again. */
    public void show() {
        mTravel = 0;
        if (!mHidden) {
            return;
        }
        mHidden = false;
        mBar.animate().cancel();
        mBar.animate().translationY(0f).alpha(1f).setDuration(DURATION_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hide() {
        if (mHidden) {
            return;
        }
        mHidden = true;
        int margin = mBar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) mBar.getLayoutParams()).bottomMargin : 0;
        mBar.animate().cancel();
        mBar.animate().translationY(mBar.getHeight() + margin).alpha(0f).setDuration(DURATION_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
}
