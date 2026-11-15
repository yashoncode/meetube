package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.NavBloom;

/**
 * MeeTube's floating glass tab bar as one piece, for screens other than the main one (MeeTune):
 * the system-inset fix, the glow (NavBloom), the tab-names setting, OG Mode docking, the liquid
 * lens a switch glides (NavLens) or the nudge without it, and a finger dragged along the bar.
 * Same constants and motion as MobileBrowseActivity's bar.
 * ponytail: MobileBrowseActivity still runs its own copy (Shorts, Search button and the held-tab
 * hint are woven into it); move it onto this class once MeeTune's bar has been lived with.
 */
public final class GlassNavBar {
    public interface Listener {
        void onChosen(int itemId);

        void onReselected(int itemId);
    }

    private final Activity mActivity;
    private final GlassView mBar;
    private final BottomNavigationView mNav;
    @Nullable
    private final NavLens mLens;
    private final Listener mListener;
    private boolean mSuppress;
    @Nullable
    private Drawable mPill;
    private float mDownX = Float.NaN;
    private boolean mDragging;
    private boolean mDragLens;
    private int mDragOver;
    private float mDragFromX = Float.NaN;

    public GlassNavBar(Activity activity, GlassView bar, BottomNavigationView nav, @Nullable NavLens lens,
            Listener listener) {
        mActivity = activity;
        mBar = bar;
        mNav = nav;
        mLens = lens;
        mListener = listener;
        // The pill floats above the system bar (content is already inset): Material pads it by
        // that inset too, whatever paddingBottomSystemWindowInsets says.
        ViewCompat.setOnApplyWindowInsetsListener(nav, (v, insets) -> insets);
        if (YouTubeMode.isOn(activity)) {
            YouTubeMode.dockBar(bar, null);
        } else {
            capWidth();
        }
        NavBloom.attach(nav);
        nav.setOnItemSelectedListener(item -> {
            if (!mSuppress) {
                Haptics.nav(nav, Float.isNaN(mDragFromX) ? 40 : 30, 255);
                int itemId = item.getItemId();
                if (playLens(itemId)) {
                    nav.postOnAnimation(() -> mListener.onChosen(itemId));
                } else {
                    if (bar.getScaleX() == 1f && !YouTubeMode.isOn(activity)) {
                        Motion.nudge(bar);
                    }
                    mListener.onChosen(itemId);
                }
            }
            return true;
        });
        nav.setOnItemReselectedListener(item -> {
            if (!mSuppress) {
                Haptics.nav(nav, 40, 255);
                mListener.onReselected(item.getItemId());
            }
        });
        syncLabels();
    }

    /** Settings > Tabs' tab names, as on MeeTube's bar; call from onResume. */
    public void syncLabels() {
        mNav.setLabelVisibilityMode(com.newtube.mobile.ui.browse.NavTabs.showLabels(mActivity)
                ? BottomNavigationView.LABEL_VISIBILITY_LABELED : BottomNavigationView.LABEL_VISIBILITY_UNLABELED);
    }

    /** Lights a tab without telling the listener. */
    public void select(int itemId) {
        mSuppress = true;
        mNav.setSelectedItemId(itemId);
        mSuppress = false;
    }

