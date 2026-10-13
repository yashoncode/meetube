package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Collections;

/**
 * METUBE(mini-pip): leaving the app (Home, Recents) while a screen's mini card plays floats that
 * card in a PiP window, like leaving the full player does. The player Activity sits stopped behind
 * the card's screen and cannot enter PiP itself, so the HOST does: its window becomes the video
 * (the card blown up to fill it, its buttons hidden) until the window expands back or closes.
 * The play/pause action is the player's own broadcast, which its receiver already handles.
 *
 * <p>Host wiring: {@link #update} whenever the card shows, hides or changes play state;
 * {@link #onUserLeaveHint}, {@link #onModeChanged}, {@link #onResume} from the same callbacks;
 * {@link #isPinned} keeps onPause from detaching the card; {@link #onStop} from onStop.</p>
 */
public final class MiniPip {
    private final Activity mHost;
    private final View mCard;
    private final View[] mChrome;
    private final Runnable mOnClosed;
    private boolean mEntering;
    private boolean mPinned;
    private boolean mDismissPending;
    private String mShown;
    // The card's own geometry, put back when the window expands.
    private ViewGroup.MarginLayoutParams mSavedLp;
    private float mSavedZ;

    /** {@code onClosed}: the PiP window was closed - stop the video with it. */
    public MiniPip(Activity host, View card, Runnable onClosed, View... chrome) {
        mHost = host;
        mCard = card;
        mOnClosed = onClosed;
        mChrome = chrome;
    }

    private static boolean supported(Activity host) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Helpers.isPictureInPictureSupported(host);
    }

    /** A mini session is showing and playing: what Home floats. */
    private boolean canFloat() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        return player != null && player.getPlayWhenReady() && mCard.getVisibility() == View.VISIBLE;
    }

    /** Re-arm (or disarm) the window's auto-enter and refresh the PiP window's action. */
    public void update() {
        if (!supported(mHost)) {
            return;
        }
        boolean playing = canFloat();
        Rational aspect = aspect();
        String shown = playing + "|" + aspect + "|" + mPinned;
        if (shown.equals(mShown)) {
            return;
        }
        mShown = shown;
        try {
            mHost.setPictureInPictureParams(params(playing && !mPinned, aspect));
        } catch (Exception e) {
            NetPath.log("mini-pip params-failed " + e.getClass().getSimpleName());
        }
    }

    /** Before Android 12 Home arrives here; from 12 on the auto-enter flag does it (update). */
    public void onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !supported(mHost) || mPinned || !canFloat()) {
            return;
        }
        try {
            mEntering = mHost.enterPictureInPictureMode(params(false, aspect()));
        } catch (Exception e) {
            mEntering = false;
        }
        NetPath.log("mini-pip enter-request accepted=" + (mEntering ? "y" : "n"));
    }

    /** True from the entry until the window expands or closes: the card must stay attached. */
    public boolean isPinned() {
        return mPinned || mEntering || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && mHost.isInPictureInPictureMode());
    }

    public void onModeChanged(boolean inPip) {
        mEntering = false;
        if (inPip == mPinned) {
            return;
        }
        mPinned = inPip;
        NetPath.log("mini-pip mode inPip=" + (inPip ? "y" : "n"));
        if (inPip) {
            fillWindow();
        } else {
            restoreCard();
            // Closed, the screen stops - before this callback (Android 16) or right after it;
            // expanded, it resumes (onResume clears the pending close).
            if (mHost instanceof LifecycleOwner && !((LifecycleOwner) mHost).getLifecycle()
                    .getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                close();
            } else {
                mDismissPending = true;
            }
        }
        mShown = null;
        update();
    }

    public void onResume() {
        mDismissPending = false;
    }

    /** Stopped right after leaving PiP without resuming: the window was closed. */
    public void onStop() {
        if (mDismissPending) {
            close();
        }
    }

    private void close() {
        mDismissPending = false;
        NetPath.log("mini-pip closed");
        mOnClosed.run();
    }

    private void fillWindow() {
        mSavedLp = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) mCard.getLayoutParams());
        mSavedZ = mCard.getTranslationZ();
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mCard.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.setMargins(0, 0, 0, 0);
        mCard.setLayoutParams(lp);
        mCard.setTranslationX(0f);
        mCard.setTranslationY(0f);
        mCard.setTranslationZ(1000f); // over the screen's other floating layers
        for (View view : mChrome) {
            if (view != null) {
                view.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void restoreCard() {
        if (mSavedLp != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mCard.getLayoutParams();
            lp.width = mSavedLp.width;
            lp.height = mSavedLp.height;
            lp.setMargins(mSavedLp.leftMargin, mSavedLp.topMargin, mSavedLp.rightMargin, mSavedLp.bottomMargin);
            mCard.setLayoutParams(lp);
            mCard.setTranslationZ(mSavedZ);
            mSavedLp = null;
        }
        for (View view : mChrome) {
            if (view != null) {
                view.setVisibility(View.VISIBLE);
            }
        }
    }

    private static Rational aspect() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        VideoSize size = player != null ? player.getVideoSize() : VideoSize.UNKNOWN;
        if (size.width <= 0 || size.height <= 0) {
            return new Rational(16, 9);
        }
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        if (ratio < 0.5f) {
            return new Rational(1, 2);
        }
        if (ratio > 2.3f) {
            return new Rational(23, 10);
        }
        return new Rational(Math.round(ratio * 1000), 1000);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private PictureInPictureParams params(boolean autoEnter, Rational aspect) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .setActions(Collections.singletonList(playPause()));
        Rect card = new Rect();
        if (!mPinned && mCard.getGlobalVisibleRect(card) && !card.isEmpty()) {
            builder.setSourceRectHint(card); // the window grows out of the card
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
        }
        return builder.build();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction playPause() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        boolean playing = player != null && player.getPlayWhenReady();
        String label = mHost.getString(playing ? R.string.mobile_player_pause : R.string.mobile_player_play);
        PendingIntent toggle = PendingIntent.getBroadcast(mHost, 0,
                new Intent(MobilePlaybackActivity.ACTION_PIP_TOGGLE).setPackage(mHost.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(mHost,
                playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play), label, label, toggle);
    }
}
