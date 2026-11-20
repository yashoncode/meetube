package com.newtube.mobile.ui.meetune;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class KaraokeEngineTest {
    private static final int RATE = 48_000;
    private static final int CHUNK_OUTPUT = 254_976;
    private static final int CHUNK_INPUT = CHUNK_OUTPUT + KaraokeEngine.TRIM;
    private final AtomicReference<Throwable> unexpected = new AtomicReference<>();
    private KaraokeEngine engine;

    @After
    public void tearDown() {
        if (engine != null) {
            engine.release();
        }
        assertNull(unexpected.get());
    }

    @Test
    public void fftMatchesDirectDft() {
        int n = KaraokeEngine.NFFT;
        float[] input = randomSamples(n);
        float[] real = input.clone();
        float[] imag = new float[n];
        KaraokeEngine.Fft fft = new KaraokeEngine.Fft();
        fft.transform(real, imag, false);

        double[] cos = new double[n];
        double[] sin = new double[n];
        for (int i = 0; i < n; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / n);
            sin[i] = Math.sin(2 * Math.PI * i / n);
        }
        double maxError = 0;
        for (int bin = 0; bin < n; bin++) {
            double re = 0;
            double im = 0;
            int phase = 0;
            for (float sample : input) {
                re += sample * cos[phase];
                im -= sample * sin[phase];
                phase += bin;
                if (phase >= n) {
                    phase -= n;
                }
            }
            maxError = Math.max(maxError, Math.hypot(real[bin] - re, imag[bin] - im));
        }
        assertTrue("Maximum DFT error " + maxError, maxError < 1e-3);
        fft.transform(real, imag, true);
        assertArrayEquals(input, real, 1e-5f);
        assertArrayEquals(new float[n], imag, 1e-5f);
    }

    @Test
    public void zeroVocalsPreserveTenSecondsInOddBlocks() throws Exception {
        List<Integer> chunks = new ArrayList<>();
        engine = new KaraokeEngine((input, t) -> {
            chunks.add(t);
            assertEquals(4 * 2048 * t, input.length);
            return new float[input.length];
        }, RATE, 0, unexpected::set);
        float[] input = randomSamples(2 * 10 * RATE);
        float[] block = new float[2 * 1931];
        for (int offset = 0; offset < input.length; offset += block.length) {
            int samples = Math.min(block.length, input.length - offset);
            System.arraycopy(input, offset, block, 0, samples);
            engine.write(block, samples / 2);
        }
        assertEquals(input.length / 2, engine.bufferedInputFrames());
        engine.endOfStream();
        awaitInstrumental(input.length / 2);
        assertArrayEquals(input, drain(input.length / 2), 1e-4f);
        assertEquals(Arrays.asList(256, 256), chunks);
    }

    @Test
    public void identityVocalsCancelInBandStereoAcrossChunkBoundary() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        engine = new KaraokeEngine((input, t) -> {
            assertEquals(256, t);
            if (calls.getAndIncrement() == 0) {
                // Frame six starts at song frame zero after both layers of left padding.
                int plane = 2048 * t;
                int leftBin = 37 * t + 6;
                int rightBin = 91 * t + 6;
                assertEquals(0.5 * 6144 / 4, input[leftBin], 1e-3);
                assertEquals(0, input[plane + leftBin], 1e-3);
                assertEquals(0, input[2 * plane + rightBin], 1e-3);
                assertEquals(-0.25 * 6144 / 4, input[3 * plane + rightBin], 1e-3);
            }
            return input;
        }, RATE, 0, unexpected::set);
        // The model omits the top 1025 real FFT bins. Use two tones inside its passband.
        int frames = CHUNK_OUTPUT + RATE;
        float[] input = new float[2 * frames];
        for (int i = 0; i < frames; i++) {
            double phase = 2 * Math.PI * i / 6144;
            input[2 * i] = (float) (0.5 * Math.cos(37 * phase));
            input[2 * i + 1] = (float) (0.25 * Math.sin(91 * phase));
        }
        engine.write(input, frames);
        engine.endOfStream();
        awaitInstrumental(frames);
        float[] out = drain(frames);
        // Zero padding truncates the tones in the first and last FFT windows.
        for (int i = KaraokeEngine.NFFT; i < frames - KaraokeEngine.NFFT; i++) {
            assertEquals(0f, out[2 * i], 1e-4f);
            assertEquals(0f, out[2 * i + 1], 1e-4f);
        }
        assertEquals(2, calls.get());
    }

    @Test
    public void rawReadPicksUpAtPlaybackCursorIncludingUnprocessedInput() throws Exception {
        float[] input = randomSamples(2 * CHUNK_INPUT);
        engine = new KaraokeEngine((spectrum, t) -> {
            // The last analysis frame reflects around the final sample, excluding the endpoint.
            double dc = 0;
            for (int i = 0; i < 6144; i++) {
                int sample = i < 3072 ? CHUNK_INPUT - 3072 + i : CHUNK_INPUT - 2 - (i - 3072);
                dc += input[2 * sample] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / 6144));
            }
            assertEquals(dc, spectrum[t - 1], 1e-3);
            return spectrum;
        }, RATE, 0, unexpected::set);
        engine.write(input, CHUNK_INPUT);
        awaitInstrumental(CHUNK_OUTPUT);
        float[] first = new float[2 * 17];
        assertEquals(17, engine.read(first, 17));
        assertEquals(CHUNK_OUTPUT - 17, engine.readyFrames());
        // One full chunk was ready before EOS. The right context has no instrumental yet.
        assertFalse(engine.isDrained());
        float[] rest = new float[input.length - first.length];
        assertEquals(rest.length / 2, engine.readRaw(rest, rest.length / 2));
        assertArrayEquals(Arrays.copyOfRange(input, first.length, input.length), rest, 0f);
        engine.endOfStream();
        assertTrue(engine.isDrained());
    }

    @Test
    public void failureReportsOnceAndPassesCurrentAndFutureInput() throws Exception {
        Exception failure = new Exception("inference failed");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch notified = new CountDownLatch(1);
        engine = new KaraokeEngine((input, t) -> {
            calls.incrementAndGet();
            throw failure;
        }, RATE, 0, error -> {
            reported.set(error);
            errors.incrementAndGet();
            notified.countDown();
        });
        float[] input = randomSamples(2 * CHUNK_INPUT);
        engine.write(input, CHUNK_INPUT);
        assertTrue(notified.await(5, TimeUnit.SECONDS));
        float[] output = new float[input.length];
        assertEquals(CHUNK_INPUT, engine.read(output, CHUNK_INPUT));
        assertArrayEquals(input, output, 0f);
        engine.write(input, CHUNK_INPUT);
        assertArrayEquals(input, drain(CHUNK_INPUT), 0f);
        assertEquals(1, calls.get());
        assertEquals(1, errors.get());
        assertSame(failure, reported.get());
    }

    @Test
    public void resetDuringInferenceDropsOldResultAndRestartsAtFrameZero() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        List<Integer> chunks = new ArrayList<>();
        engine = new KaraokeEngine((input, t) -> {
            chunks.add(t);
            if (chunks.size() == 1) {
                started.countDown();
                assertTrue(finish.await(3, TimeUnit.SECONDS));
            }
            input[0] = 0;
            return input;
        }, RATE, 0, unexpected::set, new TaggedTransform());
        engine.write(new float[2 * CHUNK_INPUT], CHUNK_INPUT);
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            engine.reset();
            assertEquals(0, engine.bufferedInputFrames());
            assertEquals(0, engine.readyFrames());
        } finally {
            finish.countDown();
        }
        float[] fresh = new float[2 * 8192];
        Arrays.fill(fresh, 0.5f);
        engine.write(fresh, fresh.length / 2);
        engine.endOfStream();
        awaitInstrumental(8192);
        float[] output = drain(fresh.length / 2);
        for (int i = 0; i < output.length; i += 2) {
            assertEquals(0f, output[i], 0f);
            assertEquals(0.5f, output[i + 1], 0f);
        }
        assertEquals(Arrays.asList(256, 256), chunks);
    }

    @Test
    public void slowInferencePlaysInOrderAndResumesOneSecondAheadOfPlayback() throws Exception {
        int missedStart = CHUNK_OUTPUT;
        int lateFinish = missedStart + CHUNK_OUTPUT + RATE / 3 + 37;
        int resume = lateFinish + RATE;
        int frames = resume + CHUNK_OUTPUT + 123;
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch simulatedDeadline = new CountDownLatch(1);
        CountDownLatch tailStarted = new CountDownLatch(1);
        CountDownLatch finishTail = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        engine = new KaraokeEngine((spectrum, t) -> {
            assertEquals(256, t);
            int call = calls.incrementAndGet();
            if (call == 2) {
                secondStarted.countDown();
                // Sleep until the simulated playback clock has passed this entire chunk.
                assertTrue(simulatedDeadline.await(5, TimeUnit.SECONDS));
            } else if (call == 4) {
                tailStarted.countDown();
                assertTrue(finishTail.await(5, TimeUnit.SECONDS));
            }
            spectrum[0] = 0;
            return spectrum;
        }, RATE, 0, unexpected::set, new TaggedTransform());
        float[] input = new float[2 * frames];
        for (int i = 0; i < frames; i++) {
            input[2 * i] = 0.5f;
            input[2 * i + 1] = i / 2_097_152f;
        }
        engine.write(input, frames);
        float[] block = new float[2 * (RATE / 100)];
        try {
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            int clock = 0;
            while (clock < frames) {
                if (clock == lateFinish) {
                    engine.endOfStream();
                    simulatedDeadline.countDown();
                    assertTrue(tailStarted.await(5, TimeUnit.SECONDS));
                    // A future chunk exists, but there is still a gap at the read cursor.
                    assertEquals(0, engine.readyFrames());
                }
                int until = clock < lateFinish ? lateFinish : frames;
                int count = Math.min(block.length / 2, until - clock);
                assertEquals(frames - clock, engine.bufferedInputFrames());
                assertEquals(count, engine.read(block, count));
                for (int i = 0; i < count; i++) {
                    int pos = clock + i;
                    int wet = CHUNK_OUTPUT - pos - 1;
                    if (pos >= resume) {
                        wet = Math.min(pos - resume, resume + CHUNK_OUTPUT - pos - 1);
                    }
                    float dry = 1f - Math.max(0, Math.min(1023, wet)) / 1023f;
                    assertEquals(0.5f * dry, block[2 * i], 1e-6f);
                    assertEquals(input[2 * pos + 1], block[2 * i + 1], 0f);
                }
                clock += count;
            }
            assertTrue(engine.isDrained());
            assertEquals(0, engine.read(block, 1));
            assertEquals(4, calls.get());
        } finally {
            simulatedDeadline.countDown();
            finishTail.countDown();
        }
    }

    private void awaitInstrumental(int frames) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.readyFrames() != frames) {
            assertNull(unexpected.get());
            assertTrue("Timed out waiting for the worker", System.nanoTime() < deadline);
            Thread.sleep(1);
        }
    }

    private float[] drain(int frames) {
        engine.endOfStream();
        float[] result = new float[2 * frames];
        float[] block = new float[2 * 4093];
        int emitted = 0;
        while (!engine.isDrained()) {
            int count = engine.read(block, block.length / 2);
            assertTrue("Buffered input must produce audio", count > 0);
            System.arraycopy(block, 0, result, 2 * emitted, 2 * count);
            emitted += count;
        }
        assertEquals(frames, emitted);
        assertEquals(0, engine.bufferedInputFrames());
        return result;
    }

    private static float[] randomSamples(int length) {
        Random random = new Random(281);
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = 2 * random.nextFloat() - 1;
        }
        return samples;
    }

    // Carry the right-channel frame tag through the real worker and model without running FFTs.
    static final class TaggedTransform extends KaraokeEngine.ChunkTransform {
        private static final int trim = KaraokeEngine.TRIM;

        TaggedTransform() {
            super(256);
        }

        @Override
        void analyze(long stream, int count) {
            input[0] = segment[2 * trim + 1];
        }

        @Override
        void synthesize(float[] vocals, int count, long stream) {
            for (int i = 0; i < count; i++) {
                instrumental[2 * i] = vocals[0];
                instrumental[2 * i + 1] = segment[2 * (trim + i) + 1];
            }
        }
    }
}
