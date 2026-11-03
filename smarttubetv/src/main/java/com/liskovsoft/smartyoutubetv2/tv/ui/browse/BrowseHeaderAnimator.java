package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Row;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Opening Explore: its sections slide in from the left and fade up one after another while the rows below glide
 * down to make room. Everything else (closing, the rows below, the first fill) keeps the default animations.
 */
final class BrowseHeaderAnimator extends DefaultItemAnimator {
    private static final long STAGGER_MS = 40;
    private final List<RecyclerView.ViewHolder> mRunning = new ArrayList<>();
    private int mOrder;

    BrowseHeaderAnimator() {
        setSupportsChangeAnimations(false); // as leanback's grids
    }

    @Override
    public boolean animateAdd(RecyclerView.ViewHolder holder) {
        if (!isExploreSection(holder)) {
            return super.animateAdd(holder);
        }
        endAnimation(holder);
        if (mRunning.isEmpty()) {
            mOrder = 0;
        }
        View view = holder.itemView;
        view.setAlpha(0f);
        view.setTranslationX(-24 * view.getResources().getDisplayMetrics().density);
        mRunning.add(holder);
        dispatchAddStarting(holder);
        view.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(80 + mOrder++ * STAGGER_MS)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        finish(holder);
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        finish(holder);
                    }
                })
                .start();
        return false; // runs on its own: nothing for runPendingAnimations
    }

    private void finish(RecyclerView.ViewHolder holder) {
        if (!mRunning.remove(holder)) {
            return; // cancel is followed by end
        }
        View view = holder.itemView;
        view.animate().setListener(null).setStartDelay(0); // the view's animator is shared with later moves
        view.setAlpha(1f);
        view.setTranslationX(0f);
        dispatchAddFinished(holder);
        if (!isRunning()) {
            dispatchAnimationsFinished();
        }
    }

    @Override
    public void endAnimation(RecyclerView.ViewHolder holder) {
        if (mRunning.contains(holder)) {
            holder.itemView.animate().cancel();
            finish(holder); // a cancel before the delayed start reaches no listener
        }
        super.endAnimation(holder);
    }

    @Override
    public void endAnimations() {
        for (RecyclerView.ViewHolder holder : new ArrayList<>(mRunning)) {
            holder.itemView.animate().cancel();
            finish(holder);
        }
        super.endAnimations();
    }

    @Override
    public boolean isRunning() {
        return !mRunning.isEmpty() || super.isRunning();
    }

    private static boolean isExploreSection(RecyclerView.ViewHolder holder) {
        Object item = holder instanceof ItemBridgeAdapter.ViewHolder ? ((ItemBridgeAdapter.ViewHolder) holder).getItem() : null;
        return item instanceof Row && ((Row) item).getHeaderItem() != null &&
                BrowseFragment.isExploreSection(((Row) item).getHeaderItem().getId());
    }
}
