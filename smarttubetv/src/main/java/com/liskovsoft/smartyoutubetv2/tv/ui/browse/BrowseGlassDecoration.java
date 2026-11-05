package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/** Decorations are attached to browse's existing views; shared layouts and themes stay untouched. */
final class BrowseGlassDecoration {
    private final View mHeaders;
    private final View mFade;
    private final View mGrid;
    private final int[] mGridPadding;
    private final boolean mGridClip;
    private final View mClock;
    private final View mTime;
    private final View mDate;
    private final Drawable mHeaderBackground;
    private final Drawable mClockBackground;
    private final int mFadeVisibility;
    private final int[] mHeaderPadding;
    private final int[] mClockPadding;
    private final float mHeaderElevation;
    private final ViewGroup mOrbRow;
    private boolean mEnabled;
    private boolean mClockStyled;
    private boolean mSidebarShown;
    private Drawable mPanel;
    private int mPanelInset = -1;
    private int mListPaddingRight = -1;

    BrowseGlassDecoration(View root, View headers) {
        mHeaders = headers;
        mFade = headers.findViewById(R.id.fade_out_edge);
        mGrid = headers.findViewById(R.id.browse_headers);
        mGridPadding = padding(mGrid);
        mGridClip = mGrid instanceof ViewGroup && ((ViewGroup) mGrid).getClipToPadding();
        if (mGrid instanceof RecyclerView) {
            ((RecyclerView) mGrid).setItemAnimator(new BrowseHeaderAnimator());
        }
        if (mGrid != null) {
            mGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updatePanel());
        }
        mTime = root.findViewById(R.id.global_time);
        mDate = root.findViewById(R.id.global_date);
        mClock = mTime != null ? (View) mTime.getParent() : null;
        mHeaderBackground = mHeaders != null ? mHeaders.getBackground() : null;
        mClockBackground = mClock != null ? mClock.getBackground() : null;
        mFadeVisibility = mFade != null ? mFade.getVisibility() : View.VISIBLE;
        mHeaderPadding = padding(mHeaders);
        mClockPadding = padding(mClock);
        mHeaderElevation = Build.VERSION.SDK_INT >= 21 && mHeaders != null ? mHeaders.getElevation() : 0;
        if (mClock != null) {
            // Checked before every frame: the title hides the time as INVISIBLE (no layout pass), which left an
            // empty pill; and a changed frame is skipped so the new padding is measured first (else "3:57 pm" -> ":57 pr").
            mClock.getViewTreeObserver().addOnPreDrawListener(() -> !updateClock());
        }
        View settingsOrb = root.findViewById(R.id.settings_orb);
        mOrbRow = settingsOrb != null ? (ViewGroup) settingsOrb.getParent() : null;
        if (mOrbRow != null) {
            // the row slides left of the title's start padding, which would clip it
            ((ViewGroup) mOrbRow.getParent()).setClipToPadding(false);
            // an orb shown or hidden (Settings, the account avatar) resizes the row: place it again (a plain
            // relayout, e.g. the clock ticking, must not snap a running slide)
            mOrbRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (l != ol || r != or) {
                    shiftOrbs(false);
                    updatePanel();
                }
            });
        }
    }

    void setSidebarShown(boolean shown, boolean animate) {
        mSidebarShown = shown;
        shiftOrbs(animate);
    }

    void setEnabled(boolean enabled) {
        boolean changed = mEnabled != enabled;
        mEnabled = enabled;
        if (mHeaders != null) {
            if (enabled) {
                updatePanel();
            } else {
                mHeaders.setBackground(mHeaderBackground);
                restorePadding(mHeaders, mHeaderPadding);
            }
            if (mGrid != null) {
                // MEETUBE: 20dp inside the floating card; keep Leanback's fixed top and scrolling.
                int listInset = mGrid.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_panel_inset) +
                        mGrid.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_panel_padding);
                int listTop = mGrid.getResources().getDimensionPixelSize(R.dimen.lb_browse_rows_margin_top);
                mGrid.setPadding(enabled ? listInset : mGridPadding[0], enabled ? listTop : mGridPadding[1],
                        enabled && mListPaddingRight >= 0 ? mListPaddingRight : mGridPadding[2],
                        enabled ? Math.max(mGridPadding[3], listInset) : mGridPadding[3]);
                ((ViewGroup) mGrid).setClipToPadding(enabled || mGridClip);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                mHeaders.setElevation(enabled ? 0 : mHeaderElevation);
            }
        }
        if (mFade != null) {
            mFade.setVisibility(enabled ? View.GONE : mFadeVisibility);
        }
        updateClock();
        if (changed) {
            shiftOrbs(false); // not on every call: browse re-applies glass per section, mid sidebar slide
        }
    }

    /**
     * With the sidebar open, the title's buttons (Settings, Search, account...) line up with the list's left edge,
     * 20dp inside the glass panel. Translation only, so the title's layout - shared with the sidebar-closed state,
     * where the buttons sit over the content - never changes.
     */
    private void shiftOrbs(boolean animate) {
        if (mOrbRow == null) {
            return;
        }
        View first = shownChild(mOrbRow, true);
        float target = 0;
        if (mEnabled && mSidebarShown && first != null && mOrbRow.getWidth() > 0) {
            // MEETUBE: use the panel's insets for both the orb row and the pills.
            int start = mOrbRow.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_panel_inset) +
                    mOrbRow.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_panel_padding);
            target = start - (mOrbRow.getLeft() + first.getLeft());
        }
        if (animate) {
            mOrbRow.animate().translationX(target).setDuration(250).setInterpolator(new DecelerateInterpolator()).start();
        } else if (mOrbRow.getTranslationX() != target) {
            mOrbRow.animate().cancel();
            mOrbRow.setTranslationX(target);
        }
    }

    /**
     * One width for everything on the panel: the buttons' row, Settings to the account, sets it. The list's right
     * padding ends every header's pill on the row's right edge (leanback wraps each header in a frame as wide as
     * the list, and the pill fills it), and the panel gives both sides 20dp of breathing room.
     */
    private void updatePanel() {
        if (mHeaders == null || !mEnabled) {
            return;
        }
        // MEETUBE: a dedicated sidebar card leaves the shared clock background untouched.
        int margin = mHeaders.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_panel_inset);
        int inner = mHeaders.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_panel_padding);
        int start = margin + inner;
        View first = mOrbRow != null && mOrbRow.getWidth() > 0 ? shownChild(mOrbRow, true) : null;
        View last = mOrbRow != null && mOrbRow.getWidth() > 0 ? shownChild(mOrbRow, false) : null;
        int rowWidth = first != null && last != null ? last.getRight() - first.getLeft() : 0;
        int right = rowWidth > 0 ? start + rowWidth : 0; // where shiftOrbs puts the row while the sidebar is open
        if (mGrid instanceof ViewGroup && right > 0 && mGrid.getWidth() > 0) {
            ViewGroup grid = (ViewGroup) mGrid;
            int paddingRight = Math.max(0, grid.getLeft() + grid.getWidth() - right);
            if (grid.getPaddingRight() != paddingRight) {
                mListPaddingRight = paddingRight;
                grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), paddingRight, grid.getPaddingBottom());
            }
            for (int i = 0; i < grid.getChildCount(); i++) {
                // and a pill that only wraps its label still reaches the edge
                View label = grid.getChildAt(i).findViewById(R.id.header_label);
                View pill = label != null ? (View) label.getParent() : null;
                if (pill != null && pill.getMinimumWidth() != rowWidth) {
                    pill.setMinimumWidth(rowWidth);
                }
            }
        }
        // the sidebar's width (lb_headers_fragment); nothing measured yet: the full width
        int width = mHeaders.getResources().getDimensionPixelSize(R.dimen.lb_browse_headers_width);
        int inset = right > 0 ? Math.max(0, width - right - inner) : margin;
        if (inset == mPanelInset && mPanel != null && mHeaders.getBackground() == mPanel) {
            return;
        }
        mPanelInset = inset;
        mPanel = new InsetDrawable(ContextCompat.getDrawable(mHeaders.getContext(), R.drawable.browse_sidebar_panel),
                margin, margin, inset, margin);
        mHeaders.setBackground(mPanel);
        // InsetDrawable must not move the grid or its focus targets.
        restorePadding(mHeaders, mHeaderPadding);
    }

    /** The first or last button on show: the row also holds INVISIBLE views (they take space, draw nothing). */
    private static View shownChild(ViewGroup group, boolean first) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(first ? i : count - 1 - i);
            if (child.getVisibility() == View.VISIBLE) {
                return child;
            }
        }
        return null;
    }

    private boolean updateClock() {
        if (mClock == null) {
            return false;
        }
        boolean visible = mTime.getVisibility() == View.VISIBLE ||
                (mDate != null && mDate.getVisibility() == View.VISIBLE);
        // MEETUBE: remove an unused clock pill and match the orbs' fixed 52dp title height.
        int visibility = visible ? View.VISIBLE : View.GONE;
        boolean changed = mClock.getVisibility() != visibility;
        if (changed) {
            mClock.setVisibility(visibility);
        }
        boolean style = mEnabled && visible;
        if (style == mClockStyled) {
            return changed;
        }
        mClockStyled = style;
        if (style) {
            mClock.setBackgroundResource(R.drawable.browse_glass_title_pill);
            float density = mClock.getResources().getDisplayMetrics().density;
            mClock.setPadding(Math.round(12 * density), Math.round(4 * density),
                    Math.round(12 * density), Math.round(4 * density));
        } else {
            mClock.setBackground(mClockBackground);
            restorePadding(mClock, mClockPadding);
        }
        return true;
    }

    private static int[] padding(View view) {
        return view == null ? new int[4] : new int[] {
                view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom()};
    }

    private static void restorePadding(View view, int[] padding) {
        view.setPadding(padding[0], padding[1], padding[2], padding[3]);
    }
}
