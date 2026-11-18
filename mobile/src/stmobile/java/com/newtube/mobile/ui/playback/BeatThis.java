package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * MEETUNE(automix): beats and downbeats with Beat This! (Foscarin, Schlüter, Widmer, ISMIR 2024; code and
 * weights MIT, github.com/CPJKU/beat_this), the final0 checkpoint exported to ONNX with its MatMuls
 * quantized to int8 (assets/beat_this_final0_q8.onnx). A neural tracker, because an autocorrelation reads
 * a tempo but not which beat is the bar's first, and weak or busy endings gave it no grid at all.
 *
 * <p>The front end is the model's own: mono at 22.05 kHz, a 1024 point STFT every 441 samples (50 frames a
 * second, centred, reflect padded, periodic Hann, divided by sqrt(1024)), magnitudes through 128 Slaney mel
 * triangles from 30 Hz to 11 kHz (unit peak), then log1p(1000 x). It runs in chunks of 1500 frames, dropping
 * 6 frames at each inner border; beats are the frames whose logit is positive and the highest within 3
 * frames either side. Blocking: call it off the main thread.
 */
final class BeatThis {
    private static final String TAG = "BeatThis";
    private static final String MODEL = "beat_this_final0_q8.onnx";
    static final int RATE = 22_050;
    private static final int FFT = 1024;
    private static final int HOP = 441;
    private static final int MELS = 128;
    static final double FRAME_MS = 1000.0 * HOP / RATE; // 20 ms
    private static final int CHUNK = 1500;
    private static final int BORDER = 6;

    // ponytail: the session is kept for the process (its arena is off, so it holds the graph, ~25 MB);
    // close it on trim memory if MeeTune ever gets killed for it in the background.
    @Nullable private static OrtSession sSession;
    private static boolean sFailed;
    private static float[] sWindow;
    private static int[] sMelFrom;
    private static float[][] sMelWeights;

    private BeatThis() {
    }

