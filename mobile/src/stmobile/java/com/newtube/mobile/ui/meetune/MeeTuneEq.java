package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * MEETUNE: MeeTune's own equalizer, for MeeTune songs only (MeeTube's equalizer is MeeTube's and is
 * left alone). Seven bands (60 Hz to 14 kHz) run as biquads in the player's audio sink, so the curve
 * is exact and the same on every phone. Two ways to shape it:
 * <ul>
 *     <li>Dynamic: one point on a pad. Left/right tilts the sound warmer or brighter, down scoops the
 *     middle and up brings it forward; Broad moves wide stretches, Focused only the ends.</li>
 *     <li>Manual: the seven bands one by one, from a preset or your own.</li>
 * </ul>
 * Balance quietens one ear. Off, or a MeeTube video playing: the processor copies the sound untouched.
 */
public final class MeeTuneEq {
    static final int[] FREQUENCIES = {60, 150, 400, 1000, 2500, 6000, 14000};
    static final String[] LABELS = {"60", "150", "400", "1k", "2.5k", "6k", "14k"};
    static final float MAX_DB = 12f;
    /** The pad's points each way from its centre: -5..5 on both axes. */
    static final int TONE_STEPS = 5;
    static final int DYNAMIC = 0;
    static final int MANUAL = 1;
    static final int CUSTOM = -1;
    /** Gains per preset, in the order of the {@code meetune_eq_presets} names: Flat first. */
    static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0},       // Flat
            {6, 4, 1, 0, 0, 0, 0},       // Bass boost
            {-6, -4, -1, 0, 0, 0, 0},    // Bass reducer
            {0, 0, 0, 0, 2, 4, 6},       // Treble boost
            {-2, -1, 1, 3, 3, 1, 0},     // Vocal
            {4, 2, -1, -1, 1, 3, 4},     // Rock
            {-1, 1, 3, 3, 1, -1, -1},    // Pop
            {3, 2, 0, 1, -1, 2, 3},      // Jazz
            {3, 2, -1, 0, 0, 2, 3},      // Classical
            {5, 3, 0, -1, 1, 3, 4},      // Electronic
            {5, 4, 1, -1, 0, 1, 2},      // Hip hop
            {3, 1, 1, 2, 2, 2, 1},       // Acoustic
            {5, 2, 0, -1, 0, 2, 5},      // Loudness
    };

    private static final double DB_PER_STEP = 1.2;

    /** The curve; null = off. Read on the audio thread, swapped from the UI. */
    @Nullable
    private static volatile Curve sCurve;
    /** A MeeTune song is playing (set by the music player); MeeTube videos keep their sound. */
    private static volatile boolean sPlaying;
    private static boolean sLoaded;

    private MeeTuneEq() {
    }

    /** A processor for one audio sink; it follows every change here live. */
    public static AudioProcessor processor(Context context) {
        synchronized (MeeTuneEq.class) {
            if (!sLoaded) {
                sLoaded = true;
                apply(context);
            }
        }
        return new Processor();
    }

    /** The player says whether what plays now is a MeeTune song. */
    public static void setPlaying(boolean playing) {
        sPlaying = playing;
    }

    /** A MeeTune song is playing now (MeeTuneSpatial follows it too). */
    static boolean isPlaying() {
        return sPlaying;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("meetune", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------------------ settings

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("eq_enabled", false);
    }

    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean("eq_enabled", on).apply();
        apply(context);
    }

    static int mode(Context context) {
        return prefs(context).getInt("eq_mode", DYNAMIC);
    }

    static void setMode(Context context, int mode) {
        prefs(context).edit().putInt("eq_mode", mode).apply();
        apply(context);
    }

    static int toneX(Context context) {
        return prefs(context).getInt("eq_tone_x", 0);
    }

    static int toneY(Context context) {
        return prefs(context).getInt("eq_tone_y", 0);
    }

    static void setTone(Context context, int x, int y) {
        prefs(context).edit().putInt("eq_tone_x", clampStep(x)).putInt("eq_tone_y", clampStep(y)).apply();
        apply(context);
    }

    static boolean isFocused(Context context) {
        return prefs(context).getBoolean("eq_tone_focused", false);
    }

    static void setFocused(Context context, boolean focused) {
        prefs(context).edit().putBoolean("eq_tone_focused", focused).apply();
        apply(context);
    }

    /** The Manual preset, or {@link #CUSTOM}. */
    static int preset(Context context) {
        int preset = prefs(context).getInt("eq_preset", 0);
        return preset >= 0 && preset < PRESETS.length ? preset : CUSTOM;
    }

    static void setPreset(Context context, int preset) {
        prefs(context).edit().putInt("eq_preset", preset).apply();
        apply(context);
    }

    /** The Manual gains in dB: the preset's, or your own. */
    static float[] bands(Context context) {
        int preset = preset(context);
        if (preset != CUSTOM) {
            return PRESETS[preset].clone();
        }
        float[] gains = new float[FREQUENCIES.length];
        String[] stored = prefs(context).getString("eq_bands", "").split(",");
        for (int i = 0; i < Math.min(gains.length, stored.length); i++) {
            try {
                gains[i] = clampDb(Float.parseFloat(stored[i]));
            } catch (NumberFormatException ignored) {
                // a missing band starts flat
            }
        }
        return gains;
    }

    /** Moving a band leaves the preset behind: the curve becomes your own. */
    static void setBands(Context context, float[] gains) {
        StringBuilder stored = new StringBuilder();
        for (float gain : gains) {
            if (stored.length() > 0) {
                stored.append(',');
            }
            stored.append(String.format(Locale.US, "%.1f", clampDb(gain)));
        }
        prefs(context).edit().putInt("eq_preset", CUSTOM).putString("eq_bands", stored.toString()).apply();
        apply(context);
    }

    /** -1 (left only) .. 0 (centre) .. 1 (right only). */
    static float balance(Context context) {
        return Math.max(-1f, Math.min(1f, prefs(context).getFloat("eq_balance", 0f)));
    }

    static void setBalance(Context context, float balance) {
        prefs(context).edit().putFloat("eq_balance", Math.max(-1f, Math.min(1f, balance))).apply();
        apply(context);
    }

    // ------------------------------------------------------------------------------ the curve

    /**
     * Dynamic's gains in dB: a tilt about the middle of the spectrum ({@code x}, warm to bright) and a
     * bump round 1.3 kHz ({@code y}, scooped to forward). Focused bends the tilt so only the ends move
     * and narrows the bump.
     */
    static float[] toneGains(int x, int y, boolean focused) {
        float[] gains = new float[FREQUENCIES.length];
        double low = Math.log(FREQUENCIES[0]);
        double high = Math.log(FREQUENCIES[FREQUENCIES.length - 1]);
        double sigma = focused ? 0.28 : 0.6;
        for (int i = 0; i < gains.length; i++) {
            double u = 2 * (Math.log(FREQUENCIES[i]) - low) / (high - low) - 1; // -1 .. 1 across the bands
            double tilt = focused ? u * u * u : u;
            double bump = Math.exp(-(u - 0.1) * (u - 0.1) / (2 * sigma * sigma));
            gains[i] = (float) (x * DB_PER_STEP * tilt + y * DB_PER_STEP * bump);
        }
        return gains;
    }

    private static void apply(Context context) {
        if (!isEnabled(context)) {
            sCurve = null;
            return;
        }
        boolean manual = mode(context) == MANUAL;
        float[] gains = manual ? bands(context) : toneGains(toneX(context), toneY(context), isFocused(context));
        sCurve = new Curve(gains, !manual && isFocused(context), balance(context));
    }

    private static int clampStep(int step) {
        return Math.max(-TONE_STEPS, Math.min(TONE_STEPS, step));
    }

    private static float clampDb(float db) {
        return Math.max(-MAX_DB, Math.min(MAX_DB, Math.round(db * 2) / 2f));
    }

    /** Shelves at the ends, peaks between; the preamp takes the loudest boost back so nothing clips. */
    private static final class Curve {
        final double[] gainDb;
        final boolean focused;
        final double preamp;
        final double left;
        final double right;

        Curve(float[] gains, boolean focused, float balance) {
            gainDb = new double[gains.length];
            double loudest = 0;
            for (int i = 0; i < gains.length; i++) {
                gainDb[i] = gains[i];
                loudest = Math.max(loudest, gains[i]);
            }
            this.focused = focused;
            preamp = Math.pow(10, -loudest / 20);
            left = balance > 0 ? 1 - balance : 1;
            right = balance < 0 ? 1 + balance : 1;
        }

        /** Normalized biquads {b0, b1, b2, a1, a2} per band (RBJ Audio EQ Cookbook). */
        double[][] coefficients(int sampleRate) {
            int n = gainDb.length;
            double[][] result = new double[n][];
            int count = 0;
            for (int i = 0; i < n; i++) {
                double fc = FREQUENCIES[i];
                if (fc >= sampleRate / 2.0) {
                    continue;
                }
                double q = i == 0 || i == n - 1 ? 0.7 : focused ? 1.6 : 1.0;
                double a = Math.pow(10, gainDb[i] / 40);
                double w0 = 2 * Math.PI * fc / sampleRate;
                double cos = Math.cos(w0);
                double alpha = Math.sin(w0) / (2 * q);
                double sq = 2 * Math.sqrt(a) * alpha;
                double b0, b1, b2, a0, a1, a2;
                if (i == 0) { // low shelf
                    b0 = a * ((a + 1) - (a - 1) * cos + sq);
                    b1 = 2 * a * ((a - 1) - (a + 1) * cos);
                    b2 = a * ((a + 1) - (a - 1) * cos - sq);
                    a0 = (a + 1) + (a - 1) * cos + sq;
                    a1 = -2 * ((a - 1) + (a + 1) * cos);
                    a2 = (a + 1) + (a - 1) * cos - sq;
                } else if (i == n - 1) { // high shelf
                    b0 = a * ((a + 1) + (a - 1) * cos + sq);
                    b1 = -2 * a * ((a - 1) + (a + 1) * cos);
                    b2 = a * ((a + 1) + (a - 1) * cos - sq);
                    a0 = (a + 1) - (a - 1) * cos + sq;
                    a1 = 2 * ((a - 1) - (a + 1) * cos);
                    a2 = (a + 1) - (a - 1) * cos - sq;
                } else { // peak
                    b0 = 1 + alpha * a;
                    b1 = -2 * cos;
                    b2 = 1 - alpha * a;
                    a0 = 1 + alpha / a;
                    a1 = -2 * cos;
                    a2 = 1 - alpha / a;
                }
                result[count++] = new double[] {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
            }
            return java.util.Arrays.copyOf(result, count);
        }
    }

    /**
     * The bands on 16-bit or float PCM: always active for those, a plain copy while off or while a
     * MeeTube video plays, so switching needs no new audio track. A change of curve keeps the filters'
     * memory (same band count), so dragging on the pad doesn't click.
     */
    private static final class Processor extends BaseAudioProcessor {
        @Nullable private Curve mApplied;
        private boolean mPrepared;
        private double[][] mCoefficients = new double[0][];
        /** Per channel, per filter: x1, x2, y1, y2. */
        private double[][] mState = new double[0][];
        /** Per channel: the preamp times the balance. */
        private double[] mGain = new double[0];

        @Override
        protected AudioFormat onConfigure(AudioFormat format) {
            if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
                return AudioFormat.NOT_SET; // inactive: passes through untouched
            }
            return format;
        }

        @Override
        protected void onFlush() {
            mPrepared = false;
        }

        @Override
        protected void onReset() {
            mPrepared = false;
        }

        @Override
        public void queueInput(ByteBuffer input) {
            int size = input.remaining();
            if (size == 0) {
                return;
            }
            ByteBuffer output = replaceOutputBuffer(size);
            Curve curve = sPlaying ? sCurve : null;
            if (curve == null) {
                mApplied = null;
                output.put(input);
                output.flip();
                return;
            }
            if (!mPrepared || curve != mApplied) {
                prepare(curve);
            }
            boolean isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
            int channels = inputAudioFormat.channelCount;
            int channel = 0;
            while (input.hasRemaining()) {
                double sample = isFloat ? input.getFloat() : input.getShort() / 32768.0;
                sample = filter(mState[channel], sample) * mGain[channel];
                if (isFloat) {
                    output.putFloat((float) sample);
                } else {
                    long pcm = Math.round(sample * 32768.0);
                    output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm)));
                }
                channel = channel + 1 == channels ? 0 : channel + 1;
            }
            output.flip();
        }

        private double filter(double[] state, double x) {
            for (int i = 0; i < mCoefficients.length; i++) {
                double[] c = mCoefficients[i];
                int s = i * 4;
                double y = c[0] * x + c[1] * state[s] + c[2] * state[s + 1] - c[3] * state[s + 2] - c[4] * state[s + 3];
                state[s + 1] = state[s];
                state[s] = x;
                state[s + 3] = state[s + 2];
                state[s + 2] = y;
                x = y;
            }
            return x;
        }

        private void prepare(Curve curve) {
            boolean keepState = mPrepared && mApplied != null;
            mApplied = curve;
            mPrepared = true;
            int channels = Math.max(1, inputAudioFormat.channelCount);
            mCoefficients = curve.coefficients(inputAudioFormat.sampleRate);
            mGain = new double[channels];
            for (int c = 0; c < channels; c++) {
                // Balance only on stereo: channel 0 is left, 1 is right.
                mGain[c] = curve.preamp * (channels != 2 ? 1 : c == 0 ? curve.left : curve.right);
            }
            if (!keepState || mState.length != channels || mState[0].length != mCoefficients.length * 4) {
                mState = new double[channels][mCoefficients.length * 4];
            }
        }
    }
}
