package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEETUBE(autoeq): headphone correction from AutoEq (github.com/jaakkopasanen/AutoEq, MIT) - its
 * ParametricEQ.txt for one headphone model (a preamp and ~10 peaking / shelf filters) run as
 * biquads inside the player's audio sink. Exact, unlike the device {@code Equalizer}'s 5 fixed
 * bands, and the same on every phone. Independent of the equalizer switch: a chosen headphone is on.
 * Audio offload is disabled player-wide (Media3PlayerInitializer), so the sink always gets PCM.
 */
public final class HeadphoneEq {
    private static final String PREFS = "meetube_audio";
    private static final String KEY_NAME = "autoeq_name";
    private static final String KEY_PROFILE = "autoeq_profile";

    /** The live profile; null = off. Read on the audio thread, swapped from the UI. */
    @Nullable
    private static volatile Profile sProfile;
    private static boolean sLoaded;

    private HeadphoneEq() {
    }

    /** A processor for one audio sink; it follows {@link #set} / {@link #clear} live. */
    public static AudioProcessor processor(Context context) {
        load(context);
        return new Processor();
    }

    /** The chosen headphone, or "" for none. */
    public static String name(Context context) {
        return prefs(context).getString(KEY_NAME, "");
    }

    /** Saves and applies a ParametricEQ.txt; false (nothing changed) when it holds no filter. */
    public static boolean set(Context context, String name, String parametricEq) {
        Profile profile = Profile.parse(parametricEq);
        if (profile == null) {
            return false;
        }
        prefs(context).edit().putString(KEY_NAME, name).putString(KEY_PROFILE, parametricEq).apply();
        sLoaded = true;
        sProfile = profile;
        return true;
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_NAME).remove(KEY_PROFILE).apply();
        sLoaded = true;
        sProfile = null;
    }

    private static synchronized void load(Context context) {
        if (!sLoaded) {
            sLoaded = true;
            String text = prefs(context).getString(KEY_PROFILE, null);
            sProfile = text != null ? Profile.parse(text) : null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** AutoEq's ParametricEQ.txt: "Preamp: -6.1 dB", "Filter 1: ON PK Fc 105 Hz Gain 6.4 dB Q 0.70". */
    static final class Profile {
        private static final Pattern PREAMP = Pattern.compile("Preamp:\\s*(-?[\\d.]+)\\s*dB");
        private static final Pattern FILTER = Pattern.compile(
                "Filter\\s*\\d+:\\s*ON\\s+(PK|LSC|HSC|LS|HS)\\s+Fc\\s+([\\d.]+)\\s*Hz\\s+Gain\\s+(-?[\\d.]+)\\s*dB\\s+Q\\s+([\\d.]+)");

        final double preampDb;
        final String[] types;
        final double[] fc;
        final double[] gainDb;
        final double[] q;

        private Profile(double preampDb, List<String> types, List<double[]> values) {
            this.preampDb = preampDb;
            this.types = types.toArray(new String[0]);
            fc = new double[values.size()];
            gainDb = new double[values.size()];
            q = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                fc[i] = values.get(i)[0];
                gainDb[i] = values.get(i)[1];
                q[i] = values.get(i)[2];
            }
        }

        @Nullable
        static Profile parse(String text) {
            Matcher preamp = PREAMP.matcher(text);
            double preampDb = preamp.find() ? Double.parseDouble(preamp.group(1)) : 0;
            List<String> types = new ArrayList<>();
            List<double[]> values = new ArrayList<>();
            Matcher filter = FILTER.matcher(text);
            while (filter.find()) {
                double q = Double.parseDouble(filter.group(4));
                if (q <= 0) {
                    continue;
                }
                types.add(filter.group(1).toUpperCase(Locale.US));
                values.add(new double[] {Double.parseDouble(filter.group(2)), Double.parseDouble(filter.group(3)), q});
            }
            return values.isEmpty() ? null : new Profile(preampDb, types, values);
        }

        /**
         * Normalized biquad coefficients {b0, b1, b2, a1, a2} per filter at {@code sampleRate}
         * (RBJ Audio EQ Cookbook, as AutoEq designs them); filters at or past Nyquist are dropped.
         */
        double[][] coefficients(int sampleRate) {
            List<double[]> result = new ArrayList<>();
            for (int i = 0; i < fc.length; i++) {
                if (fc[i] <= 0 || fc[i] >= sampleRate / 2.0) {
                    continue;
                }
                double a = Math.pow(10, gainDb[i] / 40);
                double w0 = 2 * Math.PI * fc[i] / sampleRate;
                double cos = Math.cos(w0);
                double alpha = Math.sin(w0) / (2 * q[i]);
                double sq = 2 * Math.sqrt(a) * alpha;
                double b0, b1, b2, a0, a1, a2;
                switch (types[i]) {
                    case "LSC":
                    case "LS":
                        b0 = a * ((a + 1) - (a - 1) * cos + sq);
                        b1 = 2 * a * ((a - 1) - (a + 1) * cos);
                        b2 = a * ((a + 1) - (a - 1) * cos - sq);
                        a0 = (a + 1) + (a - 1) * cos + sq;
                        a1 = -2 * ((a - 1) + (a + 1) * cos);
                        a2 = (a + 1) + (a - 1) * cos - sq;
                        break;
                    case "HSC":
                    case "HS":
                        b0 = a * ((a + 1) + (a - 1) * cos + sq);
                        b1 = -2 * a * ((a - 1) + (a + 1) * cos);
                        b2 = a * ((a + 1) + (a - 1) * cos - sq);
                        a0 = (a + 1) - (a - 1) * cos + sq;
                        a1 = 2 * ((a - 1) - (a + 1) * cos);
                        a2 = (a + 1) - (a - 1) * cos - sq;
                        break;
                    default: // PK
                        b0 = 1 + alpha * a;
                        b1 = -2 * cos;
                        b2 = 1 - alpha * a;
                        a0 = 1 + alpha / a;
                        a1 = -2 * cos;
                        a2 = 1 - alpha / a;
                        break;
                }
                result.add(new double[] {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0});
            }
            return result.toArray(new double[0][]);
        }
    }

    /**
     * The filter chain on 16-bit or float PCM, in place of nothing: always active for those, a plain
     * copy while no headphone is chosen, so choosing one needs no new audio track. A profile change
     * is picked up at the next buffer, with the filters' memory cleared.
     */
    static final class Processor extends BaseAudioProcessor {
        @Nullable private Profile mApplied;
        private boolean mPrepared;
        private double[][] mCoefficients = new double[0][];
        /** Per channel, per filter: x1, x2, y1, y2. */
        private double[][] mState = new double[0][];
        private double mGain = 1;

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
            Profile profile = sProfile;
            if (!mPrepared || profile != mApplied) {
                prepare(profile);
            }
            if (profile == null || mCoefficients.length == 0 && mGain == 1) {
                output.put(input);
                output.flip();
                return;
            }
            boolean isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
            int channels = inputAudioFormat.channelCount;
            int channel = 0;
            while (input.hasRemaining()) {
                double sample = isFloat ? input.getFloat() : input.getShort() / 32768.0;
                sample = filter(mState[channel], sample) * mGain;
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

        private void prepare(@Nullable Profile profile) {
            mApplied = profile;
            mPrepared = true;
            mCoefficients = profile != null ? profile.coefficients(inputAudioFormat.sampleRate) : new double[0][];
            mGain = profile != null ? Math.pow(10, profile.preampDb / 20) : 1;
            mState = new double[Math.max(1, inputAudioFormat.channelCount)][mCoefficients.length * 4];
        }
    }
}
