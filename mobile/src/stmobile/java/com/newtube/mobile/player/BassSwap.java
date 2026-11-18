package com.newtube.mobile.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * MEETUNE(automix): a DJ mixer's bass knob for one player. {@link #setLowGain} 1 plays the sound as it is;
 * 0 cuts what is under ~200 Hz (kick and bass line: a 24 dB/octave high-pass, about -40 dB at 60 Hz), so two
 * songs' bass never plays at once while Automix blends them. Each audio sink the player's renderers factory
 * builds gets its own {@link #processor}, all following the one knob; a plain copy while it is at 1.
 * {@link #setHighGain} is its treble knob the same way (a one pole split at ~3 kHz), which Automix crosses
 * over the whole blend and sweeps down on a song it can't beat-match.
 */
public final class BassSwap {
    private static final double CUTOFF_HZ = 200;
    private static final double TREBLE_HZ = 3000;

    private volatile float mTarget = 1f;
    private volatile long mRampMs;
    private volatile float mHighTarget = 1f;
    private volatile long mHighRampMs;

    /** The low end's level, 1 (as is) to 0 (cut), reached over {@code rampMs}. Any thread. */
    public void setLowGain(float gain, long rampMs) {
        mRampMs = Math.max(0, rampMs);
        mTarget = Math.max(0f, Math.min(1f, gain));
    }

    /** The treble's level, 1 (as is) to 0 (cut above ~3 kHz), reached over {@code rampMs}. Any thread. */
    public void setHighGain(float gain, long rampMs) {
        mHighRampMs = Math.max(0, rampMs);
        mHighTarget = Math.max(0f, Math.min(1f, gain));
    }

    AudioProcessor processor() {
        return new Processor();
    }

    private final class Processor extends BaseAudioProcessor {
        private float mGain = 1f;
        private float mHigh = 1f;
        private double mTrebleAlpha;
        /** Per channel: the treble split's low pass. */
        private double[] mLow = new double[0];
        /** Two Butterworth high-pass sections share b0 b1 b2 a1 a2. */
        private double[] mCoefficients = new double[5];
        /** Per channel: both sections' x1, x2, y1, y2. */
        private double[] mState = new double[0];

        @Override
        protected AudioFormat onConfigure(AudioFormat format) throws AudioProcessor.UnhandledAudioFormatException {
            if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
                return AudioFormat.NOT_SET; // inactive: passes through untouched
            }
            double w = Math.tan(Math.PI * CUTOFF_HZ / format.sampleRate);
            double q = Math.sqrt(0.5);
            double norm = 1 / (1 + w / q + w * w);
            mCoefficients = new double[] {norm, -2 * norm, norm, 2 * (w * w - 1) * norm, (1 - w / q + w * w) * norm};
            mState = new double[format.channelCount * 8];
            mLow = new double[format.channelCount];
            mTrebleAlpha = 1 - Math.exp(-2 * Math.PI * TREBLE_HZ / format.sampleRate);
            return format;
        }

        @Override
        protected void onFlush() {
            Arrays.fill(mState, 0);
            Arrays.fill(mLow, 0);
        }

        @Override
        protected void onReset() {
            mGain = 1f;
            mHigh = 1f;
            Arrays.fill(mState, 0);
            Arrays.fill(mLow, 0);
        }

        @Override
        public void queueInput(ByteBuffer input) {
            int size = input.remaining();
            if (size == 0) return;
            ByteBuffer output = replaceOutputBuffer(size);
            float target = mTarget;
            float highTarget = mHighTarget;
            if (mGain == 1f && target == 1f && mHigh == 1f && highTarget == 1f) {
                Arrays.fill(mState, 0); // the next cut starts from rest
                Arrays.fill(mLow, 0);
                output.put(input);
                output.flip();
                return;
            }
            boolean isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
            int channels = inputAudioFormat.channelCount;
            long rampMs = mRampMs;
            float step = rampMs == 0 ? 1f : 1000f / (rampMs * inputAudioFormat.sampleRate);
            long highRampMs = mHighRampMs;
            float highStep = highRampMs == 0 ? 1f : 1000f / (highRampMs * inputAudioFormat.sampleRate);
            double[] c = mCoefficients;
            int channel = 0;
            while (input.hasRemaining()) {
                if (channel == 0 && mGain != target) {
                    mGain = mGain < target ? Math.min(target, mGain + step) : Math.max(target, mGain - step);
                }
                if (channel == 0 && mHigh != highTarget) {
                    mHigh = mHigh < highTarget ? Math.min(highTarget, mHigh + highStep) : Math.max(highTarget, mHigh - highStep);
                }
                double x = isFloat ? input.getFloat() : input.getShort() / 32768.0;
                double high = x;
                for (int section = 0, s = channel * 8; section < 2; section++, s += 4) {
                    double y = c[0] * high + c[1] * mState[s] + c[2] * mState[s + 1]
                            - c[3] * mState[s + 2] - c[4] * mState[s + 3];
                    mState[s + 1] = mState[s];
                    mState[s] = high;
                    mState[s + 3] = mState[s + 2];
                    mState[s + 2] = y;
                    high = y;
                }
                double sample = mGain * x + (1 - mGain) * high;
                double low = mLow[channel] += mTrebleAlpha * (sample - mLow[channel]);
                sample = low + mHigh * (sample - low);
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
    }
}