    /** Beat and downbeat times in ms after the first of {@code n} mono samples at {@code rate}; null on failure. */
    @Nullable
    static synchronized double[][] track(Context context, float[] samples, int n, int rate) {
        OrtSession session = session(context);
        if (session == null) return null;
        float[] mel = mel(resample(samples, n, rate));
        int frames = mel.length / MELS;
        if (frames < 4 * BORDER) return null;
        float[] beat = new float[frames];
        float[] down = new float[frames];
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            String input = session.getInputNames().iterator().next();
            for (int start = 0; ; start += CHUNK - 2 * BORDER) {
                int length = Math.min(CHUNK, frames - start);
                if (start > 0 && length <= 2 * BORDER) break; // no usable middle; its last frames stay 0
                float[] chunk = new float[length * MELS];
                System.arraycopy(mel, start * MELS, chunk, 0, chunk.length);
                try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), new long[] {1, length, MELS});
                     OrtSession.Result result = session.run(Collections.singletonMap(input, tensor))) {
                    float[] b = ((float[][]) result.get(0).getValue())[0];
                    float[] d = ((float[][]) result.get(1).getValue())[0];
                    boolean last = start + length >= frames;
                    int from = start == 0 ? 0 : BORDER;
                    int to = last ? length : length - BORDER;
                    System.arraycopy(b, from, beat, start + from, to - from);
                    System.arraycopy(d, from, down, start + from, to - from);
                    if (last) break;
                }
            }
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "inference failed", e);
            return null;
        }
        return new double[][] {peaks(beat), peaks(down)};
    }

    @Nullable
    private static OrtSession session(Context context) {
        if (sSession != null || sFailed) return sSession;
        try (InputStream in = context.getApplicationContext().getAssets().open(MODEL)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(24 << 20);
            byte[] buffer = new byte[1 << 16];
            for (int r; (r = in.read(buffer)) > 0; ) bytes.write(buffer, 0, r);
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(2);
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            sSession = OrtEnvironment.getEnvironment().createSession(bytes.toByteArray(), options);
        } catch (Exception | LinkageError e) {
            sFailed = true; // no model, no Automix grids: the plain blend
            Log.w(TAG, "model unavailable", e);
        }
        return sSession;
    }

    /** Frames (in ms) whose logit is positive and the highest within 3 frames either side; ties merged. */
    static double[] peaks(float[] logits) {
        List<Double> peaks = new ArrayList<>();
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] <= 0) continue;
            boolean max = true;
            for (int j = Math.max(0, i - 3); j <= Math.min(logits.length - 1, i + 3) && max; j++) {
                max = logits[j] <= logits[i];
            }
            if (!max) continue;
            int last = peaks.size() - 1;
            if (last >= 0 && i - peaks.get(last) <= 1) peaks.set(last, (peaks.get(last) + i) / 2);
            else peaks.add((double) i);
        }
        double[] ms = new double[peaks.size()];
        for (int i = 0; i < ms.length; i++) ms[i] = peaks.get(i) * FRAME_MS;
        return ms;
    }

    /** Mono at {@link #RATE}: a windowed sinc low pass at 95% of the lower Nyquist, read from a table. */
    static float[] resample(float[] x, int n, int rate) {
        if (rate == RATE) return java.util.Arrays.copyOf(x, n);
        double step = (double) rate / RATE; // input samples per output sample
        double cutoff = 0.95 * Math.min(1, 1 / step); // of the input's Nyquist
        int zeros = 12;
        double half = zeros / cutoff; // the kernel's half width, in input samples
        int res = 128;
        float[] table = new float[(int) Math.ceil(half * res) + 2];
        for (int i = 0; i < table.length; i++) {
            double d = (double) i / res;
            double s = d == 0 ? 1 : Math.sin(Math.PI * cutoff * d) / (Math.PI * cutoff * d);
            double w = d >= half ? 0 : 0.5 + 0.5 * Math.cos(Math.PI * d / half);
            table[i] = (float) (cutoff * s * w);
        }
        int out = (int) Math.round(n / step);
        float[] y = new float[out];
        for (int m = 0; m < out; m++) {
            double t = m * step;
            int from = Math.max(0, (int) Math.ceil(t - half));
            int to = Math.min(n - 1, (int) Math.floor(t + half));
            double sum = 0;
            for (int i = from; i <= to; i++) {
                double p = Math.abs(t - i) * res;
                int k = (int) p;
                double f = p - k;
                sum += x[i] * (table[k] + f * (table[k + 1] - table[k]));
            }
            y[m] = (float) sum;
        }
        return y;
    }

    /** The model's log-mel input, frame after frame of {@link #MELS} values. */
    static float[] mel(float[] x) {
        prepare();
        int n = x.length;
        if (n <= FFT / 2) return new float[0];
        int frames = n / HOP + 1;
        float[] out = new float[frames * MELS];
        double[] re = new double[FFT];
        double[] im = new double[FFT];
        double[] mag = new double[FFT / 2 + 1];
        double norm = Math.sqrt(FFT);
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < FFT; i++) {
                int j = f * HOP + i - FFT / 2; // centred, reflect padded
                if (j < 0) j = -j;
                if (j >= n) j = 2 * (n - 1) - j;
                re[i] = j >= 0 && j < n ? x[j] * sWindow[i] : 0;
                im[i] = 0;
            }
            fft(re, im);
            for (int k = 0; k < mag.length; k++) mag[k] = Math.hypot(re[k], im[k]) / norm;
            for (int m = 0; m < MELS; m++) {
                double e = 0;
                float[] w = sMelWeights[m];
                for (int k = 0; k < w.length; k++) e += w[k] * mag[sMelFrom[m] + k];
                out[f * MELS + m] = (float) Math.log1p(1000 * e);
            }
        }
        return out;
    }

    private static void prepare() {
        if (sWindow != null) return;
        float[] window = new float[FFT];
        for (int i = 0; i < FFT; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / FFT)); // periodic
        // torchaudio melscale_fbanks(mel_scale="slaney", norm=None): triangles between mel-spaced points.
        int bins = FFT / 2 + 1;
        double lo = hzToMel(30);
        double hi = hzToMel(11_000);
        double[] pts = new double[MELS + 2];
        for (int i = 0; i < pts.length; i++) pts[i] = melToHz(lo + (hi - lo) * i / (MELS + 1));
        int[] from = new int[MELS];
        float[][] weights = new float[MELS][];
        for (int m = 0; m < MELS; m++) {
            float[] row = new float[bins];
            int first = -1;
            int last = -1;
            for (int k = 0; k < bins; k++) {
                double hz = (double) k * RATE / FFT;
                double down = (hz - pts[m]) / (pts[m + 1] - pts[m]);
                double up = (pts[m + 2] - hz) / (pts[m + 2] - pts[m + 1]);
                row[k] = (float) Math.max(0, Math.min(down, up));
                if (row[k] > 0) {
                    if (first < 0) first = k;
                    last = k;
                }
            }
            if (first < 0) first = last = 0;
            from[m] = first;
            weights[m] = java.util.Arrays.copyOfRange(row, first, last + 1);
        }
        sMelFrom = from;
        sMelWeights = weights;
        sWindow = window;
    }

    private static double hzToMel(double hz) {
        return hz < 1000 ? hz / (200.0 / 3) : 15 + Math.log(hz / 1000) / (Math.log(6.4) / 27);
    }

    private static double melToHz(double mel) {
        return mel < 15 ? mel * (200.0 / 3) : 1000 * Math.exp((Math.log(6.4) / 27) * (mel - 15));
    }

    /** In place, radix 2. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wr = Math.cos(angle);
            double wi = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double next = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }
}
