package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.newtube.mobile.player.BassSwap;
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
 *
 * <p>Automix (Beta) makes it a DJ's blend. {@link MeeTuneBeats} finds the beat grid of this song's ending
 * (as soon as it plays) and of the next one's opening (once it is known, {@link #analyzeNext}, a minute
 * before the end). The blend then ends where this song's music does (before a fade-out or silence) on a bar
 * line. Only the next song moves (the audible outgoing one never does): stretched, pitch kept, to the outgoing
 * tempo; one seek while it is still near silent starts it at its first full bar (past a silent intro), as far
 * into the bar as the outgoing song is; small tempo nudges keep the beats together. It comes up without its
 * bass ({@link BassSwap}); on the bar line midway the two swap bass over one beat, and only then does the
 * outgoing song fade. After the blend the next song eases back to its own tempo. Grids too weak or tempos
 * too far apart: the plain crossfade.
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

        /** The main player's bass knob (Automix), or null. */
        @Nullable BassSwap mainBass();
    }

    private static final String TAG = "MeeTuneCrossfade";
    static final long LEAD_MS = 2500;
    private static final long TICK_MS = 50;
    private static final long IDLE_TICK_MS = 500;
    /** The next song has this long to start before its rise is given up and it plays at full volume. */
    private static final long START_TIMEOUT_MS = 15_000;
    /** Automix's blend when Crossfade is Off. */
    private static final int AUTOMIX_DEFAULT_S = 8;
    /** How much of each song Automix analyzes besides the blend itself. */
    private static final long ANALYZE_EXTRA_MS = 30_000;
    /** The most of a song's ending (fade-out, quiet outro, silence) Automix leaves out. */
    private static final long MAX_SKIPPED_OUTRO_MS = 25_000;
    /** Silent intros past this are left alone (taken for a misreading). */
    private static final long MAX_SKIPPED_INTRO_MS = 20_000;
    /** The most the next song is stretched to match tempos. */
    private static final double MAX_STRETCH = 0.08;
    /** The most a phase nudge adds to that, and how long it takes to close a gap. */
    private static final double MAX_NUDGE = 0.04;
    private static final double CATCH_UP_MS = 1500;
    /** Off-beat by less than this (of a beat): left alone. */
    private static final double LOCKED = 0.03;
    /** Tempo changes at most this often, and after the blend goes back to the song's own over this long. */
    private static final long TRIM_EVERY_MS = 200;
    private static final long RESTORE_MS = 4000;

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
    // Automix: the grids being found or found, by song, and the blend's.
    @Nullable private MeeTuneBeats.Probe mOutProbe;
    @Nullable private MeeTuneBeats.Probe mInProbe;
    @Nullable private String mOutGridId;
    @Nullable private String mInGridId;
    @Nullable private MeeTuneBeats.Grid mOutGrid;
    @Nullable private MeeTuneBeats.Grid mInGrid;
    @Nullable private MeeTuneBeats.Grid mMixOut;
    @Nullable private MeeTuneBeats.Grid mMixIn;
    @Nullable private String mMixInId;
    private double mMatch;
    private boolean mAligned;
    private float mTrim = 1f;
    private long mTrimAt;
    private long mRestoreAt;
    private float mRestoreFrom;
    /** The planned blend of song {@link #mPlanId}: where it starts, and how long (outgoing song time). */
    @Nullable private String mPlanId;
    private long mPlanHandoff;
    private long mPlanBlend;
    @Nullable private BassSwap mTailBass;
    /** Where the next song starts (its first full bar), the outgoing song's position for the bass swap. */
    private double mCueMs;
    private double mSwapOutMs;
    private long mSwapAt;
    private int mSeeks;
    private long mCheckAt;

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
        releaseProbes();
    }

    /** The blend's length, 0 when off. */
    private long fadeMs() {
        int seconds = com.newtube.mobile.ui.meetune.MeeTune.crossfadeSeconds(mContext);
        if (seconds == 0 && com.newtube.mobile.ui.meetune.MeeTune.automix(mContext)) seconds = AUTOMIX_DEFAULT_S;
        return seconds * 1000L;
    }

    /**
     * Automix: {@code next} is the song after this one; find the beat of this song's ending and of
     * {@code next}'s opening while this one still plays.
     */
    void analyzeNext(MediaItemFormatInfo next) {
        Media3PlayerController controller = mHost.controller();
        ExoPlayer main = controller != null ? controller.getExoPlayer() : null;
        MediaItemFormatInfo current = mHost.formatInfo();
        String currentId = mHost.videoId();
        String nextId = next.getVideoId();
        long fade = fadeMs();
        if (!mRunning || !com.newtube.mobile.ui.meetune.MeeTune.automix(mContext) || fade <= 0 || main == null
                || !mHost.crossfades()
                || current == null || currentId == null || !currentId.equals(current.getVideoId())
                || nextId == null || current.isLive() || next.isLive() || main.getDuration() == C.TIME_UNSET) {
            return;
        }
        long length = fade + ANALYZE_EXTRA_MS;
        probeEnding(controller, main, currentId);
        if (!nextId.equals(mInGridId)) {
            mInGridId = nextId;
            mInGrid = null;
            probe(controller, next, 0, length, false, grid -> {
                if (nextId.equals(mInGridId)) mInGrid = grid;
            });
        }
    }

    /** Automix: finds the beat of the playing song's ending, once per song, as soon as it plays. */
    private void probeEnding(Media3PlayerController controller, ExoPlayer main, @Nullable String id) {
        if (id == null || id.equals(mOutGridId) || !com.newtube.mobile.ui.meetune.MeeTune.automix(mContext)
                || !main.isPlaying() || fadeMs() <= 0 || !mHost.crossfades()) {
            return;
        }
        MediaItemFormatInfo current = mHost.formatInfo();
        long duration = main.getDuration();
        if (current == null || !id.equals(current.getVideoId()) || current.isLive() || duration == C.TIME_UNSET) {
            return;
        }
        long length = fadeMs() + ANALYZE_EXTRA_MS;
        mOutGridId = id;
        mOutGrid = null;
        probe(controller, current, Math.max(0, duration - length), length, true, grid -> {
            if (id.equals(mOutGridId)) mOutGrid = grid;
        });
    }

    /**
     * Automix: where song {@code id}'s blend starts and how long it is (whole bars, at least {@code fade}),
     * ending where its music does; null until its ending's beat is known, or when it holds no plan.
     */
    @Nullable
    private long[] planBlend(String id, long duration, long fade) {
        MeeTuneBeats.Grid out = id.equals(mOutGridId) ? mOutGrid : null;
        if (out == null) return null;
        double end = Math.min(duration, out.musicEndMs());
        if (!out.strong()) { // no beat to trust, but where the music ends still holds: fade out by then
            long start = Math.round(end - fade);
            return Double.isNaN(end) || start < duration - fade - MAX_SKIPPED_OUTRO_MS ? null : new long[] {start, fade};
        }
        double bar = 4 * out.periodMs;
        long blend = Math.round(Math.ceil(fade / bar) * bar);
        long start = Math.round(end - blend);
        if (Double.isNaN(end) || start < duration - fade - MAX_SKIPPED_OUTRO_MS || blend > 2 * fade) return null;
        return new long[] {start, blend};
    }

    private void bass(float gain, long rampMs) {
        BassSwap bass = mHost.mainBass();
        if (bass != null) bass.setLowGain(gain, rampMs);
    }

    private void probe(Media3PlayerController controller, MediaItemFormatInfo info, long fromMs, long lengthMs,
                       boolean out, java.util.function.Consumer<MeeTuneBeats.Grid> done) {
        String id = info.getVideoId();
        if (out && mOutProbe != null) mOutProbe.release();
        if (!out && mInProbe != null) mInProbe.release();
        controller.buildSourceAsync(info, source -> {
            if (source == null || !mRunning || !id.equals(out ? mOutGridId : mInGridId)) return;
            MeeTuneBeats.Probe probe = new MeeTuneBeats.Probe(mContext, source, fromMs, lengthMs,
                    (out ? "out " : "in ") + id, done);
            if (out) mOutProbe = probe; else mInProbe = probe;
        });
    }

    private void releaseProbes() {
        if (mOutProbe != null) mOutProbe.release();
        if (mInProbe != null) mInProbe.release();
        mOutProbe = mInProbe = null;
        mOutGridId = mInGridId = null;
        mOutGrid = mInGrid = null;
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
        if (mRestoreAt != 0) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - mRestoreAt) / (float) RESTORE_MS);
            if (t >= 1f || !java.util.Objects.equals(id, mMixInId)) {
                endTrim(controller);
            } else {
                setTrim(controller, mRestoreFrom + (1f - mRestoreFrom) * t, false);
            }
        }
        long next = stepState(controller, main, pos, id);
        return mRestoreAt != 0 ? Math.min(next, TRIM_EVERY_MS) : next;
    }

    private long stepState(Media3PlayerController controller, ExoPlayer main, long pos, @Nullable String id) {
        switch (mState) {
            case IDLE: {
                probeEnding(controller, main, id);
                long fade = fadeMs();
                long duration = main.getDuration();
                if (fade <= 0 || duration == C.TIME_UNSET || id == null || id.equals(mDoneId)
                        || !main.isPlaying() || !mHost.crossfades() || duration < 2 * fade + 10_000) {
                    return IDLE_TICK_MS;
                }
                if (!id.equals(mPlanId)) { // planned once, while there is still time to arm for it
                    long[] plan = planBlend(id, duration, fade);
                    if (plan != null && pos >= plan[0] - LEAD_MS - IDLE_TICK_MS
                            && pos + LEAD_MS + 2000 < plan[0] + plan[1]) {
                        // Seeked past the blend's start: blend from here to where the music ends, not
                        // through the silent outro.
                        long from = pos + LEAD_MS + IDLE_TICK_MS;
                        plan = new long[] {from, plan[0] + plan[1] - from};
                    }
                    if (plan != null && pos < plan[0] - LEAD_MS) {
                        mPlanId = id;
                        mPlanHandoff = plan[0];
                        mPlanBlend = plan[1];
                        Log.d(TAG, "automix plan " + id + " blend=" + plan[1] + "ms from=" + plan[0]
                                + " (outro left out " + (duration - plan[0] - plan[1]) + "ms)");
                    }
                }
                long handoff = id.equals(mPlanId) ? mPlanHandoff : duration - fade;
                fade = id.equals(mPlanId) ? mPlanBlend : fade;
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
                if (mOverlapAt != 0) {
                    float t = Math.min(1f, (now - mOverlapAt) / (float) OVERLAP_MS);
                    controller.setFadeGain((float) Math.cos(Math.PI / 2 * t));
                    if (t < 1f) return TICK_MS;
                    mOverlapAt = 0;
                    mHost.playEnd(); // now the next song
                    return TICK_MS;
                }
                if (mTail != null && !mTail.getPlayWhenReady()) {
                    finish(controller); // the tail was stopped (paused below)
                    return TICK_MS;
                }
                if (mMainStartAt == 0 && main.isPlaying()
                        && (!java.util.Objects.equals(mSongId, id) || pos < mHandoffMs - LEAD_MS)) {
                    // The next song (or this one again) plays: rise over what is left of the tail.
                    mMainStartAt = now;
                    mMainFadeMs = Math.max(600, mFadeEndAt - now);
                    startMix(id);
                }
                if (mMixIn != null && mTail != null) {
                    lockBeat(controller, main, id);
                }
                if (mTail != null) {
                    // The outgoing song holds for the blend's first half, then fades out smoothly by its end.
                    float p = Math.min(1f, (now - mFadeStartAt) / (float) Math.max(1, mFadeEndAt - mFadeStartAt));
                    float fall = Math.max(0f, (p - 0.5f) / 0.5f);
                    mTail.setVolume(mTailBase * (float) Math.cos(Math.PI / 2 * fall));
                    if (now >= mFadeEndAt || mTail.getPlaybackState() == Player.STATE_ENDED) {
                        releaseTail();
                    }
                }
                if (mMainStartAt != 0) {
                    float in = Math.min(1f, (now - mMainStartAt) / mMainFadeMs);
                    controller.setFadeGain((float) (1 - Math.cos(Math.PI * in)) / 2); // an S-curve: gentle in, gentle on
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
            mTailBass = new BassSwap();
            mTail = Media3PlayerInitializer.createTailPlayer(mContext, mHost.maxAudioBitrate(), mTailBass);
            mTail.setMediaSource(source, handoff);
            mTail.setPlayWhenReady(false);
            mTail.prepare();
        });
    }

    private float mTailBase = 1f;
    private long mFadeStartAt;
    private long mOverlapAt;
    private static final long OVERLAP_MS = 300;

    private void handoff(Media3PlayerController controller, ExoPlayer main) {
        mState = FADING;
        mDoneId = mSongId;
        mTail.setPlaybackParameters(main.getPlaybackParameters());
        // The outgoing song as loud as it was (the user's volume times its loudness level), not full:
        // at 1.0 it jumped up as the blend began.
        mTailBase = controller.getBaseVolume();
        mTail.setVolume(mTailBase);
        mTail.play();
        // The main keeps the song a moment longer, fading under the tail as its sound comes up; muted
        // at once, the blend began with a ~0.75 s hole (the tail's output takes that long to start).
        mOverlapAt = SystemClock.uptimeMillis();
        mFadeStartAt = SystemClock.uptimeMillis();
        mFadeEndAt = mFadeStartAt + Math.round(mFadeMs / Math.max(0.25f, main.getPlaybackParameters().speed));
        mMainStartAt = 0;
        mMixOut = mSongId.equals(mOutGridId) && mOutGrid != null && mOutGrid.strong() ? mOutGrid : null;
        mMixIn = null;
    }

    private void finish(Media3PlayerController controller) {
        releaseTail();
        controller.setFadeGain(1f);
        bass(1f, 1000); // no-op after a swap
        mState = IDLE;
        if (mMixIn != null && mTrim != 1f) { // the blend is over: back to the song's own tempo
            mRestoreAt = SystemClock.uptimeMillis();
            mRestoreFrom = mTrim;
        }
        mMixIn = null;
    }

    private void cancel() {
        releaseTail();
        Media3PlayerController controller = mHost.controller();
        if (controller != null) {
            controller.setFadeGain(1f);
            endTrim(controller);
        }
        bass(1f, 0);
        mState = IDLE;
        mMixIn = null;
    }

    /** Automix: the next song ({@code inId}) started; beat-match it if both grids hold and the tempos are close. */
    private void startMix(@Nullable String inId) {
        MeeTuneBeats.Grid out = mMixOut;
        MeeTuneBeats.Grid in = inId != null && inId.equals(mInGridId) ? mInGrid : null;
        if (out == null || in == null || !in.strong() || mTail == null) {
            if (com.newtube.mobile.ui.meetune.MeeTune.automix(mContext)) {
                Log.d(TAG, "automix off: out=" + out + " in=" + in);
            }
            bass(1f, 0);
            return;
        }
        // The next song's beat counted at the octave nearest the outgoing one (170 BPM over 85: every other beat).
        double ratio = in.periodMs / out.periodMs;
        double times = 1;
        while (ratio * times < 0.707) times *= 2;
        while (ratio * times > 1.414) times /= 2;
        double match = ratio * times;
        if (Math.abs(match - 1) > MAX_STRETCH) {
            Log.d(TAG, "automix off: tempos too far apart out=" + out + " in=" + in);
            bass(1f, 0);
            return;
        }
        mMixIn = in.scaled(times);
        mMixInId = inId;
        mMatch = match;
        mAligned = false;
        mSeeks = 0;
        mCheckAt = 0;
        mRestoreAt = 0;
        mSwapAt = 0;
        double cue = in.mixInMs();
        mCueMs = Double.isNaN(cue) || cue > MAX_SKIPPED_INTRO_MS ? in.firstMs : cue;
        // The bass swap: on the outgoing song's bar line nearest the blend's middle (else just the middle).
        long now = SystemClock.uptimeMillis();
        float speed = Math.max(0.25f, mTail.getPlaybackParameters().speed);
        double tailPos = mTail.getCurrentPosition();
        double left = (mFadeEndAt - now) * speed;
        double bar = 4 * out.periodMs;
        double mid = tailPos + left / 2;
        double swap = out.firstMs + Math.round((mid - out.firstMs) / bar) * bar;
        mSwapOutMs = swap > tailPos + 2 * out.periodMs && swap < tailPos + left - 2 * out.periodMs ? swap : mid;
        Log.d(TAG, "automix out=" + out + " in=" + in + " stretch=" + String.format(java.util.Locale.US, "%.4f", match)
                + " cue=" + Math.round(mCueMs) + " swapIn=" + Math.round((mSwapOutMs - tailPos) / speed) + "ms");
    }

    /** Keeps the next song's beats on the outgoing one's while both play. */
    private void lockBeat(Media3PlayerController controller, ExoPlayer main, @Nullable String id) {
        if (!java.util.Objects.equals(id, mMixInId)) {
            endTrim(controller); // something else plays now
            bass(1f, 0);
            mMixIn = null;
            return;
        }
        long tailPos = mTail.getCurrentPosition();
        long now = SystemClock.uptimeMillis();
        if (mSwapAt == 0 && tailPos >= mSwapOutMs) {
            // The bass swap, over one beat: the next song's bass in, the outgoing one's out.
            long beat = Math.round(mMixOut.periodMs / Math.max(0.25f, mTail.getPlaybackParameters().speed));
            bass(1f, beat);
            // The outgoing song's bass eases out over the rest of the blend: cut in one beat, it sounded
            // like the song suddenly falling away (most of its weight is bass with a boosted equalizer).
            if (mTailBass != null) mTailBass.setLowGain(0f, Math.max(beat, mFadeEndAt - now));
            mSwapAt = now;
            Log.d(TAG, "automix bass swap at=" + tailPos);
        }
        if (!main.isPlaying() || !mTail.isPlaying()) return;
        long inPos = main.getCurrentPosition();
        // How far (in beats, -0.5 to 0.5) the next song's beat trails the outgoing one's.
        double behind = mMixOut.phase(tailPos) - mMixIn.phase(inPos);
        behind -= Math.floor(behind + 0.5);
        if (!mAligned) {
            setTrim(controller, (float) mMatch, true);
            if (inPos < 200) return; // let its position settle first
            mAligned = true;
            long target;
            if (mSeeks == 0) {
                // To its first full bar (past a silent intro), as far into it as the outgoing song is into its own.
                double outBeats = (tailPos - mMixOut.firstMs) / mMixOut.periodMs;
                double intoBar = outBeats - 4 * Math.floor(outBeats / 4);
                target = Math.round(mCueMs + intoBar * mMixIn.periodMs);
            } else { // the first jump landed off the beat (a slow seek): forward onto it
                target = inPos + Math.round((behind < 0 ? behind + 1 : behind) * mMixIn.periodMs);
            }
            mSeeks++;
            main.seekTo(Math.max(0, target));
            mCheckAt = now + 1000;
            Log.d(TAG, "automix align #" + mSeeks + " " + inPos + "->" + target + "ms");
            return;
        }
        if (mCheckAt != 0 && now >= mCheckAt) {
            mCheckAt = 0;
            Log.d(TAG, "automix lock off by " + Math.round(behind * mMixOut.periodMs) + "ms");
            if (Math.abs(behind) > 0.15 && mSeeks < 1 && now - mMainStartAt < mMainFadeMs / 2) {
                mAligned = false; // still quiet: jump again rather than nudge for seconds
                return;
            }
        }
        double nudge = Math.abs(behind) < LOCKED ? 0
                : Math.max(-MAX_NUDGE, Math.min(MAX_NUDGE, behind * mMixOut.periodMs / CATCH_UP_MS));
        setTrim(controller, (float) (mMatch * (1 + nudge)), false);
    }

    private void setTrim(Media3PlayerController controller, float trim, boolean now) {
        long at = SystemClock.uptimeMillis();
        if (Math.abs(trim - mTrim) < 0.002f || (!now && at - mTrimAt < TRIM_EVERY_MS)) return;
        mTrim = trim;
        mTrimAt = at;
        controller.setTempoTrim(trim);
    }

    private void endTrim(Media3PlayerController controller) {
        if (mTrim != 1f) controller.setTempoTrim(1f);
        mTrim = 1f;
        mRestoreAt = 0;
    }

    private void releaseTail() {
        mOverlapAt = 0;
        if (mTail != null) {
            mTail.release();
            mTail = null;
        }
    }
}
