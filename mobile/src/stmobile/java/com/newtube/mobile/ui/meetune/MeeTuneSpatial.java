package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * MEETUNE(issue #262): Spatial sound for MeeTune songs on headphones, in software so it works on any
 * phone. All on stereo: the stereo image is widened above ~200 Hz (the bass stays in the middle), a
 * head model (MIT KEMAR, {@link MeeTuneHrtf}) plays it as two speakers in front of you rather than
 * inside your head, and a few early reflections that differ per ear give it a room. Cinema adds a
 * deeper bass. Loudness is kept level with the dry sound and peaks are softly held, and changes glide
 * in over ~50 ms so switching never clicks. Light by default; Off, or a MeeTube video: an untouched copy.
 *
 * <p>Head tracking (Android 13+): when it is on and the phone's own spatial audio would take this
 * song, the song is handed over as 5.1 (the room in the back channels) and Android renders it for the
 * ears and follows the head, so the head model is skipped. Decided when a song's audio starts.
 */
public final class MeeTuneSpatial {
    static final int OFF = 0;
    static final int LIGHT = 1;
    static final int STRONG = 2;
    static final int CINEMA = 3;
    private static final float[] AMOUNTS = {0f, 0.5f, 1f, 1.3f};
    private static final float[] BASS = {0f, 0f, 0f, 1f};

    /** The wanted amount, 0..1.3, and bass, 0..1, read on the audio thread. */
    private static volatile float sAmount = -1f;
    private static volatile float sBass;
    private static volatile boolean sHeadTracking;
    private static Context sContext;

    private MeeTuneSpatial() {
    }

    public static AudioProcessor processor(Context context) {
        if (sAmount < 0f) {
            int level = level(context);
            sAmount = AMOUNTS[level];
            sBass = BASS[level];
            sHeadTracking = headTracking(context);
        }
        sContext = context.getApplicationContext();
        return new Processor();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("meetune", Context.MODE_PRIVATE);
    }

    static int level(Context context) {
        int level = prefs(context).getInt("spatial", LIGHT);
        return level >= OFF && level <= CINEMA ? level : LIGHT;
    }

    static void setLevel(Context context, int level) {
        prefs(context).edit().putInt("spatial", level).apply();
        sBass = BASS[level];
        setAmount(AMOUNTS[level]);
    }

    static void setAmount(float amount) {
        sAmount = amount;
    }

    static void setBass(float bass) {
        sBass = bass;
    }

    static boolean headTracking(Context context) {
        return prefs(context).getBoolean("head_tracking", false);
    }

    static void setHeadTracking(Context context, boolean on) {
        prefs(context).edit().putBoolean("head_tracking", on).apply();
        sHeadTracking = on;
    }

    /** The phone's spatial audio is on and would take 5.1 at this rate on the current output. */
    static boolean systemSpatializes(Context context, int rate) {
        if (Build.VERSION.SDK_INT < 33 || context == null) {
            return false;
        }
        try {
            android.media.Spatializer spatializer =
                    ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).getSpatializer();
            return spatializer.getImmersiveAudioLevel() != android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
                    && spatializer.isAvailable() && spatializer.isEnabled()
                    && spatializer.canBeSpatialized(new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                            new android.media.AudioFormat.Builder()
                                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_5POINT1)
                                    .setSampleRate(rate).build());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A processor with no settings read (tests). */
    static AudioProcessor processor() {
        return new Processor();
    }

    private static final class Processor extends BaseAudioProcessor {
        private static final float[] TAPS_L_MS = {8.3f, 14.9f, 22.1f, 33.7f};
        private static final float[] TAPS_R_MS = {10.1f, 17.3f, 26.9f, 38.9f};
        private static final float[] TAP_GAINS = {0.30f, 0.22f, 0.16f, 0.11f};
        /** Cinema's bass: +5 dB below ~110 Hz. */
        private static final float BASS_GAIN = 0.78f;

        private boolean mFloat;
        /** Hand Android 5.1 for its spatial audio (head tracking) instead of rendering for the ears here. */
        private boolean mSurround;
        private boolean mPendingSurround;
        private float mWet;
        private float mBassWet;
        private float mGlide;
        private float mBassCoef;
        private float mLowCoef;
        private float mRoomCoef;
        private float mSideLow;
        private float mBassLow;
        private float mRoomLow;
        /** The head model, and its mid and side inputs, each written twice so a window never wraps. */
        private float[] mMidTaps = MeeTuneHrtf.MID_48000;
        private float[] mSideTaps = MeeTuneHrtf.SIDE_48000;
        private float[] mMidRing = new float[2];
        private float[] mSideRing = new float[2];
        private int mRingPos;
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
            // ponytail: decided per song; turning head tracking or earbuds on mid-song applies from the next one.
            mPendingSurround = sHeadTracking && MeeTuneEq.isPlaying()
                    && systemSpatializes(sContext, format.sampleRate);
            return mPendingSurround ? new AudioFormat(format.sampleRate, 6, format.encoding) : format;
        }

        @Override
        protected void onFlush() {
            int rate = inputAudioFormat.sampleRate;
            mFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
            mSurround = mPendingSurround; // the new format is in force from here
            mGlide = 1f - (float) Math.exp(-1.0 / (0.05 * rate));
            mBassCoef = onePole(rate, 200);
            mLowCoef = onePole(rate, 110);
            mRoomCoef = onePole(rate, 4000);
            mMidTaps = rate == 44100 ? MeeTuneHrtf.MID_44100 : MeeTuneHrtf.MID_48000;
            mSideTaps = rate == 44100 ? MeeTuneHrtf.SIDE_44100 : MeeTuneHrtf.SIDE_48000;
            mMidRing = new float[2 * mMidTaps.length];
            mSideRing = new float[2 * mSideTaps.length];
            mRingPos = 0;
            mTapsL = samples(TAPS_L_MS, rate);
            mTapsR = samples(TAPS_R_MS, rate);
            mHistory = new float[samples(TAPS_R_MS, rate)[TAPS_R_MS.length - 1] + 1];
            mHistoryPos = 0;
            mSideLow = mBassLow = mRoomLow = 0f;
        }

        @Override
        protected void onReset() {
            mWet = 0f;
            mBassWet = 0f;
            mSurround = mPendingSurround = false;
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
            int frameBytes = mFloat ? 8 : 4;
            ByteBuffer output = replaceOutputBuffer(mSurround ? size / frameBytes * frameBytes * 3 : size);
            boolean music = MeeTuneEq.isPlaying();
            float goal = music ? Math.max(0f, sAmount) : 0f;
            float bassGoal = music ? sBass : 0f;
            if (goal == 0f && mWet < 1e-4f && bassGoal == 0f && mBassWet < 1e-4f) {
                mWet = 0f;
                mBassWet = 0f;
                if (!mSurround) {
                    output.put(input);
                } else {
                    while (input.remaining() >= frameBytes) { // the song as front left and right
                        float l = read(input);
                        float r = read(input);
                        put(output, l);
                        put(output, r);
                        put(output, 0f);
                        put(output, 0f);
                        put(output, 0f);
                        put(output, 0f);
                    }
                }
                output.flip();
                return;
            }
            int taps = mMidTaps.length;
            while (input.remaining() >= frameBytes) {
                mWet += (goal - mWet) * mGlide;
                mBassWet += (bassGoal - mBassWet) * mGlide;
                float w = mWet;
                float l = read(input);
                float r = read(input);

                // Wider above the bass.
                float mid = (l + r) * 0.5f;
                float side = (l - r) * 0.5f;
                mSideLow = side + (mSideLow - side) * mBassCoef;
                float wide = mSideLow + (side - mSideLow) * (1f + 0.9f * w);

                // The head model: two speakers at +-30 degrees, on mid and side.
                mMidRing[mRingPos] = mMidRing[mRingPos + taps] = mid;
                mSideRing[mRingPos] = mSideRing[mRingPos + taps] = wide;
                float head = mSurround ? 0f : Math.min(1f, 2f * w);
                if (head > 0f) {
                    float hm = 0f;
                    float hs = 0f;
                    for (int k = 0, i = mRingPos + taps; k < taps; k++, i--) {
                        hm += mMidTaps[k] * mMidRing[i];
                        hs += mSideTaps[k] * mSideRing[i];
                    }
                    mid += (hm - mid) * head;
                    wide += (hs - wide) * head;
                }
                mRingPos = mRingPos + 1 == taps ? 0 : mRingPos + 1;

                // Cinema: a deeper bass, in the middle.
                mBassLow = mid + (mBassLow - mid) * mLowCoef;
                mid += mBassLow * BASS_GAIN * mBassWet;

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

                float level = 1f / (1f + 0.3f * w + 0.3f * mBassWet);
                if (mSurround) { // a centre for the voice, the room behind
                    put(output, soft((mid * 0.65f + wide) * level));
                    put(output, soft((mid * 0.65f - wide) * level));
                    put(output, soft(mid * 0.5f * level));
                    put(output, 0f);
                    put(output, soft(roomL * 0.9f * w * level));
                    put(output, soft(roomR * 0.9f * w * level));
                } else {
                    put(output, soft((mid + wide + roomL * 0.6f * w) * level));
                    put(output, soft((mid - wide + roomR * 0.6f * w) * level));
                }
            }
            output.flip();
        }

        private float read(ByteBuffer input) {
            return mFloat ? input.getFloat() : input.getShort() / 32768f;
        }

        private void put(ByteBuffer output, float sample) {
            if (mFloat) {
                output.putFloat(sample);
            } else {
                output.putShort((short) Math.round(sample * 32767f));
            }
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
