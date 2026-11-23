package com.newtube.mobile.ui.meetune;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Streaming vocal separation in stereo sample frames. Playback starts
 * with vocals removed and the worker stays ahead. If it falls behind, the original audio
 * covers the gap while the worker prepares a later chunk.
 */
public final class KaraokeEngine {
    static final int NFFT = 6144;
    static final int HOP = 1024;
    static final int DIMF = 2048;
    static final int TRIM = NFFT / 2;
    static final int FADE_FRAMES = 1024;
    private static final int T = 256;
    private static final int CHUNK = HOP * (T - 1);
    // ponytail: Native-rate separation works for 48 kHz Opus. Resampling to the 44.1 kHz training rate is the upgrade path.

    private final KaraokeModel model;
    private final Thread worker;
    private final CompletableFuture<Void> workerStopped = new CompletableFuture<>();
    final int sampleRate;
    private final int leadFrames;
    // mix retains played frames as left context. ready can start ahead of playback
    // during the lead-in or after a missed deadline. Its end is the next chunk's start.
    private StereoQueue mix = new StereoQueue();
    private final StereoQueue ready = new StereoQueue();
    private long readPosition;
    private long separationStart;
    private boolean rescheduling;
    private int wetLevel; // Weight for the next frame, from 0 (raw) to FADE_FRAMES - 1.
    private volatile long generation;
    private boolean ended;
    private boolean released;
    private boolean failed;
    private boolean drainingRaw;
    private volatile long lastRunFrames; // How long the last chunk took to separate, in playback frames.

    public KaraokeEngine(KaraokeModel model, int sampleRate, int leadFrames, Consumer<Throwable> onError) {
        this(model, sampleRate, leadFrames, onError, null);
    }

