package com.newtube.mobile.ui.playback;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * METUBE(shorts): the TikTok-style vertical player, layered on the ordinary one. A video from the
 * Shorts tab ({@link Video#belongsToShorts()}) switches the watch screen into shorts mode: the
 * video fills the screen (the host resizes it), this overlay shows channel, title and actions, and
 * a vertical drag on the video pages to the next / previous short in the queue the Shorts tab
 * built. Looping and preloading the next one come from the shared playback controllers.
 */
final class ShortsOverlay {
    interface Host {
        void onShortsModeChanged(boolean on);

        void playNextShort();

        void playPreviousShort();
    }

    private static final long OUT_MS = 170;
    private static final long IN_MS = 240;
    private static final float FLING_PX_PER_S = 1200f;

    private final View mOverlay;
    private final View mVideo;
    private final TextView mChannel;
    private final TextView mTitle;
    private final TextView mLikes;
    private final Host mHost;
    private boolean mActive;

    ShortsOverlay(@NonNull View root, @NonNull View video, @NonNull Host host,
                  @Nullable View like, @Nullable View dislike, @Nullable View share, @Nullable TextView likeCount) {
        mOverlay = root.findViewById(R.id.mobile_shorts_overlay);
        mVideo = video;
        mHost = host;
        mChannel = root.findViewById(R.id.mobile_shorts_channel);
        mTitle = root.findViewById(R.id.mobile_shorts_title);
        mLikes = root.findViewById(R.id.mobile_shorts_like_count);
        forward(R.id.mobile_shorts_like, like);
        forward(R.id.mobile_shorts_dislike, dislike);
        forward(R.id.mobile_shorts_share, share);
        if (likeCount != null) {
            showLikes(likeCount.getText());
            likeCount.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    showLikes(s);
                }
            });
        }
    }

    boolean isActive() {
        return mActive;
    }

    /** Every setVideo: shorts mode follows the video that is playing now. */
    void bind(@Nullable Video item) {
        boolean on = item != null && item.belongsToShorts();
        if (on) {
            mChannel.setText(item.getAuthor());
            mTitle.setText(item.getTitle());
        }
        if (on != mActive) {
            mActive = on;
            mOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
            if (!on) {
                mVideo.setTranslationY(0f);
                mVideo.setAlpha(1f);
            }
            mHost.onShortsModeChanged(on);
        }
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

    /** The like count, or nothing while it is still the watch page's placeholder. */
    private void showLikes(CharSequence count) {
        boolean placeholder = count == null
                || mLikes.getContext().getString(R.string.mobile_watch_count_placeholder).contentEquals(count);
        mLikes.setText(placeholder ? "" : count);
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
