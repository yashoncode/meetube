package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * MEETUNE(issue #262): Spatial sound for MeeTune songs on headphones, in software so it works on any
 * phone. Three parts, all on stereo: the stereo image is widened above ~200 Hz (the bass stays in the
 * middle), each ear hears a little of the other side late and dulled as from speakers (crossfeed, so
 * the sound sits in front of you rather than inside your head), and a few early reflections that
 * differ per ear give it a room. Loudness is kept level with the dry sound and peaks are softly held,
 * and changes glide in over ~50 ms so switching never clicks. Light by default; Off, or a MeeTube video: an untouched copy.
 */
public final class MeeTuneSpatial {
    static final int OFF = 0;
    static final int LIGHT = 1;
    static final int STRONG = 2;
    private static final float[] AMOUNTS = {0f, 0.5f, 1f};

    /** The wanted amount, 0..1, read on the audio thread. */
    private static volatile float sAmount = -1f;

    private MeeTuneSpatial() {
    }

    public static AudioProcessor processor(Context context) {
        if (sAmount < 0f) {
            sAmount = AMOUNTS[level(context)];
        }
        return new Processor();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("meetune", Context.MODE_PRIVATE);
    }

    static int level(Context context) {
        int level = prefs(context).getInt("spatial", LIGHT);
        return level >= OFF && level <= STRONG ? level : LIGHT;
    }

    static void setLevel(Context context, int level) {
        prefs(context).edit().putInt("spatial", level).apply();
        setAmount(AMOUNTS[level]);
    }

    static void setAmount(float amount) {
        sAmount = amount;
    }

    /** A processor with no settings read (tests). */
    static AudioProcessor processor() {
        return new Processor();
    }

    private static final class Processor extends BaseAudioProcessor {
        private static final float[] TAPS_L_MS = {8.3f, 14.9f, 22.1f, 33.7f};
        private static final float[] TAPS_R_MS = {10.1f, 17.3f, 26.9f, 38.9f};
        private static final float[] TAP_GAINS = {0.30f, 0.22f, 0.16f, 0.11f};

        private boolean mFloat;
        private float mWet;
        private float mGlide;
        private float mBassCoef;
        private float mCrossCoef;
        private float mRoomCoef;
        private float mSideLow;
        private float mCrossL;
        private float mCrossR;
        private float mRoomLow;
        /** Crossfeed delay line: the low-passed left and right, interleaved. */
        private float[] mCross = new float[2];
        private int mCrossPos;
        /** The room's mono feed, long enough for the latest tap. */
        private float[] mHistory = new float[1];
        private int mHistoryPos;
        private int[] mTapsL = new int[0];
        private int[] mTapsR = new int[0];

        @Override
        protected AudioFormat onConfigure(AudioFormat format) {
            if (format.channelCount != 2
                    || format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
                return AudioFormat.NOT_SET; // inactive: passes through untouched
            }
            return format;
        }

        @Override
        protected void onFlush() {
            int rate = inputAudioFormat.sampleRate;
            mFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
            mGlide = 1f - (float) Math.exp(-1.0 / (0.05 * rate));
            mBassCoef = onePole(rate, 200);
            mCrossCoef = onePole(rate, 700);
            mRoomCoef = onePole(rate, 4000);
            mCross = new float[2 * Math.max(1, Math.round(rate * 0.0003f))];
            mCrossPos = 0;
            mTapsL = samples(TAPS_L_MS, rate);
            mTapsR = samples(TAPS_R_MS, rate);
            mHistory = new float[samples(TAPS_R_MS, rate)[TAPS_R_MS.length - 1] + 1];
            mHistoryPos = 0;
            mSideLow = mCrossL = mCrossR = mRoomLow = 0f;
        }

        @Override
        protected void onReset() {
            mWet = 0f;
        }

        private static float onePole(int rate, double hz) {
            return (float) Math.exp(-2.0 * Math.PI * hz / rate);
        }

        private static int[] samples(float[] ms, int rate) {
            int[] out = new int[ms.length];
            for (int i = 0; i < ms.length; i++) {
                out[i] = Math.round(ms[i] / 1000f * rate);
            }
            return out;
        }

        @Override
        public void queueInput(ByteBuffer input) {
            int size = input.remaining();
            if (size == 0) {
                return;
            }
            ByteBuffer output = replaceOutputBuffer(size);
            float goal = MeeTuneEq.isPlaying() ? Math.max(0f, sAmount) : 0f;
            if (goal == 0f && mWet < 1e-4f) {
                mWet = 0f;
                output.put(input);
                output.flip();
                return;
            }
            while (input.remaining() >= (mFloat ? 8 : 4)) {
                mWet += (goal - mWet) * mGlide;
                float w = mWet;
                float l = mFloat ? input.getFloat() : input.getShort() / 32768f;
                float r = mFloat ? input.getFloat() : input.getShort() / 32768f;

                // Wider above the bass.
                float mid = (l + r) * 0.5f;
                float side = (l - r) * 0.5f;
                mSideLow = side + (mSideLow - side) * mBassCoef;
                float wide = mSideLow + (side - mSideLow) * (1f + 0.9f * w);
                float outL = mid + wide;
                float outR = mid - wide;

                // Crossfeed: the other ear's sound, dulled and ~0.3 ms late.
                mCrossL = l + (mCrossL - l) * mCrossCoef;
                mCrossR = r + (mCrossR - r) * mCrossCoef;
                float lateL = mCross[mCrossPos];
                float lateR = mCross[mCrossPos + 1];
                mCross[mCrossPos] = mCrossL;
                mCross[mCrossPos + 1] = mCrossR;
                mCrossPos = mCrossPos + 2 == mCross.length ? 0 : mCrossPos + 2;
                outL += lateR * 0.2f * w;
                outR += lateL * 0.2f * w;

                // A small room: different early reflections in each ear.
                mRoomLow = mid + (mRoomLow - mid) * mRoomCoef;
                mHistory[mHistoryPos] = mRoomLow;
                float roomL = 0f;
                float roomR = 0f;
                for (int t = 0; t < TAP_GAINS.length; t++) {
                    roomL += mHistory[wrap(mHistoryPos - mTapsL[t])] * TAP_GAINS[t];
                    roomR += mHistory[wrap(mHistoryPos - mTapsR[t])] * TAP_GAINS[t];
                }
                mHistoryPos = mHistoryPos + 1 == mHistory.length ? 0 : mHistoryPos + 1;
                outL += roomL * 0.6f * w;
                outR += roomR * 0.6f * w;

                float level = 1f / (1f + 0.3f * w);
                outL = soft(outL * level);
                outR = soft(outR * level);
                if (mFloat) {
                    output.putFloat(outL);
                    output.putFloat(outR);
                } else {
                    output.putShort((short) Math.round(outL * 32767f));
                    output.putShort((short) Math.round(outR * 32767f));
                }
            }
            output.flip();
        }

        private int wrap(int index) {
            return index < 0 ? index + mHistory.length : index;
        }

        /** Untouched up to 0.9, then bent so it never passes 1. */
        static float soft(float x) {
            float a = Math.abs(x);
            if (a <= 0.9f) {
                return x;
            }
            float over = a - 0.9f;
            float y = 0.9f + over / (1f + over * 10f);
            return x < 0 ? -y : y;
        }
    }
}
