package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.IconHeaderItemPresenter;

import java.util.Map;
import java.util.WeakHashMap;

/** Keeps the stock header layout, binding, marquee and focus handling. */
public final class BrowseHeaderItemPresenter extends IconHeaderItemPresenter {
    private final Map<View, Long> mHeaders = new WeakHashMap<>();
    private ColorStateList mOriginalTextColors;
    private boolean mGlassEnabled = true;
    private long mSelectedHeaderId = -1;
    private boolean mExploreExpanded;

    public BrowseHeaderItemPresenter(int resId, String iconUrl) {
        super(resId, iconUrl);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        ViewHolder holder = super.onCreateViewHolder(parent);
        TextView label = holder.view.findViewById(R.id.header_label);
        mOriginalTextColors = label.getTextColors();
        // ItemBridgeAdapter chains this listener with Leanback's own focus listener.
        holder.view.setOnFocusChangeListener((view, focused) -> updateColors(view));
        return holder;
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder holder, Object item) {
        super.onBindViewHolder(holder, item);
        mHeaders.put(holder.view, ((Row) item).getHeaderItem().getId());
        updateStyle(holder.view);
        if (!mGlassEnabled) {
            super.onSelectLevelChanged((ViewHolder) holder);
        }
    }

    public void setGlassState(boolean enabled, long selectedHeaderId) {
        mGlassEnabled = enabled;
        mSelectedHeaderId = selectedHeaderId;
        for (View view : mHeaders.keySet()) {
            updateStyle(view);
        }
    }

    public void setExploreExpanded(boolean expanded) {
        boolean changed = mExploreExpanded != expanded;
        mExploreExpanded = expanded;
        for (View view : mHeaders.keySet()) {
            if (changed) {
                turnChevron(view, expanded);
            }
            updateStyle(view);
        }
    }

    /** Explore's chevron turns over as the group opens and closes. */
    private static void turnChevron(View view, boolean expanded) {
        TextView label = view.findViewById(R.id.header_label);
        Drawable chevron = label.getCompoundDrawablesRelative()[2];
        if (!(chevron instanceof RotateDrawable)) {
            return;
        }
        Object running = label.getTag(R.id.header_label);
        if (running instanceof Animator) {
            ((Animator) running).cancel();
        }
        ObjectAnimator turn = ObjectAnimator.ofInt(chevron, "level", chevron.getLevel(), expanded ? 10000 : 0);
        turn.setDuration(250);
        turn.setInterpolator(new DecelerateInterpolator());
        label.setTag(R.id.header_label, turn);
        turn.start();
    }

    @Override
    protected void onSelectLevelChanged(ViewHolder holder) {
        if (mGlassEnabled) {
            holder.view.setAlpha(1f);
        } else {
            super.onSelectLevelChanged(holder);
        }
    }

    private void updateStyle(View view) {
        view.setBackgroundResource(mGlassEnabled ? R.drawable.browse_glass_header : android.R.color.transparent);
        int pad = mGlassEnabled ? Math.round(14 * view.getResources().getDisplayMetrics().density) : 0;
        Long headerId = mHeaders.get(view);
        int indent = headerId != null && BrowseFragment.isExploreSection(headerId) ?
                Math.round(16 * view.getResources().getDisplayMetrics().density) : 0;
        view.setPaddingRelative(pad + indent, view.getPaddingTop(), pad + pad / 2, view.getPaddingBottom());
        TextView label = view.findViewById(R.id.header_label);
        // MEETUBE: a compound drawable keeps the chevron local to browse, leaving stock layouts alone.
        if (headerId != null && headerId == BrowseFragment.EXPLORE_HEADER_ID) {
            label.setCompoundDrawablePadding(Math.round(8 * view.getResources().getDisplayMetrics().density));
            if (Build.VERSION.SDK_INT >= 23) {
                // one chevron that turns (turnChevron): 0 points down, level 10000 = 180 degrees points up
                Drawable end = label.getCompoundDrawablesRelative()[2];
                RotateDrawable chevron;
                if (end instanceof RotateDrawable) {
                    chevron = (RotateDrawable) end;
                } else {
                    chevron = new RotateDrawable();
                    chevron.setDrawable(ContextCompat.getDrawable(view.getContext(), R.drawable.browse_chevron_down));
                    chevron.setToDegrees(180);
                    label.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null);
                }
                Object turning = label.getTag(R.id.header_label);
                if (!(turning instanceof Animator && ((Animator) turning).isRunning())) {
                    chevron.setLevel(mExploreExpanded ? 10000 : 0);
                }
            } else {
                label.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                        ContextCompat.getDrawable(view.getContext(), mExploreExpanded ?
                                R.drawable.browse_chevron_up : R.drawable.browse_chevron_down), null);
            }
        } else {
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        }
        view.setActivated(mGlassEnabled && headerId != null && headerId == mSelectedHeaderId);
        view.setAlpha(mGlassEnabled || view.hasFocus() ? 1f : view.getResources()
                .getFraction(R.fraction.lb_browse_header_unselect_alpha, 1, 1));
        updateColors(view);
    }

    private void updateColors(View view) {
        TextView label = view.findViewById(R.id.header_label);
        ImageView icon = view.findViewById(R.id.header_icon);
        if (mGlassEnabled && view.hasFocus()) {
            label.setTextColor(0xFF000000);
            icon.setColorFilter(0xFF000000);
        } else {
            label.setTextColor(mOriginalTextColors);
            icon.clearColorFilter();
        }
        for (Drawable drawable : label.getCompoundDrawablesRelative()) {
            if (drawable != null) {
                drawable.mutate().setTint(label.getCurrentTextColor());
            }
        }
    }
}