    KaraokeEngine(KaraokeModel model, int sampleRate, int leadFrames, Consumer<Throwable> onError,
                  ChunkTransform transform) {
        if (sampleRate <= 0 || leadFrames < 0) {
            throw new IllegalArgumentException("Invalid sample rate or lead-in");
        }
        this.model = model;
        this.sampleRate = sampleRate;
        this.leadFrames = leadFrames;
        ready.clear(leadFrames);
        worker = new Thread(() -> {
            try {
                work(transform, onError);
            } finally {
                workerStopped.complete(null);
            }
        }, "KaraokeEngine");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void write(float[] interleavedStereo, int frames) {
        if (ended || released) {
            throw new IllegalStateException("Stream is closed");
        }
        checkFrames(interleavedStereo, frames);
        mix.append(interleavedStereo, frames);
        notifyAll();
    }

    public synchronized int bufferedInputFrames() {
        return (int) (mix.end - readPosition);
    }

    /** Consecutive instrumental frames available at the current playback position. */
    synchronized int readyFrames() {
        return ready.start == readPosition ? ready.size() : 0;
    }

    synchronized boolean hasFailed() {
        return failed;
    }

    public synchronized int read(float[] outInterleaved, int maxFrames) {
        return readFrames(outInterleaved, maxFrames, !failed && !drainingRaw);
    }

    public synchronized int readRaw(float[] outInterleaved, int maxFrames) {
        return readFrames(outInterleaved, maxFrames, false);
    }

    private int readFrames(float[] out, int maxFrames, boolean separating) {
        checkFrames(out, maxFrames);
        int count = Math.min(maxFrames, bufferedInputFrames());
        if (count == 0) {
            return 0;
        }
        long end = readPosition + count;
        mix.copy(readPosition, out, 0, count);
        if (separating || (drainingRaw && wetLevel > 0)) {
            if (separating && readPosition == separationStart && leadFrames == 0) {
                wetLevel = FADE_FRAMES - 1;
            }
            long wetEnd = Math.min(end, ready.end);
            for (long pos = Math.max(readPosition, ready.start); pos < wetEnd; pos++) {
                // If the next chunk is still missing, fade over the aligned samples we have.
                if (!ended || ready.end < mix.end) {
                    wetLevel = (int) Math.min(wetLevel, ready.end - 1 - pos);
                }
                int i = 2 * (int) (pos - readPosition);
                float wet = wetLevel / (float) (FADE_FRAMES - 1);
                for (int ch = 0; ch < 2; ch++) {
                    float instrumental = ready.sample(pos, ch);
                    out[i + ch] = wetLevel == FADE_FRAMES - 1 ? instrumental
                            : out[i + ch] + wet * (instrumental - out[i + ch]);
                }
                if (separating) {
                    wetLevel = Math.min(FADE_FRAMES - 1, wetLevel + 1);
                } else if (--wetLevel <= 0) {
                    wetLevel = 0;
                    break;
                }
            }
        } else {
            wetLevel = 0;
        }
        readPosition = end;
        mix.discardBefore(Math.max(0, end - TRIM));
        if (end > ready.end && !failed && !drainingRaw) {
            wetLevel = 0;
            ready.clear(end + retryLead());
            rescheduling = true;
            generation++;
            notifyAll();
        } else if (end > ready.start) {
            ready.discardBefore(end);
        }
        return count;
    }

    public synchronized void endOfStream() {
        ended = true;
        notifyAll();
    }

    synchronized void beginRawDrain() {
        drainingRaw = true;
        generation++;
    }

    public synchronized boolean isDrained() {
        return ended && bufferedInputFrames() == 0;
    }

    public synchronized void reset() {
        mix.clear(0);
        readPosition = 0;
        ended = false;
        restartAtReadPosition();
    }

    synchronized void restartAtReadPosition() {
        generation++;
        separationStart = readPosition;
        ready.clear(readPosition + leadFrames);
        rescheduling = false;
        wetLevel = 0;
        drainingRaw = false;
        notifyAll();
    }

    /** Re-enabling on a different model carries the original PCM into a fresh, empty engine. */
    synchronized void moveUnreadInputTo(KaraokeEngine replacement) {
        synchronized (replacement) {
            beginRawDrain();
            StereoQueue empty = replacement.mix;
            replacement.mix = mix;
            // Keep the available left context. Rebase the ring's frame numbers without moving samples.
            replacement.readPosition = readPosition - mix.start;
            mix.end -= mix.start;
            mix.start = 0;
            mix = empty;
            readPosition = 0;
            replacement.ended = ended;
            replacement.restartAtReadPosition();
        }
    }

    public void release() {
        release(null);
    }

    void release(Runnable afterWorkerStops) {
        synchronized (this) {
            if (released) return;
            released = true;
            reset();
            worker.interrupt();
        }
        // Native inference can outlive the interrupt. Its model is safe to retire once work returns.
        if (afterWorkerStops != null) workerStopped.thenRun(afterWorkerStops);
    }

    private static void checkFrames(float[] samples, int frames) {
        if (frames < 0 || frames > samples.length / 2) {
            throw new IllegalArgumentException("Invalid stereo frame count " + frames);
        }
    }

    private long nextChunkStart() {
        // A stale inference may still have been running while playback continued in raw.
        // Pick the retry's actual start when the worker can take it.
        return rescheduling ? Math.max(ready.end, readPosition + retryLead()) : ready.end;
    }

    /**
     * MEETUBE(issue #314): a retry one second ahead never landed on a phone that needs longer than that
     * per chunk, so after one miss every chunk was thrown away: vocals came back and the CPU ran hot for
     * nothing. Start the retry twice the last chunk's time ahead (1 to 10 s; the input buffer holds 20).
     */
    private long retryLead() {
        return Math.max(sampleRate, Math.min(10L * sampleRate, 2 * lastRunFrames));
    }

    private boolean chunkAvailable() {
        long remaining = mix.end - nextChunkStart();
        return ended ? remaining > 0 : remaining >= CHUNK - TRIM;
    }

    long prepareInputFrames() {
        return Math.max(CHUNK - TRIM, 20L * sampleRate);
    }

    private void work(ChunkTransform transform, Consumer<Throwable> onError) {
        ChunkTransform dsp = transform;
        if (dsp == null) dsp = new Separator();
        while (true) {
            int count;
            long start;
            long stream;
            synchronized (this) {
                while (!released && (drainingRaw || !chunkAvailable())) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        // release sets the flag before interrupting.
                    }
                }
                if (released) {
                    return;
                }
                stream = generation;
                start = nextChunkStart();
                if (rescheduling) {
                    ready.clear(start);
                    rescheduling = false;
                }
                count = (int) Math.min(CHUNK - 2 * TRIM, mix.end - start);
                long left = start - TRIM;
                long from = Math.max(0, left);
                int prefix = (int) (from - left);
                int real = (int) (Math.min(left + CHUNK, mix.end) - from);
                Arrays.fill(dsp.segment, 0, 2 * prefix, 0f);
                mix.copy(from, dsp.segment, prefix, real);
                Arrays.fill(dsp.segment, 2 * (prefix + real), 2 * CHUNK, 0f);
            }

            long began = System.nanoTime();
            try {
                dsp.analyze(stream, count);
                if (stream != generation) {
                    continue;
                }
                float[] vocals = model.run(dsp.input, dsp.frames);
                if (vocals == null || vocals.length != dsp.input.length) {
                    throw new IllegalArgumentException("Expected " + dsp.input.length + " vocal spectrum values");
                }
                dsp.synthesize(vocals, count, stream);
                lastRunFrames = (System.nanoTime() - began) * sampleRate / 1_000_000_000L;
            } catch (Throwable error) {
                synchronized (this) {
                    if (released) return;
                    failed = true;
                    ready.clear(readPosition);
                    wetLevel = 0;
                }
                onError.accept(error);
                return;
            }
            synchronized (this) {
                // A seek or a raw read past this chunk's start invalidates its result.
                if (stream == generation && start == ready.end) {
                    ready.append(dsp.instrumental, count);
                    notifyAll();
                }
            }
        }
    }

    /** Scratch belongs exclusively to the worker, including while a reset invalidates a chunk. */
    abstract static class ChunkTransform {
        final float[] segment;
        final float[] instrumental;
        final float[] input;
        final int frames;

        ChunkTransform() {
            this(4 * DIMF * T);
        }

        /** Tests pass a small input to tag frames without running FFTs. */
        ChunkTransform(int inputValues) {
            segment = new float[2 * CHUNK];
            instrumental = new float[2 * (CHUNK - 2 * TRIM)];
            input = new float[inputValues];
            frames = T;
        }

        abstract void analyze(long stream, int count);
        abstract void synthesize(float[] vocals, int count, long stream);
    }

    private final class Separator extends ChunkTransform {
        final float[] window = new float[NFFT];
        final float[] norm = new float[HOP];
        final float[] real = new float[NFFT];
        final float[] imag = new float[NFFT];
        final Fft fft = new Fft();

        Separator() {
            for (int i = 0; i < NFFT; i++) {
                window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / NFFT));
                // Every retained sample has all six overlapping windows, repeating each hop.
                norm[i % HOP] += window[i] * window[i];
            }
        }

        @Override
        void analyze(long stream, int count) {
            for (int frame = 0; frame < T; frame++) {
                if (stream != generation) {
                    return;
                }
                for (int ch = 0; ch < 2; ch++) {
                    stftFrame(frame, ch);
                }
            }
        }

        @Override
        void synthesize(float[] vocals, int count, long stream) {
            Arrays.fill(instrumental, 0, 2 * count, 0f);
            for (int frame = 0; frame < T; frame++) {
                if (stream != generation) {
                    return;
                }
                // The output excludes the iSTFT centre padding and TRIM frames at each end.
                int start = frame * HOP - 2 * TRIM;
                int first = Math.max(0, -start);
                int limit = Math.min(NFFT, count - start);
                if (first >= limit) {
                    continue;
                }
                for (int ch = 0; ch < 2; ch++) {
                    inverseVocalFrame(vocals, frame, ch);
                    for (int i = first; i < limit; i++) {
                        instrumental[2 * (start + i) + ch] += real[i] * window[i];
                    }
                }
            }
            for (int i = 0; i < count; i++) {
                for (int ch = 0; ch < 2; ch++) {
                    int pos = 2 * i + ch;
                    instrumental[pos] = segment[2 * TRIM + pos] - instrumental[pos] / norm[i % HOP];
                }
            }
        }

        private void stftFrame(int frame, int ch) {
            int start = frame * HOP - TRIM;
            for (int i = 0; i < NFFT; i++) {
                int pos = start + i;
                if (pos < 0) {
                    pos = -pos;
                } else if (pos >= CHUNK) {
                    pos = 2 * CHUNK - 2 - pos;
                }
                real[i] = segment[2 * pos + ch] * window[i];
            }
            Arrays.fill(imag, 0f);
            fft.transform(real, imag, false);
            int index = 2 * ch * DIMF * T + frame;
            for (int bin = 0; bin < DIMF; bin++, index += T) {
                input[index] = real[bin];
                input[index + DIMF * T] = imag[bin];
            }
        }

        private void inverseVocalFrame(float[] vocals, int frame, int ch) {
            int index = 2 * ch * DIMF * T + frame;
            real[0] = vocals[index];
            imag[0] = 0f;
            for (int bin = 1; bin < DIMF; bin++) {
                index += T;
                real[bin] = real[NFFT - bin] = vocals[index];
                imag[bin] = vocals[index + DIMF * T];
                imag[NFFT - bin] = -imag[bin];
            }
            Arrays.fill(real, DIMF, NFFT - DIMF + 1, 0f);
            Arrays.fill(imag, DIMF, NFFT - DIMF + 1, 0f);
            fft.transform(real, imag, true);
        }
    }

    /** In-place radix-2 butterflies on bit-reversed input, one group per transform. */
    private static void butterflies(float[] r, float[] im, float[] cos, float[] sin, int group, int sign) {
        int n = r.length;
        for (int size = 2; size <= group; size *= 2) {
            int half = size / 2;
            int stride = n / size;
            for (int start = 0; start < n; start += size) {
                for (int j = 0; j < half; j++) {
                    int a = start + j;
                    int b = a + half;
                    float wr = cos[j * stride];
                    float wi = sign * sin[j * stride];
                    float br = wr * r[b] - wi * im[b];
                    float bi = wr * im[b] + wi * r[b];
                    r[b] = r[a] - br;
                    im[b] = im[a] - bi;
                    r[a] += br;
                    im[a] += bi;
                }
            }
        }
    }

    /** Three decimated radix-2 transforms followed by a radix-3 butterfly. */
    static final class Fft {
        private static final int M = NFFT / 3;
        private static final float ROOT = (float) (Math.sqrt(3) / 2);
        private static final float[] COS = new float[NFFT];
        private static final float[] SIN = new float[NFFT];
        private static final int[] REVERSE = new int[M];
        static {
            for (int i = 0; i < NFFT; i++) {
                COS[i] = (float) Math.cos(2 * Math.PI * i / NFFT);
                SIN[i] = (float) Math.sin(2 * Math.PI * i / NFFT);
            }
            for (int i = 0; i < M; i++) {
                REVERSE[i] = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(M));
            }
        }
        private final float[] r = new float[NFFT];
        private final float[] im = new float[NFFT];

        void transform(float[] real, float[] imag, boolean inverse) {
            for (int group = 0; group < 3; group++) {
                for (int i = 0; i < M; i++) {
                    int dest = group * M + REVERSE[i];
                    r[dest] = real[3 * i + group];
                    im[dest] = imag[3 * i + group];
                }
            }
            int sign = inverse ? 1 : -1;
            butterflies(r, im, COS, SIN, M, sign);
            float scale = inverse ? 1f / NFFT : 1f;
            for (int k = 0; k < M; k++) {
                int b = k + M;
                int c = b + M;
                float br = COS[k] * r[b] - sign * SIN[k] * im[b];
                float bi = COS[k] * im[b] + sign * SIN[k] * r[b];
                float cr = COS[2 * k] * r[c] - sign * SIN[2 * k] * im[c];
                float ci = COS[2 * k] * im[c] + sign * SIN[2 * k] * r[c];
                real[k] = scale * (r[k] + (br + cr));
                imag[k] = scale * (im[k] + (bi + ci));
                float baseR = r[k] - 0.5f * (br + cr);
                float baseI = im[k] - 0.5f * (bi + ci);
                float deltaR = sign * ROOT * (bi - ci);
                float deltaI = sign * ROOT * (br - cr);
                real[b] = scale * (baseR - deltaR);
                imag[b] = scale * (baseI + deltaI);
                real[c] = scale * (baseR + deltaR);
                imag[c] = scale * (baseI - deltaI);
            }
        }
    }

    /** Growable stereo ring addressed in stream frames. All access is under the engine lock. */
    private static final class StereoQueue {
        long start;
        long end;
        private int head;
        private float[] data = new float[8192];

        int size() {
            return (int) (end - start);
        }

        void clear(long position) {
            head = 0;
            start = end = position;
        }

        void discardBefore(long position) {
            if (position >= end) {
                clear(position);
            } else {
                head = index(position);
                start = position;
            }
        }

        float sample(long frame, int channel) {
            return data[index(frame) + channel];
        }

        private int index(long frame) {
            return (head + 2 * (int) (frame - start)) % data.length;
        }

        void copy(long from, float[] dest, int destFrame, int frames) {
            int pos = index(from);
            int first = Math.min(frames * 2, data.length - pos);
            System.arraycopy(data, pos, dest, 2 * destFrame, first);
            System.arraycopy(data, 0, dest, 2 * destFrame + first, 2 * frames - first);
        }

        void append(float[] source, int frames) {
            int needed = 2 * (size() + frames);
            if (needed > data.length) {
                float[] larger = new float[Math.max(needed, 2 * data.length)];
                copy(start, larger, 0, size());
                head = 0;
                data = larger;
            }
            int pos = index(end);
            int first = Math.min(2 * frames, data.length - pos);
            System.arraycopy(source, 0, data, pos, first);
            System.arraycopy(source, first, data, 0, 2 * frames - first);
            end += frames;
        }
    }
}
