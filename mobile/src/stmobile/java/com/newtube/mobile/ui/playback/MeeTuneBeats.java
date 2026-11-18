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
 * MEETUNE(automix): a song's beat grid (its tempo, and where its beats fall) from a stretch of its audio,
 * for Automix's beat-matched crossfade. A {@link Probe} decodes the stretch with a silent player that runs
 * as fast as the network and decoder allow; an {@link Onsets} meter turns the sound into an onset curve
 * (how sharply the loudness, overall and in the bass, rises every 10 ms) and {@link #analyze} finds the
 * beat in it: the tempo from the curve's autocorrelation, the phase from where the beats land, both
 * refined by a fit through the beats' peaks. Too weak a beat (speech, ambient, rubato) gives no grid.
 */
final class MeeTuneBeats {
    private static final String TAG = "MeeTuneBeats";
    static final int HOP_MS = 10;
    private static final double MIN_BPM = 70;
    private static final double MAX_BPM = 180;
    /** Onset on the beats over onset at any phase, below which the beat is too weak to mix on. */
    // ponytail: one fixed threshold; tune per genre from the "grid" log lines if it lets bad grids through.
    static final double MIN_STRENGTH = 1.8;

    private MeeTuneBeats() {
    }

    /**
     * Beats every {@link #periodMs}, one of them at {@link #firstMs} (both in the song's own time), the first
     * of a bar as far as the kick tells (a bar is 4 beats from it), with each analyzed beat's loudness.
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

        Grid(double periodMs, double firstMs, double strength, @Nullable double[] levels) {
            this.periodMs = periodMs;
            this.firstMs = firstMs;
            this.strength = strength;
            this.levels = levels;
        }

        /** The first bar line from which a whole bar plays above near silence (past a silent intro), or NaN. */
        double mixInMs() {
            double floor = median() - SILENT;
            for (int k = 0; levels != null && k + 4 <= levels.length; k += 4) {
                if (barLevel(k) >= floor) return firstMs + k * periodMs;
            }
            return Double.NaN;
        }

        /** The bar line ending the last bar at full level (before a fade-out, a quiet outro or silence), or NaN. */
        double musicEndMs() {
            double floor = median() - QUIET;
            for (int k = levels != null ? (levels.length / 4 - 1) * 4 : -1; k >= 0; k -= 4) {
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
            return new Grid(periodMs * times, firstMs, strength, null);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.2fbpm@%.0fms s=%.2f", bpm(), firstMs, strength);
        }
    }

    /** Mono samples in, one onset value per {@link #HOP_MS} out. */
    static final class Onsets {
        private final int mHop;
        private final float mLowAlpha;
        private float mLowPass;
        private double mSum;
        private double mLowSum;
        private int mCount;
        private double mPrev = Double.NaN;
        private double mPrevLow;
        private float[] mValues = new float[4096];
        private float[] mLow = new float[4096];
        private float[] mLevel = new float[4096];
        private int mSize;
        double firstMs = Double.NaN;

        Onsets(int sampleRate) {
            mHop = Math.max(1, sampleRate * HOP_MS / 1000);
            mLowAlpha = (float) (1 - Math.exp(-2 * Math.PI * 150 / sampleRate)); // bass and kick, under ~150 Hz
        }

        void add(float sample) {
            mLowPass += mLowAlpha * (sample - mLowPass);
            mSum += sample * sample;
            mLowSum += mLowPass * mLowPass;
            if (++mCount < mHop) return;
            double level = Math.log(mSum / mCount + 1e-7);
            double low = Math.log(mLowSum / mCount + 1e-7);
            if (!Double.isNaN(mPrev)) {
                if (mSize == mValues.length) {
                    mValues = Arrays.copyOf(mValues, mSize * 2);
                    mLow = Arrays.copyOf(mLow, mSize * 2);
                    mLevel = Arrays.copyOf(mLevel, mSize * 2);
                }
                mLow[mSize] = (float) Math.max(0, low - mPrevLow);
                mLevel[mSize] = (float) level;
                mValues[mSize] = (float) Math.max(0, level - mPrev) + mLow[mSize];
                mSize++;
            }
            mPrev = level;
            mPrevLow = low;
            mSum = mLowSum = 0;
            mCount = 0;
        }

        int size() {
            return mSize;
        }

        float[] values() {
            return mValues;
        }
    }

    /** The beat grid of what {@code onsets} measured (see {@link Grid#strong}), or null when shorter than 10 s or silent. */
    @Nullable
    static Grid analyze(Onsets onsets) {
        return analyze(onsets.mValues, onsets.mLow, onsets.mLevel, onsets.size(), onsets.firstMs);
    }

    /** Onset curves whose value {@code i} is at {@code firstMs + (i + 1) * HOP_MS}: all, bass, and loudness. */
    @Nullable
    private static Grid analyze(float[] raw, float[] low, float[] level, int n, double firstMs) {
        if (n < 1000) return null;
        // Onsets over their local average (half a second each side): steady loudness is not a beat.
        float[] o = new float[n];
        int w = 50;
        double run = 0;
        for (int i = 0; i < Math.min(n, w); i++) run += raw[i];
        for (int i = 0; i < n; i++) {
            if (i + w < n) run += raw[i + w];
            if (i - w - 1 >= 0) run -= raw[i - w - 1];
            int count = Math.min(n - 1, i + w) - Math.max(0, i - w) + 1;
            o[i] = (float) Math.max(0, raw[i] - run / count);
        }
        double total = 0;
        for (float v : o) total += v;
        if (total <= 0) return null;

        // Tempo: the lag whose autocorrelation, with its double's, is strongest, leaning toward ~120 BPM
        // where most songs' beat (not their bar or eighth notes) lies.
        int minLag = (int) Math.floor(60_000 / MAX_BPM / HOP_MS);
        int maxLag = (int) Math.ceil(60_000 / MIN_BPM / HOP_MS);
        double[] ac = new double[2 * maxLag + 3];
        for (int lag = minLag - 1; lag < ac.length; lag++) {
            double s = 0;
            for (int i = lag; i < n; i++) s += o[i] * o[i - lag];
            ac[lag] = s / (n - lag);
        }
        double[] score = new double[maxLag + 2];
        int best = -1;
        for (int lag = minLag - 1; lag <= maxLag + 1; lag++) {
            double octaves = Math.log(60_000.0 / (lag * HOP_MS) / 120) / Math.log(2);
            score[lag] = (ac[lag] + 0.5 * ac[2 * lag]) * Math.exp(-0.5 * octaves * octaves);
            if (lag >= minLag && lag <= maxLag && (best < 0 || score[lag] > score[best])) best = lag;
        }
        double period = best + parabola(score[best - 1], score[best], score[best + 1]);

        // Phase: the offset whose beats land on the most onset.
        // Strength: that sum over the average phase's (a steady beat stands out; noise or rubato is flat).
        double bestPhase = 0;
        double bestSum = -1;
        double allSums = 0;
        int phases = (int) Math.ceil(period);
        for (int phase = 0; phase < phases; phase++) {
            double s = 0;
            for (double t = phase; t < n; t += period) s += o[Math.min(n - 1, (int) Math.round(t))];
            allSums += s;
            if (s > bestSum) {
                bestSum = s;
                bestPhase = phase;
            }
        }
        double strength = bestSum / (allSums / phases);

        // Refine: a line through each beat's own peak (near where the grid puts it), weighted by its onset.
        double sw = 0, sk = 0, sj = 0, skk = 0, skj = 0;
        for (int k = 0; bestPhase + k * period < n; k++) {
            double center = bestPhase + k * period;
            int from = Math.max(1, (int) Math.round(center - period / 6));
            int to = Math.min(n - 2, (int) Math.round(center + period / 6));
            int peak = -1;
            for (int j = from; j <= to; j++) {
                if (peak < 0 || o[j] > o[peak]) peak = j;
            }
            if (peak < 0 || o[peak] <= 0) continue;
            double j = peak + parabola(o[peak - 1], o[peak], o[peak + 1]);
            double wk = o[peak];
            sw += wk;
            sk += wk * k;
            sj += wk * j;
            skk += wk * k * k;
            skj += wk * k * j;
        }
        double denominator = sw * skk - sk * sk;
        if (denominator > 0) {
            double slope = (sw * skj - sk * sj) / denominator;
            if (Math.abs(slope / period - 1) < 0.03) {
                period = slope;
                bestPhase = (sj - slope * sk) / sw;
            }
        }

        // Bar: the beat of four the kick (bass onsets) hits hardest is taken as the first.
        double[] bar = new double[4];
        for (int k = 0; bestPhase + k * period < n; k++) {
            int at = (int) Math.round(bestPhase + k * period);
            float peak = 0;
            for (int j = Math.max(0, at - 2); j <= Math.min(n - 1, at + 2); j++) peak = Math.max(peak, low[j]);
            bar[k % 4] += peak;
        }
        int down = 0;
        for (int r = 1; r < 4; r++) {
            if (bar[r] > bar[down]) down = r;
        }
        double first = bestPhase + down * period;

        // Each beat's loudness, from that bar line on.
        int beats = Math.max(0, (int) Math.floor((n - first) / period));
        double[] levels = new double[beats];
        for (int k = 0; k < beats; k++) {
            int from = Math.max(0, (int) Math.round(first + k * period));
            int to = Math.min(n, (int) Math.round(first + (k + 1) * period));
            double power = 0; // the beat's mean power, not its mean log: a hit is most of a beat's sound
            for (int j = from; j < to; j++) power += Math.exp(level[j]);
            levels[k] = to > from ? Math.log(power / (to - from)) : -16;
        }
        return new Grid(period * HOP_MS, firstMs + (first + 1) * HOP_MS, strength, levels);
    }

    /** The peak's offset (-0.5..0.5) from the middle of three samples. */
    private static double parabola(double left, double mid, double right) {
        double d = left - 2 * mid + right;
        return d < 0 ? Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / d)) : 0;
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

        Probe(Context context, MediaSource source, long fromMs, long lengthMs, String label, Consumer<Grid> done) {
            mDone = done;
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
            release();
            Onsets onsets = sink.mOnsets;
            // A first sample far from where the probe started means the timestamps are not the song's own.
            Grid grid = onsets != null && Math.abs(onsets.firstMs - mFromMs) < 3000
                    ? analyze(onsets) : null;
            Log.d(TAG, mLabel + " grid=" + grid + " frames=" + (onsets != null ? onsets.size() : 0)
                    + " from=" + (onsets != null ? Math.round(onsets.firstMs) : -1)
                    + " took=" + (android.os.SystemClock.uptimeMillis() - mStartedAt) + "ms"
                    + (grid != null ? " in=" + Math.round(grid.mixInMs()) + " end=" + Math.round(grid.musicEndMs()) : ""));
            mDone.accept(grid);
        }

        void release() {
            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
                mSink = null;
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
        @Nullable volatile Onsets mOnsets;
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
                Onsets onsets = mOnsets;
                if (onsets == null) {
                    onsets = new Onsets(mSampleRate);
                    onsets.firstMs = (presentationTimeUs - mOffsetUs) / 1000.0;
                    mOnsets = onsets;
                }
                ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int base = pcm.position();
                for (int f = 0; f < frames; f++) {
                    int sum = 0;
                    for (int c = 0; c < mChannels; c++) sum += pcm.getShort(base + (f * mChannels + c) * 2);
                    onsets.add(sum / (32768f * mChannels));
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
            // The song ended inside the stretch (a song's ending always does): done now. Waiting for the
            // player's own ENDED took ~38 s more, so the blend began before the ending's beats were known.
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
