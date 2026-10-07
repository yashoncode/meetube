package com.newtube.mobile.ui.playback;

import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.ShortsSeen;

/**
 * METUBE(shorts): the TikTok/YouTube-style vertical player, layered on the ordinary one. A video
 * from the Shorts tab ({@link Video#belongsToShorts()}) switches the watch screen into shorts mode:
 * the video fills the screen (the host resizes it), this overlay shows the channel row, title and
 * actions like YouTube's Shorts, and a vertical drag pages to the next / previous short. Tap
 * pauses, double tap likes (with a heart), hold is the player's own 2x. Looping and preloading
 * the next one come from the shared playback controllers.
 */
final class ShortsOverlay {
    interface Host {
        void onShortsModeChanged(boolean on);

        void playNextShort();

        void playPreviousShort();

        void openShortsComments();
    }

    /** The watch page's views the overlay mirrors and forwards to. */
    static final class Watch {
        View like;
        View dislike;
        View share;
        View subscribe;
        TextView likeCount;
        TextView commentsCount;
        TextView meta;
        TextView subscribeLabel;
        ImageView avatar;
    }

    private static final long OUT_MS = 170;
    private static final long IN_MS = 240;
    private static final float FLING_PX_PER_S = 1200f;

    private final View mOverlay;
    private final View mVideo;
    private final TextView mChannel;
    private final TextView mTitle;
    private final TextView mMeta;
    private final TextView mLikes;
    private final TextView mComments;
    private final TextView mSubscribe;
    private final ImageView mLikeIcon;
    private final ImageView mAvatar;
    private final ImageView mFlash;
    private final ImageView mHeart;
    private final ProgressBar mProgress;
    private final Watch mWatch;
    private final Host mHost;
    private boolean mActive;
    private boolean mCommentsOpen;

    ShortsOverlay(@NonNull View root, @NonNull View video, @NonNull Host host, @NonNull Watch watch) {
        mOverlay = root.findViewById(R.id.mobile_shorts_overlay);
        mVideo = video;
        mHost = host;
        mWatch = watch;
        mChannel = root.findViewById(R.id.mobile_shorts_channel);
        mTitle = root.findViewById(R.id.mobile_shorts_title);
        mMeta = root.findViewById(R.id.mobile_shorts_meta);
        mLikes = root.findViewById(R.id.mobile_shorts_like_count);
        mComments = root.findViewById(R.id.mobile_shorts_comments_count);
        mSubscribe = root.findViewById(R.id.mobile_shorts_subscribe);
        mLikeIcon = root.findViewById(R.id.mobile_shorts_like);
        mAvatar = root.findViewById(R.id.mobile_shorts_avatar);
        mFlash = root.findViewById(R.id.mobile_shorts_flash);
        mHeart = root.findViewById(R.id.mobile_shorts_heart);
        mProgress = root.findViewById(R.id.mobile_shorts_progress);

        forward(R.id.mobile_shorts_like, watch.like);
        forward(R.id.mobile_shorts_dislike, watch.dislike);
        forward(R.id.mobile_shorts_share, watch.share);
        forward(R.id.mobile_shorts_subscribe, watch.subscribe);
        mOverlay.findViewById(R.id.mobile_shorts_comments).setOnClickListener(v -> mHost.openShortsComments());

        mirror(watch.likeCount, text -> mLikes.setText(isCount(text) ? text
                : mLikes.getContext().getString(R.string.mobile_watch_like)));
        mirror(watch.commentsCount, text -> mComments.setText(isCount(text) ? text
                : mComments.getContext().getString(R.string.mobile_comments_title)));
        mirror(watch.meta, mMeta::setText);
        mirror(watch.subscribeLabel, mSubscribe::setText);
    }

    boolean isActive() {
        return mActive;
    }

    /** Shorts mode with the video filling the screen (not shrunk above the comments). */
    boolean isFullscreen() {
        return mActive && !mCommentsOpen;
    }

    boolean isCommentsOpen() {
        return mActive && mCommentsOpen;
    }

    /** The comments sheet takes the lower part of the screen; the short shrinks above it. */
    void setCommentsOpen(boolean open) {
        mCommentsOpen = open;
        mOverlay.setVisibility(mActive && !open ? View.VISIBLE : View.GONE);
    }