    /** meetubeNavMaxWidth: the bar's ConstraintLayout cap, for a bar in any other parent. */
    private void capWidth() {
        TypedArray a = mActivity.obtainStyledAttributes(new int[] {R.attr.meetubeNavMaxWidth});
        int max = a.getDimensionPixelSize(0, Integer.MAX_VALUE);
        a.recycle();
        View parent = (View) mBar.getParent();
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mBar.getLayoutParams();
            int room = r - l - lp.leftMargin - lp.rightMargin;
            int width = room > max ? max : ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp.width != width) {
                lp.width = width;
                mBar.post(() -> mBar.setLayoutParams(lp));
            }
        });
    }

    private boolean playLens(int toItemId) {
        View from = mNav.findViewById(mNav.getSelectedItemId());
        View to = mNav.findViewById(toItemId);
        if (!NavLens.enabled(mActivity) || YouTubeMode.isOn(mActivity)
                || from == null || to == null || from == to || to.getWidth() == 0) {
            return false;
        }
        boolean dragged = !Float.isNaN(mDragFromX);
        float fromX = dragged ? mDragFromX : NavLens.centreInBar(from, mBar);
        NavLens.jiggle(mBar, NavLens.centreInBar(to, mBar) > fromX ? 1 : -1);
        if (mLens == null || !NavLens.supported(mActivity)) {
            return true;
        }
        hidePill();
        mLens.play(mBar, fromX, to, dragged, () -> mNav.setItemBackground(mPill));
        return true;
    }

    private void hidePill() {
        if (mPill == null) {
            mPill = mNav.getItemBackground();
        }
        mNav.setItemBackground(null);
    }

    /** From the activity's dispatchTouchEvent: true when a drag along the bar took the event. */
    public boolean onTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDragging = false;
            mDownX = mNav.isShown() && isOn(mNav, event) && !YouTubeMode.isOn(mActivity) ? event.getRawX() : Float.NaN;
            return false;
        }
        if (Float.isNaN(mDownX)) {
            return false;
        }
        boolean end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (!mDragging) {
            if (end) {
                mDownX = Float.NaN;
            }
            if (action != MotionEvent.ACTION_MOVE
                    || Math.abs(event.getRawX() - mDownX) < ViewConfiguration.get(mActivity).getScaledTouchSlop()) {
                return false;
            }
            mDragging = true; // a drag now, not a tap: the tab under the finger lets go of the press
            Haptics.nav(mNav, 26, 220);
            mDragOver = View.NO_ID;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            mActivity.getWindow().superDispatchTouchEvent(cancel);
            cancel.recycle();
            popBar(true);
            mDragLens = mLens != null && NavLens.enabled(mActivity) && NavLens.supported(mActivity);
            if (mDragLens) {
                hidePill();
            }
        }
        int[] at = new int[2];
        mBar.getLocationOnScreen(at);
        float x = (event.getRawX() - at[0]) / mBar.getScaleX();
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int over = mDragOver;
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < mNav.getMenu().size(); i++) {
            MenuItem item = mNav.getMenu().getItem(i);
            View tab = mNav.findViewById(item.getItemId());
            if (!item.isVisible() || tab == null) {
                continue;
            }
            float centre = NavLens.centreInBar(tab, mBar);
            min = Math.min(min, centre);
            max = Math.max(max, centre);
            if (Math.abs(centre - x) < nearest) {
                nearest = Math.abs(centre - x);
                over = item.getItemId();
            }
        }
        x = Math.max(min, Math.min(max, x));
        if (over != mDragOver) {
            popTab(mDragOver, false);
            popTab(over, true);
            mDragOver = over;
            Haptics.nav(mNav, 24, 220);
        }
        if (mNav.getBackground() instanceof NavBloom) {
            int[] nav = new int[2];
            mNav.getLocationOnScreen(nav);
            ((NavBloom) mNav.getBackground()).follow(end ? Float.NaN : x - (nav[0] - at[0]) / mBar.getScaleX(), over);
        }
        View lit = mNav.findViewById(mNav.getSelectedItemId());
        if (!end) {
            View under = mNav.findViewById(over);
            if (mDragLens && under != null) {
                mLens.drag(mBar, under, x);
            }
            return true;
        }
        popTab(over, false);
        popBar(false);
        mDownX = Float.NaN;
        mDragging = false;
        int target = action == MotionEvent.ACTION_UP ? over : mNav.getSelectedItemId();
        if (target != mNav.getSelectedItemId()) {
            mDragFromX = mDragLens ? x : Float.NaN;
            mNav.setSelectedItemId(target); // the usual switch, its lens from x
            mDragFromX = Float.NaN;
        } else if (mDragLens && lit != null) {
            mLens.play(mBar, x, lit, true, () -> mNav.setItemBackground(mPill)); // back home
        }
        return true;
    }

    private void popTab(int itemId, boolean out) {
        View tab = itemId != View.NO_ID ? mNav.findViewById(itemId) : null;
        if (tab != null) {
            float scale = out ? 1.3f : 1f;
            tab.animate().scaleX(scale).scaleY(scale).setDuration(out ? 240 : 180)
                    .setInterpolator(out ? new OvershootInterpolator(2f) : Motion.STANDARD).start();
        }
    }

    private void popBar(boolean out) {
        Object running = mBar.getTag(R.id.mobile_jiggle_animator);
        if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
        }
        android.animation.ValueAnimator pop = android.animation.ValueAnimator.ofFloat(mBar.getScaleX(), out ? 1.06f : 1f);
        Motion.Spring spring = new Motion.Spring(0f, 1f, 0f, 420f, 0.38f, 0.002f);
        pop.setDuration(Math.max(1, spring.durationMs));
        pop.setInterpolator(spring);
        pop.addUpdateListener(a -> {
            mBar.setScaleX((float) a.getAnimatedValue());
            mBar.setScaleY((float) a.getAnimatedValue());
        });
        mBar.setTag(R.id.mobile_jiggle_animator, pop);
        pop.start();
    }

    private static boolean isOn(View view, MotionEvent event) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return event.getRawX() >= at[0] && event.getRawX() < at[0] + view.getWidth()
                && event.getRawY() >= at[1] && event.getRawY() < at[1] + view.getHeight();
    }
}
