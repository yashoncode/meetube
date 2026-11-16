package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.newtube.mobile.player.Media3PlayerController;
import com.newtube.mobile.player.Media3PlayerInitializer;

/**
 * MEETUNE: a true crossfade between MeeTune songs, the outgoing song falling while the next one rises
 * (equal power, so the blend never dips). One player plays one song, so the overlap needs a second
 * one: a sound-only "tail" that plays just the outgoing song's ending.
 *
 * <ol>
 *     <li>{@link #LEAD_MS} before the fade point (the song's length minus the crossfade), the tail builds
 *     the same song from the same format info and waits there, paused.</li>
 *     <li>At the fade point the tail plays on, the main player goes quiet and is told the song ended
 *     (whatever the song's own end would do: the next song, or this one again on Repeat one).</li>
 *     <li>The tail falls to silence by the song's end; the main player's next song rises over the same
 *     window, which starts when it actually plays (its open takes a moment).</li>
 * </ol>
 * Anything not ready in time (a slow build, a seek, a pause, a MeeTube video) drops the tail and the
 * song ends as it always did. Off at 0 s.
 */
final class MeeTuneCrossfade {
    interface Host {
        @Nullable Media3PlayerController controller();

        /** The playing song's format info, or null. */
        @Nullable MediaItemFormatInfo formatInfo();

        @Nullable String videoId();

        /** A MeeTune song plays and its end goes on to more music (not Pause or Close). */
        boolean crossfades();

        /** What the song's own end does. */
        void playEnd();

        int maxAudioBitrate();
    }

    static final long LEAD_MS = 2500;
    private static final long TICK_MS = 50;
    private static final long IDLE_TICK_MS = 500;
    /** The next song has this long to start before its rise is given up and it plays at full volume. */
    private static final long START_TIMEOUT_MS = 15_000;

    private static final int IDLE = 0;
    private static final int ARMING = 1;
    private static final int FADING = 2;

    private final Context mContext;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::tick;
    private int mState = IDLE;
    @Nullable private ExoPlayer mTail;
    @Nullable private String mSongId;
    /** The song a fade already ran for (its muted end must not start another). */
    @Nullable private String mDoneId;
    private long mHandoffMs;
    private long mFadeMs;
    private long mFadeEndAt;
    private long mMainStartAt;
    private float mMainFadeMs;
    private boolean mRunning;

    MeeTuneCrossfade(Context context, Host host) {
        mContext = context; // the activity: made before it has a base context, used only after
        mHost = host;
    }

    void start() {
        if (!mRunning) {
            mRunning = true;
            mHandler.post(mTick);
        }
    }