    /** Every setVideo: shorts mode follows the video that is playing now. */
    void bind(@Nullable Video item) {
        boolean on = item != null && item.belongsToShorts();
        if (on) {
            ShortsSeen.add(item.videoId);
            mChannel.setText(item.getAuthor());
            mTitle.setText(item.getTitle());
            // The watch page loads the channel avatar on its own schedule: copy it as it lands.
            copyAvatar();
            mAvatar.postDelayed(this::copyAvatar, 900);
            mAvatar.postDelayed(this::copyAvatar, 2500);
        }
        if (on != mActive) {
            mActive = on;
            mCommentsOpen = false;
            mOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
            if (!on) {
                mVideo.setTranslationY(0f);
                mVideo.setAlpha(1f);
            }
            mHost.onShortsModeChanged(on);
        }
    }

    void setLiked(boolean liked) {
        mLikeIcon.setImageResource(liked ? R.drawable.ic_watch_thumb_up : R.drawable.ic_watch_thumb_up_outline);
    }

    void setSubscribed(boolean subscribed) {
        mSubscribe.setSelected(subscribed);
    }

    void setProgress(long positionMs, long durationMs) {
        if (mActive) {
            mProgress.setProgress(durationMs > 0 ? (int) (positionMs * 1000 / durationMs) : 0);
        }
    }

    /** The tap's answer: a play or pause glyph that pops up in the middle and fades. */
    void flashPlayState(boolean playing) {
        mFlash.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play); // paused shows play
        mFlash.animate().cancel();
        mFlash.setAlpha(1f);
        mFlash.setScaleX(0.8f);
        mFlash.setScaleY(0.8f);
        mFlash.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction(() ->
                mFlash.animate().alpha(0f).setStartDelay(450).setDuration(200).start()).start();
    }

    /** Double tap: a big heart springs in and floats off. */
    void popHeart() {
        mHeart.animate().cancel();
        mHeart.setAlpha(1f);
        mHeart.setScaleX(0.4f);
        mHeart.setScaleY(0.4f);
        mHeart.setRotation((float) (Math.random() * 24 - 12));
        mHeart.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(2.5f)).setDuration(260)
                .withEndAction(() -> mHeart.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setStartDelay(250)
                        .setInterpolator(new DecelerateInterpolator()).setDuration(400).start()).start();
    }

    /** The video follows the finger, a little behind it. */
    void drag(float dy) {
        mVideo.animate().cancel();
        mVideo.setTranslationY(dy * 0.85f);
    }

    void release(float dy, float yVelocity) {
        float height = Math.max(1, mVideo.getHeight());
        boolean far = Math.abs(dy) > height * 0.18f;
        boolean fling = Math.abs(yVelocity) > FLING_PX_PER_S && Math.signum(yVelocity) == Math.signum(dy);
        if (dy == 0 || !(far || fling)) {
            mVideo.animate().translationY(0f).setDuration(IN_MS).setInterpolator(new DecelerateInterpolator()).start();
            return;
        }
        boolean next = dy < 0; // finger went up: the next short comes up from below
        mVideo.animate().translationY(next ? -height : height).alpha(0.4f).setDuration(OUT_MS)
                .withEndAction(() -> {
                    mProgress.setProgress(0);
                    if (next) {
                        mHost.playNextShort();
                    } else {
                        mHost.playPreviousShort();
                    }
                    mVideo.setTranslationY(next ? height * 0.3f : -height * 0.3f);
                    mVideo.animate().translationY(0f).alpha(1f).setDuration(IN_MS)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }).start();
    }

    private void copyAvatar() {
        Drawable avatar = mWatch.avatar != null ? mWatch.avatar.getDrawable() : null;
        if (avatar != null && avatar.getConstantState() != null) {
            mAvatar.setImageDrawable(avatar.getConstantState().newDrawable().mutate());
            mAvatar.setClipToOutline(true);
            mAvatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        }
    }

    /** A count has digits; the placeholder and "N/A" (counts hidden by the channel) don't. */
    private static boolean isCount(@Nullable CharSequence text) {
        return text != null && text.toString().matches(".*\\d.*");
    }

    private interface OnText {
        void set(CharSequence text);
    }

    private static void mirror(@Nullable TextView source, OnText target) {
        if (source == null) {
            return;
        }
        target.set(source.getText());
        source.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                target.set(s);
            }
        });
    }

    private void forward(int id, @Nullable View target) {
        View button = mOverlay.findViewById(id);
        if (target == null) {
            button.setVisibility(View.GONE);
        } else {
            button.setOnClickListener(v -> target.performClick());
        }
    }
}
