package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * MEETUNE(automix): a song's beat grid (its tempo, where its bars start, how loud each beat is) from a
 * stretch of its audio, for Automix's beat-matched crossfade. A {@link Probe} decodes the stretch with a
 * silent player that runs as fast as the network and decoder allow; {@link BeatThis} finds its beats and
 * downbeats, and {@link #grid} fits a straight grid through them. Too few beats, or beats that don't hold
 * to one tempo (speech, ambient, rubato, a tempo change), give no grid worth mixing on.
 */
final class MeeTuneBeats {
    private static final String TAG = "MeeTuneBeats";
    static final int HOP_MS = 10;
    /** The share of the grid's beats the model heard, below which it is not steady enough to mix on. */
    // ponytail: one fixed threshold; tune per genre from the "grid" log lines if it lets bad grids through.
    static final double MIN_STRENGTH = 0.75;
    private static final int MIN_BEATS = 16;
    /** The model runs here, one stretch at a time, below the song being heard. */
    private static final java.util.concurrent.Executor ANALYSIS =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "MeeTuneBeats");
                thread.setDaemon(true);
                return thread;
            });

    private MeeTuneBeats() {
    }

    /**
     * Beats every {@link #periodMs}, one of them at {@link #firstMs} (both in the song's own time), the first
     * of a bar (a bar is 4 beats from it), with each analyzed beat's loudness. The grid holds from firstMs to
     * {@link #untilMs}: the steady stretch it was fitted on.
     */
    static final class Grid {
        /** Under the stretch's median beat by this much (ln of power): quiet; by the second: near silent. */
        private static final double QUIET = Math.log(Math.pow(10, 0.6)); // 6 dB
        private static final double SILENT = Math.log(Math.pow(10, 1.2)); // 12 dB

        final double periodMs;
        final double firstMs;
        final double strength;
        /** Mean ln power of beat {@code k} from {@link #firstMs}; null on a {@link #scaled} grid. */
        @Nullable final double[] levels;
        final double untilMs;

        Grid(double periodMs, double firstMs, double strength, @Nullable double[] levels, double untilMs) {
            this.periodMs = periodMs;
            this.firstMs = firstMs;
            this.strength = strength;
            this.levels = levels;
            this.untilMs = untilMs;
        }

        /** Bars (of 4 beats from firstMs) whose end the steady stretch reaches. */
        private int steadyBeats() {
            int beats = (int) Math.floor((untilMs - firstMs) / periodMs + 0.5);
            return levels == null ? 0 : Math.min(levels.length, beats) / 4 * 4;
        }

        /** The first bar line from which a whole bar plays at full level (a drop, past a quiet intro), or NaN. */
        double energyMs() {
            double floor = median() - QUIET;
            for (int k = 0; k + 4 <= steadyBeats(); k += 4) {
                if (barLevel(k) >= floor) return firstMs + k * periodMs;
            }
            return Double.NaN;
        }

        /** The first bar line from which a whole bar plays above near silence (past a silent intro), or NaN. */
        double mixInMs() {
            double floor = median() - SILENT;
            for (int k = 0; k + 4 <= steadyBeats(); k += 4) {
                if (barLevel(k) >= floor) return firstMs + k * periodMs;
            }
            return Double.NaN;
        }

        /** The bar line ending the last bar at full level (before a fade-out, a quiet outro or silence), or NaN. */
        double musicEndMs() {
            double floor = median() - QUIET;
            for (int k = steadyBeats() - 4; k >= 0; k -= 4) {
                if (barLevel(k) >= floor) return firstMs + (k + 4) * periodMs;
            }
            return Double.NaN;
        }

        private double barLevel(int k) {
            return (levels[k] + levels[k + 1] + levels[k + 2] + levels[k + 3]) / 4;
        }

        private double median() {
            if (levels == null || levels.length == 0) return Double.NaN;
            double[] sorted = levels.clone();
            Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }

        /** A beat steady enough to mix on. */
        boolean strong() {
            return strength >= MIN_STRENGTH;
        }

        double bpm() {
            return 60_000 / periodMs;
        }

        /** Where {@code posMs} falls between two beats, 0 (on a beat) to 1. */
        double phase(double posMs) {
            double beats = (posMs - firstMs) / periodMs;
            return beats - Math.floor(beats);
        }

        /** The same beat counted every {@code times} its period (2: every other beat; 0.5: on the half beats). */
        Grid scaled(double times) {
            return new Grid(periodMs * times, firstMs, strength, null, untilMs);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.2fbpm@%.0fms s=%.2f", bpm(), firstMs, strength);
        }
    }

    /** Mono samples in: kept for the model, and their loudness every {@link #HOP_MS}. */
    static final class Audio {
        final int rate;
        private final int mHop;
        private double mSum;
        private int mCount;
        float[] samples = new float[1 << 20];
        int size;
        private float[] mLevel = new float[4096];
        private int mLevels;
        /** The song time of the first sample. */
        double firstMs = Double.NaN;

        Audio(int sampleRate) {
            rate = sampleRate;
            mHop = Math.max(1, sampleRate * HOP_MS / 1000);
        }

        void add(float sample) {
            if (size == samples.length) samples = Arrays.copyOf(samples, size * 2);
            samples[size++] = sample;
            mSum += sample * sample;
            if (++mCount < mHop) return;
            if (mLevels == mLevel.length) mLevel = Arrays.copyOf(mLevel, mLevels * 2);
            mLevel[mLevels++] = (float) Math.log(mSum / mCount + 1e-7);
            mSum = 0;
            mCount = 0;
        }
    }

    /**
     * The grid through {@code beats} and {@code downs} (song ms, ascending), with each beat's loudness from
     * {@code audio}; null under {@link #MIN_BEATS}.
     */
    @Nullable
    static Grid grid(double[] beats, double[] downs, Audio audio) {
        int n = beats.length;
        if (n < MIN_BEATS) return null;
        double[] gaps = new double[n - 1];
        for (int i = 1; i < n; i++) gaps[i - 1] = beats[i] - beats[i - 1];
        double[] sorted = gaps.clone();
        Arrays.sort(sorted);
        double gap = sorted[sorted.length / 2];
        // The steady stretch: the longest run of beats a gap apart (or two, a missed one), within 8%. An intro
        // without drums, a breakdown or a tempo change is left out rather than bending the line.
        int runFrom = 0;
        int bestFrom = 0;
        int bestTo = 0;
        for (int i = 1; i <= n; i++) {
            double r = i < n ? gaps[i - 1] / gap : 0;
            if (i < n && (Math.abs(r - 1) < 0.08 || Math.abs(r - 2) < 0.16)) continue;
            if (i - 1 - runFrom > bestTo - bestFrom) {
                bestFrom = runFrom;
                bestTo = i - 1;
            }
            runFrom = i;
        }
        beats = Arrays.copyOfRange(beats, bestFrom, bestTo + 1);
        n = beats.length;
        if (n < MIN_BEATS) return null;
        // Each beat's number on the grid (a missed beat skips one), then a line through them all.
        long[] k = new long[n];
        for (int i = 1; i < n; i++) k[i] = k[i - 1] + Math.max(1, Math.round((beats[i] - beats[i - 1]) / gap));
        double mk = 0;
        double mb = 0;
        for (int i = 0; i < n; i++) {
            mk += k[i];
            mb += beats[i];
        }
        mk /= n;
        mb /= n;
        double skk = 0;
        double skb = 0;
        for (int i = 0; i < n; i++) {
            skk += (k[i] - mk) * (k[i] - mk);
            skb += (k[i] - mk) * (beats[i] - mb);
        }
        double period = skb / skk;
        double origin = mb - period * mk; // where beat 0 falls
        // Strength: the share of the grid's beats the model heard within a twelfth of a beat of it.
        int on = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(beats[i] - origin - k[i] * period) < period / 12) on++;
        }
        // Strength: that share, scaled down for a stretch under 16 s (too short to blend over and trust).
        double until = beats[n - 1] + period;
        double strength = (double) on / (k[n - 1] + 1) * Math.min(1, (until - beats[0]) / 16_000);
        // The bar: the beat of four the downbeats land on most.
        int[] votes = new int[4];
        for (double d : downs) {
            if (d >= beats[0] - period / 2 && d < until) votes[(int) Math.floorMod(Math.round((d - origin) / period), 4L)]++;
        }
        int down = 0;
        for (int r = 1; r < 4; r++) {
            if (votes[r] > votes[down]) down = r;
        }
        double first = origin + down * period;
        // Each beat's loudness from that bar line on (level j is the 10 ms ending at firstMs + (j + 1) * 10).
        double hop0 = (first - audio.firstMs) / HOP_MS - 1;
        double hops = period / HOP_MS;
        int count = Math.max(0, (int) Math.floor((audio.mLevels - hop0) / hops));
        double[] levels = new double[count];
        for (int b = 0; b < count; b++) {
            int from = Math.max(0, (int) Math.round(hop0 + b * hops));
            int to = Math.min(audio.mLevels, (int) Math.round(hop0 + (b + 1) * hops));
            double power = 0; // the beat's mean power, not its mean log: a hit is most of a beat's sound
            for (int j = from; j < to; j++) power += Math.exp(audio.mLevel[j]);
            levels[b] = to > from ? Math.log(power / (to - from)) : -16;
        }
        return new Grid(period, first, strength, levels, until);
    }

    /**
     * Analyzes {@code [fromMs, fromMs + lengthMs)} of the song {@code source} plays, then hands the grid (or
     * null) to {@code done} on main. Main thread only; {@link #release} drops it unanswered.
     */
    static final class Probe {
        private final Handler mMain = new Handler(Looper.getMainLooper());
        private final Consumer<Grid> mDone;
        private final String mLabel;
        private final long mFromMs;
        private final long mStartedAt = android.os.SystemClock.uptimeMillis();
        @Nullable private ExoPlayer mPlayer;
        @Nullable private TapSink mSink;
        private final Context mContext;
        private boolean mDropped;
        /**
         * Its own low priority thread: ExoPlayer's playback thread runs at audio priority, and this one decoding
         * flat out took the CPU from the song being heard (jitters as each song started).
         */
        private final android.os.HandlerThread mThread =
                new android.os.HandlerThread("MeeTuneProbe", android.os.Process.THREAD_PRIORITY_BACKGROUND);

        Probe(Context context, MediaSource source, long fromMs, long lengthMs, String label, Consumer<Grid> done) {
            mDone = done;
            mContext = context.getApplicationContext();
            mLabel = label;
            mFromMs = fromMs;
            TapSink sink = new TapSink(fromMs + lengthMs, () -> mMain.post(this::finish));
            mSink = sink;
            DefaultTrackSelector selector = new DefaultTrackSelector(context);
            selector.setParameters(selector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setForceLowestBitrate(true));
            mPlayer = new ExoPlayer.Builder(context, new DefaultRenderersFactory(context) {
                @Override
                protected AudioSink buildAudioSink(Context c, boolean floatOutput, boolean playbackParams) {
                    return sink;
                }
            }).setTrackSelector(selector)
                    .setPlaybackLooper(startThread())
                    // It always plays up to what is loaded: start again at once, not after 5 s more.
                    .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                            .setBufferDurationsMs(30_000, 60_000, 500, 500).build())
                    .build();
            mPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) finish(); // the song ended inside the stretch
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.w(TAG, mLabel + " probe failed: " + error.getErrorCodeName());
                    finish();
                }
            });
            mPlayer.setMediaSource(source, fromMs);
            mPlayer.setPlayWhenReady(true);
            mPlayer.prepare();
        }

        private void finish() {
            if (mPlayer == null) return;
            TapSink sink = mSink;
            stopPlayer();
            Audio audio = sink.mAudio;
            ANALYSIS.execute(() -> {
                // A first sample far from where the probe started means the timestamps are not the song's own.
                double[][] beats = audio != null && Math.abs(audio.firstMs - mFromMs) < 3000
                        ? BeatThis.track(mContext, audio.samples, audio.size, audio.rate) : null;
                if (beats != null) {
                    for (double[] times : beats) {
                        for (int i = 0; i < times.length; i++) times[i] += audio.firstMs;
                    }
                }
                Grid grid = beats != null ? grid(beats[0], beats[1], audio) : null;
                String line = mLabel + " grid=" + grid + " beats=" + (beats != null ? beats[0].length : -1)
                        + " downbeats=" + (beats != null ? beats[1].length : -1)
                        + " from=" + (audio != null ? Math.round(audio.firstMs) : -1)
                        + " took=" + (android.os.SystemClock.uptimeMillis() - mStartedAt) + "ms"
                        + (grid != null ? " in=" + Math.round(grid.mixInMs()) + " end=" + Math.round(grid.musicEndMs()) : "");
                mMain.post(() -> {
                    Log.d(TAG, line);
                    if (!mDropped) mDone.accept(grid);
                });
            });
        }

        private Looper startThread() {
            mThread.start();
            return mThread.getLooper();
        }

        /** Drops it unanswered, even one whose analysis is already running. */
        void release() {
            mDropped = true;
            stopPlayer();
        }

        private void stopPlayer() {
            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
                mSink = null;
                mThread.quitSafely();
            }
        }
    }

    /**
     * An audio "output" that only measures: it takes every buffer at once and reports the position as the end
     * of the last one, so the player decodes as fast as it can and plays nothing. 16-bit PCM only (what the
     * MediaCodec decoders give unless asked for float).
     */
    private static final class TapSink implements AudioSink {
        private final long mEndUs;
        private final Runnable mOnFull;
        @Nullable volatile Audio mAudio;
        private int mChannels = 2;
        private int mSampleRate = 48_000;
        private long mOffsetUs;
        private long mPositionUs = CURRENT_POSITION_NOT_SET;
        private boolean mFull;
        private boolean mEnded;
        private PlaybackParameters mParameters = PlaybackParameters.DEFAULT;
        private AudioAttributes mAttributes = AudioAttributes.DEFAULT;
        private boolean mSkipSilence;

        TapSink(long endMs, Runnable onFull) {
            mEndUs = endMs * 1000;
            mOnFull = onFull;
        }

        @Override
        public boolean supportsFormat(Format format) {
            return getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED;
        }

        @Override
        public int getFormatSupport(Format format) {
            return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType) && format.pcmEncoding == C.ENCODING_PCM_16BIT
                    ? SINK_FORMAT_SUPPORTED_DIRECTLY : SINK_FORMAT_UNSUPPORTED;
        }

        @Override
        public void configure(Format format, int bufferSize, @Nullable int[] outputChannels) {
            mChannels = Math.max(1, format.channelCount);
            mSampleRate = Math.max(1, format.sampleRate);
        }

        @Override
        public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int count) {
            int frames = buffer.remaining() / (2 * mChannels);
            if (!mFull && frames > 0) {
                Audio audio = mAudio;
                if (audio == null) {
                    audio = new Audio(mSampleRate);
                    audio.firstMs = (presentationTimeUs - mOffsetUs) / 1000.0;
                    mAudio = audio;
                }
                ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int base = pcm.position();
                for (int f = 0; f < frames; f++) {
                    int sum = 0;
                    for (int c = 0; c < mChannels; c++) sum += pcm.getShort(base + (f * mChannels + c) * 2);
                    audio.add(sum / (32768f * mChannels));
                }
                if (presentationTimeUs - mOffsetUs >= mEndUs) {
                    mFull = true;
                    mOnFull.run();
                }
            }
            mPositionUs = presentationTimeUs + frames * 1_000_000L / mSampleRate;
            buffer.position(buffer.limit());
            return true;
        }

        @Override
        public long getCurrentPositionUs(boolean sourceEnded) {
            return mPositionUs;
        }

        @Override
        public void setOutputStreamOffsetUs(long offsetUs) {
            mOffsetUs = offsetUs;
        }

        @Override
        public void playToEndOfStream() {
            mEnded = true;
            // The song ended inside the stretch (its audio often stops short of the listed length): done
            // now. Waiting for the player's own end took as long as the stretch lasts, in real time.
            if (!mFull) {
                mFull = true;
                mOnFull.run();
            }
        }

        @Override
        public boolean isEnded() {
            return mEnded;
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void flush() {
            mPositionUs = CURRENT_POSITION_NOT_SET;
            mEnded = false;
        }

        @Override
        public void reset() {
            flush();
        }

        @Override
        public void setListener(Listener listener) {
        }

        @Override
        public void play() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void handleDiscontinuity() {
        }

        @Override
        public void setPlaybackParameters(PlaybackParameters parameters) {
            mParameters = parameters;
        }

        @Override
        public PlaybackParameters getPlaybackParameters() {
            return mParameters;
        }

        @Override
        public void setSkipSilenceEnabled(boolean enabled) {
            mSkipSilence = enabled;
        }

        @Override
        public boolean getSkipSilenceEnabled() {
            return mSkipSilence;
        }

        @Override
        public void setAudioAttributes(AudioAttributes attributes) {
            mAttributes = attributes;
        }

        @Override
        public AudioAttributes getAudioAttributes() {
            return mAttributes;
        }

        @Override
        public void setAudioSessionId(int id) {
        }

        @Override
        public void setAuxEffectInfo(AuxEffectInfo info) {
        }

        @Override
        public long getAudioTrackBufferSizeUs() {
            return C.TIME_UNSET;
        }

        @Override
        public void enableTunnelingV21() {
        }

        @Override
        public void disableTunneling() {
        }

        @Override
        public void setVolume(float volume) {
        }
    }
}
