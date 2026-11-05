package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerEngine;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * MEETUBE(shorts): the Shorts player, layered on the ordinary one. A short ({@link Video#isShorts}) plays in a 9:16
 * card centred on the screen (the video surface is sized to it), with ambient light from its thumbnail on both sides
 * ({@link ShortsFrameView}), the title, channel and stats beside the card's bottom, a progress line and key hints that
 * fade after a few seconds. Up / down page through shorts (PlayerUIController).
 */
public final class ShortsOverlay {
    private static final int TICK_MS = 250;
    private static final int HINTS_SHOWN_MS = 5_000;
    private final Context mContext;
    private final PlayerEngine mPlayer;
    private final View mRoot;
    private final View mSurfaceRoot;
    private final ShortsFrameView mFrame;
    private final ImageView mAvatar;
    private final View mPanel;
    private final View mPaused;
    private final View mHints;
    private final TextView mTitle;
    private final TextView mMeta;
    private final int mMargin;
    private final int mGap;
    private final RectF mCard = new RectF();
    private final Runnable mTick = this::tick;
    private boolean mIsShorts;
    private boolean mControlsShown;
    private boolean mIsPaused;
    private String mVideoId;

    private final CustomTarget<Bitmap> mBackdropTarget = new CustomTarget<Bitmap>() {
        @Override
        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
            mFrame.setBackdrop(resource);
        }

        @Override
        public void onLoadCleared(@Nullable Drawable placeholder) {
            mFrame.setBackdrop(null);
        }
    };

    public ShortsOverlay(View root, PlayerEngine player) {
        mContext = root.getContext();
        mPlayer = player;
        mRoot = root;
        mSurfaceRoot = root.findViewById(R.id.surface_root);
        mFrame = root.findViewById(R.id.shorts_frame);
        mAvatar = root.findViewById(R.id.shorts_avatar);
        mPanel = root.findViewById(R.id.shorts_panel);
        mPaused = root.findViewById(R.id.shorts_paused);
        mHints = root.findViewById(R.id.shorts_hints);
        mTitle = root.findViewById(R.id.shorts_title);
        mMeta = root.findViewById(R.id.shorts_meta);
        float density = root.getResources().getDisplayMetrics().density;
        mMargin = Math.round(20 * density);
        mGap = Math.round(28 * density);

        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                view.post(this::applyLayout);
            }
        });
    }

    public void bind(Video video, CharSequence meta) {
        boolean isShorts = video != null && video.isShorts;
        boolean modeChanged = isShorts != mIsShorts;
        boolean videoChanged = isShorts && (modeChanged || !TextUtils.equals(mVideoId, video.videoId));
        mIsShorts = isShorts;

        if (modeChanged) {
            applyLayout();
        }

        updateVisibility();

        if (!mIsShorts) {
            mVideoId = null;
            Glide.with(mContext).clear(mBackdropTarget);
            Glide.with(mContext).clear(mAvatar);
            mAvatar.setImageDrawable(null);
            mAvatar.setVisibility(View.GONE);
            mRoot.removeCallbacks(mTick);
            return;
        }

        mTitle.setText(video.getTitleFull());
        mMeta.setText(meta);

        if (videoChanged) {
            mVideoId = video.videoId;
            Glide.with(mContext).clear(mAvatar);
            mAvatar.setImageDrawable(null);
            mAvatar.setVisibility(View.GONE); // until this short's channel icon arrives
            mFrame.setRimEnabled(PlayerTweaksData.instance(mContext).isPlayerGlassOutlineEnabled());
            mFrame.setProgress(0);
            // a few pixels of the thumbnail, stretched over the screen by ShortsFrameView: a soft glow of its colours
            Glide.with(mContext).asBitmap().load(video.getCardImageUrl())
                    .apply(ViewUtil.glideOptions()).override(12).into(mBackdropTarget);
            mHints.animate().cancel();
            mHints.setAlpha(1f);
            mHints.animate().alpha(0f).setStartDelay(HINTS_SHOWN_MS).setDuration(600).start();
            mRoot.removeCallbacks(mTick);
            mRoot.postDelayed(mTick, TICK_MS);
        }
    }

    public void setAvatar(String url) {
        if (url == null) {
            Glide.with(mContext).clear(mAvatar);
            mAvatar.setImageDrawable(null);
            mAvatar.setVisibility(View.GONE);
        } else {
            mAvatar.setVisibility(View.VISIBLE);
            Glide.with(mContext).load(url).apply(ViewUtil.glideOptions()).circleCrop().into(mAvatar);
        }
    }

    public void setControlsShown(boolean shown) {
        mControlsShown = shown;
        updateVisibility();
    }

    public void setPaused(boolean paused) {
        mIsPaused = paused;
        updateVisibility();
    }

    private void updateVisibility() {
        mFrame.setVisibility(mIsShorts ? View.VISIBLE : View.GONE);
        mPanel.setVisibility(mIsShorts && !mControlsShown ? View.VISIBLE : View.GONE);
        mPaused.setVisibility(mIsShorts && mIsPaused && !mControlsShown ? View.VISIBLE : View.GONE);
    }

    /** The 9:16 card, the video surface sized to it, and the info beside its bottom; the whole screen otherwise. */
    private void applyLayout() {
        int width = mRoot.getWidth();
        int height = mRoot.getHeight();

        if (width == 0 || height == 0) {
            return;
        }

        ViewGroup.LayoutParams params = mSurfaceRoot.getLayoutParams();

        if (!mIsShorts) {
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT || params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                mSurfaceRoot.setLayoutParams(params);
            }
            return;
        }

        int cardHeight = height - 2 * mMargin;
        int cardWidth = Math.round(cardHeight * 9 / 16f);
        float left = (width - cardWidth) / 2f;
        mCard.set(left, mMargin, left + cardWidth, mMargin + cardHeight);
        mFrame.setCard(mCard);

        if (params.width != cardWidth || params.height != cardHeight) {
            params.width = cardWidth;
            params.height = cardHeight;
            if (params instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) params).gravity = Gravity.CENTER;
            }
            mSurfaceRoot.setLayoutParams(params);
        }

        mPanel.setPadding(Math.round(mCard.right) + mGap, 0, mGap, mMargin + mGap / 2);
    }

    private void tick() {
        if (!mIsShorts) {
            return;
        }

        long duration = mPlayer.getDurationMs();
        mFrame.setProgress(duration > 0 ? mPlayer.getPositionMs() / (float) duration : 0);
        mRoot.postDelayed(mTick, TICK_MS);
    }
}
