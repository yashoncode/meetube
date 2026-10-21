package com.newtube.mobile.ui.playback;

import android.view.View;

import com.newtube.mobile.ui.common.Motion;

/**
 * METUBE(mini-controls): the mini card's buttons stay hidden over the video, like a PiP window's.
 * A tap on the card brings them up for a few seconds; a tap on the video while they show expands.
 */
public final class MiniControls {
    private static final long SHOWN_MS = 3000;

    private final View mControls;
    private final Runnable mHide = this::hide;
    private boolean mShown;

    public MiniControls(View controls) {
        mControls = controls;
    }

    /** A tap on the video: true when it only brought the buttons up, so it must not expand. */
    public boolean onVideoTap() {
        if (mShown) {
            return false;
        }
        show();
        return true;
    }

    /** Up, or up a few seconds longer (a button was used). */
    public void show() {
        mShown = true;
        mControls.removeCallbacks(mHide);
        mControls.animate().cancel();
        if (mControls.getVisibility() != View.VISIBLE) {
            mControls.setAlpha(0f);
            mControls.setVisibility(View.VISIBLE);
        }
        mControls.animate().alpha(1f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD).start();
        mControls.postDelayed(mHide, SHOWN_MS);
    }

    private void hide() {
        mShown = false;
        mControls.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> mControls.setVisibility(View.GONE)).start();
    }

    /** Gone at once: the card folded, so it comes back as a bare picture. */
    public void reset() {
        mShown = false;
        mControls.removeCallbacks(mHide);
        mControls.animate().cancel();
        mControls.setVisibility(View.GONE);
    }
}