    void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mTick);
        cancel();
    }

    private void tick() {
        if (!mRunning) return;
        long next = IDLE_TICK_MS;
        try {
            next = step();
        } finally {
            mHandler.postDelayed(mTick, next);
        }
    }

    private long step() {
        Media3PlayerController controller = mHost.controller();
        ExoPlayer main = controller != null ? controller.getExoPlayer() : null;
        if (main == null) {
            cancel();
            return IDLE_TICK_MS;
        }
        long pos = main.getCurrentPosition();
        String id = mHost.videoId();
        switch (mState) {
            case IDLE: {
                long fade = com.newtube.mobile.ui.meetune.MeeTune.crossfadeSeconds(mContext) * 1000L;
                long duration = main.getDuration();
                if (fade <= 0 || duration == C.TIME_UNSET || id == null || id.equals(mDoneId)
                        || !main.isPlaying() || !mHost.crossfades() || duration < 2 * fade + 10_000) {
                    return IDLE_TICK_MS;
                }
                long handoff = duration - fade;
                if (pos < handoff - LEAD_MS - IDLE_TICK_MS) {
                    return Math.min(IDLE_TICK_MS, Math.max(TICK_MS, handoff - LEAD_MS - pos));
                }
                if (pos >= handoff - LEAD_MS && pos < handoff - 300) {
                    arm(controller, id, handoff, fade);
                }
                return TICK_MS;
            }
            case ARMING: {
                if (!java.util.Objects.equals(id, mSongId) || pos < mHandoffMs - LEAD_MS - 1000 || !main.getPlayWhenReady()) {
                    cancel(); // another song, a seek back, or paused
                    return TICK_MS;
                }
                if (pos >= mHandoffMs) {
                    if (mTail != null && mTail.getPlaybackState() == Player.STATE_READY) {
                        handoff(controller, main);
                    } else if (pos > mHandoffMs + 400) {
                        mDoneId = mSongId; // too late: the song ends as it always did
                        cancel();
                    }
                }
                return TICK_MS;
            }
            case FADING: {
                long now = SystemClock.uptimeMillis();
                if (mTail != null && !mTail.getPlayWhenReady()) {
                    finish(controller); // the tail was stopped (paused below)
                    return TICK_MS;
                }
                if (mMainStartAt == 0 && main.isPlaying()
                        && (!java.util.Objects.equals(mSongId, id) || pos < mHandoffMs - LEAD_MS)) {
                    // The next song (or this one again) plays: rise over what is left of the tail.
                    mMainStartAt = now;
                    mMainFadeMs = Math.max(600, mFadeEndAt - now);
                }
                if (mTail != null) {
                    float out = mMainStartAt == 0 ? 1f
                            : 1f - Math.min(1f, (now - mMainStartAt) / (float) Math.max(1, mFadeEndAt - mMainStartAt));
                    mTail.setVolume((float) Math.cos(Math.PI / 2 * (1 - out)));
                    if (now >= mFadeEndAt || mTail.getPlaybackState() == Player.STATE_ENDED) {
                        releaseTail();
                    }
                }
                if (mMainStartAt != 0) {
                    float in = Math.min(1f, (now - mMainStartAt) / mMainFadeMs);
                    controller.setFadeGain((float) Math.sin(Math.PI / 2 * in));
                    if (in >= 1f && mTail == null) {
                        finish(controller);
                    }
                } else if (now > mFadeEndAt + START_TIMEOUT_MS) {
                    finish(controller); // the next song never started: whatever plays, at full volume
                } else if (java.util.Objects.equals(mSongId, id) && !main.getPlayWhenReady() && mTail != null) {
                    mTail.setPlayWhenReady(false); // the user paused the outgoing song
                }
                return TICK_MS;
            }
            default:
                return IDLE_TICK_MS;
        }
    }

    private void arm(Media3PlayerController controller, String id, long handoff, long fade) {
        MediaItemFormatInfo info = mHost.formatInfo();
        if (info == null || info.isLive()) {
            mDoneId = id;
            return;
        }
        mState = ARMING;
        mSongId = id;
        mHandoffMs = handoff;
        mFadeMs = fade;
        controller.buildSourceAsync(info, source -> {
            if (mState != ARMING || !id.equals(mSongId)) return;
            if (source == null) {
                mDoneId = id;
                cancel();
                return;
            }
            mTail = Media3PlayerInitializer.createTailPlayer(mContext, mHost.maxAudioBitrate());
            mTail.setMediaSource(source, handoff);
            mTail.setPlayWhenReady(false);
            mTail.prepare();
        });
    }

    private void handoff(Media3PlayerController controller, ExoPlayer main) {
        mState = FADING;
        mDoneId = mSongId;
        mTail.setPlaybackParameters(main.getPlaybackParameters());
        mTail.setVolume(1f);
        mTail.play();
        controller.setFadeGain(0f);
        mFadeEndAt = SystemClock.uptimeMillis() + Math.round(mFadeMs / Math.max(0.25f, main.getPlaybackParameters().speed));
        mMainStartAt = 0;
        mHost.playEnd();
    }

    private void finish(Media3PlayerController controller) {
        releaseTail();
        controller.setFadeGain(1f);
        mState = IDLE;
    }

    private void cancel() {
        releaseTail();
        Media3PlayerController controller = mHost.controller();
        if (controller != null) controller.setFadeGain(1f);
        mState = IDLE;
    }

    private void releaseTail() {
        if (mTail != null) {
            mTail.release();
            mTail = null;
        }
    }
}
