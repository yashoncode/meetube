package com.newtube.mobile.ui.meetune;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import org.junit.After;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MeeTuneKaraokeTest {
    private static final int RATE = 48_000;
    private final AtomicInteger calls = new AtomicInteger();
    private final KaraokeModel removeLeft = (input, t) -> {
        calls.incrementAndGet();
        input[0] = 0f;
        return input;
    };
    private AudioProcessor processor;
    private KaraokeEngine engine;

    @After
    public void tearDown() {
        if (processor != null) {
            processor.reset();
        }
        MeeTuneKaraoke.setOn(false);
        MeeTuneKaraoke.removeModel();
        MeeTuneEq.setPlaying(false);
    }

    @Test
    public void offCopiesEveryByte() throws Exception {
        MeeTuneEq.setPlaying(true);
        loadSelected(removeLeft, () -> {});
        MeeTuneKaraoke.setOn(false);
        assertPassthrough();
    }

    @Test
    public void nonMusicCopiesEveryByte() throws Exception {
        MeeTuneEq.setPlaying(false);
        loadSelected(removeLeft, () -> {});
        MeeTuneKaraoke.setOn(true);
        assertPassthrough();
    }

    @Test
    public void missingModelCopiesEveryByte() throws Exception {
        MeeTuneEq.setPlaying(true);
        MeeTuneKaraoke.setOn(true);
        assertFalse(MeeTuneKaraoke.isLoaded());
        assertPassthrough();
    }

    @Test
    public void shortPcm16SongWaitsForAllItsInstrumentalAtEos() throws Exception {
        assertShortSong(C.ENCODING_PCM_16BIT);
    }

    @Test
    public void shortFloatSongWaitsForAllItsInstrumentalAtEos() throws Exception {
        assertShortSong(C.ENCODING_PCM_FLOAT);
    }

    @Test
    public void eosStartsWhenTwoSecondsAreReadyEvenWithMoreAudioToSeparate() throws Exception {
        Semaphore finish = new Semaphore(0);
        enable((input, t) -> {
            finish.acquire();
            return removeLeft.run(input, t);
        });
        // At 192 kHz the first T=256 chunk covers only 1.33 seconds.
        int rate = 192_000;
        configure(rate, C.ENCODING_PCM_16BIT);
        ByteBuffer input = buffer(3 * rate * 4);
        while (input.hasRemaining()) {
            int before = input.position();
            processor.queueInput(input);
            assertTrue(input.position() > before);
        }
        processor.queueEndOfStream();
        finish.release();
        awaitReady(engine, CHUNK_FRAMES);
        assertFalse(processor.getOutput().hasRemaining());
        assertTrue(MeeTuneKaraoke.isPreparing());
        finish.release();
        awaitReady(engine, 2 * CHUNK_FRAMES);
        assertTrue(processor.getOutput().hasRemaining());
        assertFalse(MeeTuneKaraoke.isPreparing());
        assertFalse(processor.isEnded());
    }

    @Test
    public void flushAndReenablePrepareAgainAndResetClearsOnlyItsOwnProcessor() throws Exception {
        enable(removeLeft);
        configure(C.ENCODING_PCM_16BIT);
        processor.queueInput(buffer(4));
        assertTrue(MeeTuneKaraoke.isPreparing());
        assertFalse(processor.getOutput().hasRemaining());

        processor.flush();
        ByteBuffer afterSeek = buffer(8_000 * 4);
        processor.queueInput(afterSeek);
        processor.queueInput(afterSeek);
        assertFalse(afterSeek.hasRemaining());
        assertTrue(MeeTuneKaraoke.isPreparing());
        MeeTuneKaraoke.setOn(false);
        assertFalse(MeeTuneKaraoke.isPreparing());
        int drained = processor.getOutput().remaining() / 4;
        assertTrue(drained > 0 && drained < 8_000);

        MeeTuneKaraoke.setOn(true);
        assertFalse(processor.getOutput().hasRemaining());
        processor.queueInput(buffer(4));
        assertTrue(MeeTuneKaraoke.isPreparing());
        assertFalse(processor.getOutput().hasRemaining());
        assertEquals(8_000 - drained + 1, engine.bufferedInputFrames());
        AudioProcessor other = newProcessor();
        try {
            other.configure(new AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT));
            other.flush();
            other.queueInput(buffer(4));
            processor.reset();
            assertTrue(MeeTuneKaraoke.isPreparing());
        } finally {
            other.reset();
        }
        assertFalse(MeeTuneKaraoke.isPreparing());
    }

    @Test
    public void preparingContinuesToAcceptInputUpToFortySeconds() throws Exception {
        Semaphore finish = new Semaphore(0);
        enable((input, t) -> {
            finish.acquire();
            return input;
        });
        int rate = 8_000;
        int frameBytes = 4;
        configure(rate, C.ENCODING_PCM_16BIT);

        ByteBuffer input = buffer((40 * rate + 1) * frameBytes);
        while (input.remaining() > frameBytes) {
            int before = input.position();
            processor.queueInput(input);
            assertTrue(input.position() > before);
        }
        processor.queueInput(input);
        assertEquals(frameBytes, input.remaining());

        assertFalse(processor.getOutput().hasRemaining());
        assertTrue(MeeTuneKaraoke.isPreparing());
        finish.release();
        awaitReady(engine, CHUNK_FRAMES);
        assertTrue(processor.getOutput().hasRemaining());
        assertFalse(MeeTuneKaraoke.isPreparing());
        processor.queueInput(input);
        assertFalse(input.hasRemaining());
    }

    @Test
    public void turningOffCrossfadesAlignedFramesThenDrainsTheOriginals() throws Exception {
        enable(removeLeft);
        configure(C.ENCODING_PCM_FLOAT);
        processor.queueInput(buffer(8));
        // The preparation threshold after a format change uses the new stream's rate.
        int rate = 44_100;
        processor.configure(new AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT));
        processor.flush();
        int frames = 20 * rate;
        ByteBuffer input = buffer(frames * 8);
        while (input.hasRemaining()) {
            input.putFloat(0.5f).putFloat(-0.25f);
        }
        input.flip();
        input.limit(input.capacity() - 8);
        while (input.hasRemaining()) {
            int before = input.position();
            processor.queueInput(input);
            assertTrue(input.position() > before);
        }
        awaitReady(engine, 2 * rate);
        assertTrue(MeeTuneKaraoke.isPreparing());
        assertFalse(processor.getOutput().hasRemaining());
        input.limit(input.capacity());
        processor.queueInput(input);
        ByteBuffer wet = processor.getOutput();
        int played = wet.remaining() / 8;
        assertTrue(played > 0);
        assertFalse(MeeTuneKaraoke.isPreparing());
        while (wet.hasRemaining()) {
            assertEquals(0f, wet.getFloat(), 0f);
            assertEquals(-0.25f, wet.getFloat(), 0f);
        }
        MeeTuneKaraoke.setOn(false);
        ByteBuffer next = buffer(8);
        next.putFloat(-0.875f).putFloat(0.75f).flip();
        processor.queueInput(next);
        assertEquals(0, next.position());

        int rawFrames = 0;
        while (rawFrames < frames - played) {
            ByteBuffer raw = processor.getOutput();
            assertTrue(raw.hasRemaining());
            while (raw.hasRemaining()) {
                float dry = Math.min(1f, rawFrames / 1023f);
                assertEquals(0.5f * dry, raw.getFloat(), 1e-5f);
                assertEquals(-0.25f, raw.getFloat(), 0f);
                rawFrames++;
            }
        }
        assertEquals(frames - played, rawFrames);
        ByteBuffer expected = next.duplicate();
        processor.queueInput(next);
        processor.queueEndOfStream();
        assertFalse(processor.isEnded());
        assertEquals(expected, processor.getOutput());
        assertTrue(processor.isEnded());
    }

    @Test
    public void deletingThePlayingModelTurnsOffAndDrainsRaw() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        enable(removeLeft, closed::countDown);
        configure(C.ENCODING_PCM_16BIT);
        processor.queueInput(songFrames(11));

        MeeTuneKaraoke.removeModel();
        assertFalse(MeeTuneKaraoke.isOn());
        assertFalse(MeeTuneKaraoke.isLoaded());
        ByteBuffer next = songFrames(1);
        processor.queueInput(next);
        assertEquals(0, next.position());
        assertEquals(songFrames(11), processor.getOutput());
        processor.queueInput(next);
        assertEquals(songFrames(1), processor.getOutput());
        assertTrue(closed.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void enablingDuringRawDrainSeparatesAllUnreadFrames() throws Exception {
        enable(removeLeft);
        configure(C.ENCODING_PCM_16BIT);
        int frames = 5001;
        ByteBuffer input = songFrames(frames);
        processor.queueInput(input);
        processor.queueInput(input);
        assertFalse(input.hasRemaining());
        MeeTuneKaraoke.setOn(false);
        ByteBuffer raw = processor.getOutput();
        int drained = raw.remaining() / 4;
        assertTrue(drained > 0 && drained < frames);
        assertEquals(songFrames(drained), raw);

        MeeTuneKaraoke.setOn(true);
        ByteBuffer last = songFrames(1);
        processor.queueInput(last);
        assertFalse(last.hasRemaining());
        processor.queueEndOfStream();
        int unread = frames - drained + 1;
        awaitReady(engine, unread);
        assertEquals(unread, assertMix(processor.getOutput(), 0));
        assertTrue(processor.isEnded());
    }

    @Test
    public void deletingKeepsSessionsOpenUntilTheWorkerFinishesInference() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        enable((input, t) -> {
            started.countDown();
            // Native inference can outlive the interrupt sent on release.
            try {
                assertTrue(finish.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException expected) {
                assertTrue(finish.await(3, TimeUnit.SECONDS));
            }
            return input;
        }, closed::countDown);
        configure(C.ENCODING_PCM_16BIT);
        processor.queueInput(songFrames(1));
        processor.queueEndOfStream();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            MeeTuneKaraoke.removeModel();
            processor.reset();
            assertEquals(1, closed.getCount());
        } finally {
            finish.countDown();
        }
        assertTrue(closed.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void deletingClosesSessionsOnlyAfterEveryProcessorReleasesThem() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        enable(removeLeft, closed::countDown);
        configure(C.ENCODING_PCM_16BIT);
        processor.queueInput(songFrames(1));
        AudioProcessor other = newProcessor();
        try {
            other.configure(new AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT));
            other.flush();
            other.queueInput(songFrames(1));
            MeeTuneKaraoke.removeModel();
            processor.reset();
            Thread.sleep(100);
            assertEquals(1, closed.getCount());
        } finally {
            other.reset();
        }
        assertTrue(closed.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void failedModelDuringPreparationAllowsRawDrain() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        enable((input, t) -> {
            throw new IllegalStateException("inference failed");
        });
        processor = MeeTuneKaraoke.processorWithEngineFactory((model, rate, lead, onError) ->
                new KaraokeEngine(model, rate, lead, error -> failed.countDown(),
                        new KaraokeEngineTest.TaggedTransform()));
        processor.configure(new AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT));
        processor.flush();
        ByteBuffer input = buffer(4);
        input.putShort((short) -567).putShort((short) 321).flip();
        ByteBuffer expected = input.duplicate();
        processor.queueInput(input);
        assertTrue(MeeTuneKaraoke.isPreparing());
        processor.queueEndOfStream();
        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertEquals(expected, processor.getOutput());
        assertFalse(MeeTuneKaraoke.isPreparing());
        assertTrue(processor.isEnded());
    }

    @Test
    public void unsupportedFormatsAreInactive() throws Exception {
        enable(removeLeft);
        processor = newProcessor();
        assertEquals(AudioProcessor.AudioFormat.NOT_SET,
                processor.configure(new AudioProcessor.AudioFormat(RATE, 1, C.ENCODING_PCM_16BIT)));
        assertEquals(AudioProcessor.AudioFormat.NOT_SET,
                processor.configure(new AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_24BIT)));
    }

    @Test(timeout = 4900)
    public void sinkBackPressureAllowsEveryChunkToRunWithoutAnUnderrun() throws Exception {
        TimedModel model = new TimedModel();
        enable(model);
        configure(C.ENCODING_PCM_FLOAT);

        FakeAudioTrack track = new FakeAudioTrack();
        ByteBuffer decoded = buffer(DECODED_FRAMES * 8);
        decoded.limit(0);
        ByteBuffer pending = AudioProcessor.EMPTY_BUFFER;
        int submitted = 0;
        int previousDecodeLimit = 0;
        boolean eos = false;
        boolean startedPlayback = false;
        boolean waitedForModel = false;
        for (int now = 0; track.played < SONG_FRAMES; now += TICK_FRAMES) {
            assertTrue("Playback did not finish", now < 70 * RATE);
            model.advance(now, submitted, eos, engine);
            // At 4.5x, only whole 960-frame buffers completed this tick can enter the sink.
            // Unused decode slots expire when the AudioTrack holds up input.
            int decodeLimit = now * 9 / (2 * DECODED_FRAMES);
            int availableBuffers = decodeLimit - previousDecodeLimit;
            previousDecodeLimit = decodeLimit;
            while (true) {
                if (!pending.hasRemaining()) {
                    if (!startedPlayback && submitted > 0) {
                        int ready = engine.readyFrames();
                        boolean enoughInput = engine.bufferedInputFrames() >= 20 * RATE;
                        boolean prepared = (enoughInput || eos) && ready >= 2 * RATE;
                        waitedForModel |= enoughInput && ready < 2 * RATE;
                        pending = processor.getOutput();
                        assertEquals(prepared, pending.hasRemaining());
                        assertEquals(!prepared, MeeTuneKaraoke.isPreparing());
                        startedPlayback = prepared;
                    } else {
                        pending = processor.getOutput();
                    }
                }
                // Backpressure holds this exact output until the track can take it all.
                // The sink pulls all available output before it submits another input buffer.
                if (pending.hasRemaining()) {
                    track.write(pending);
                    if (pending.hasRemaining()) break;
                    continue;
                }
                if (submitted == SONG_FRAMES) {
                    if (eos) break;
                    processor.queueEndOfStream();
                    eos = true;
                } else {
                    if (!decoded.hasRemaining()) {
                        if (availableBuffers == 0) break;
                        availableBuffers--;
                        decoded.clear();
                        for (int i = 0; i < DECODED_FRAMES; i++) {
                            decoded.putFloat(0.5f).putFloat(frameTag(submitted + i));
                        }
                        decoded.flip();
                    }
                    int before = decoded.position();
                    processor.queueInput(decoded);
                    submitted += (decoded.position() - before) / 8;
                    if (before == decoded.position()) break;
                }
            }
            if (startedPlayback) track.drain();
        }
        assertTrue(waitedForModel);
        assertFalse(MeeTuneKaraoke.isPreparing());
        assertEquals(SONG_FRAMES, submitted);
        assertEquals(submitted, track.written);
        assertEquals(SONG_FRAMES, model.nextStart);
        assertFalse(pending.hasRemaining());
        assertTrue(processor.isEnded());
    }

    private static final int SONG_FRAMES = 60 * RATE;
    private static final int CHUNK_FRAMES = 255 * KaraokeEngine.HOP - 2 * KaraokeEngine.TRIM;
    private static final int TICK_FRAMES = RATE / 100;
    private static final int DECODED_FRAMES = 960;

    private static float frameTag(int frame) {
        return frame / 4_194_304f;
    }

    private static final class FakeAudioTrack {
        int played;
        int written;

        void write(ByteBuffer output) {
            int space = RATE / 2 - (written - played);
            int frames = Math.min(space, output.remaining() / 8);
            for (int end = written + frames; written < end; written++) {
                float left = output.getFloat();
                float right = output.getFloat();
                if (left != 0f || right != frameTag(written)) {
                    throw new AssertionError("Unexpected mix or frame order at " + written);
                }
            }
        }

        void drain() {
            int demand = Math.min(TICK_FRAMES, SONG_FRAMES - played);
            int available = written - played;
            assertTrue("AudioTrack underrun at playback frame " + played, available >= demand);
            played += demand;
        }
    }

    private static final class TimedModel implements KaraokeModel {
        final Semaphore started = new Semaphore(0);
        final Semaphore finish = new Semaphore(0);
        int nextStart;
        int due;
        int modelStart;
        int modelT;

        @Override
        public float[] run(float[] input, int frames) throws InterruptedException {
            modelStart = Math.round(input[0] * 4_194_304f);
            modelT = frames;
            started.release();
            finish.acquire();
            input[0] = 0f; // Remove the left-channel vocal.
            return input;
        }

        void advance(int now, int submitted, boolean eos, KaraokeEngine engine) throws InterruptedException {
            int count = Math.min(CHUNK_FRAMES, SONG_FRAMES - nextStart);
            if (due != 0 && now >= due) {
                // Freeze simulated time until the worker has published this exact result.
                int expected = engine.readyFrames() + count;
                finish.release();
                awaitReady(engine, expected);
                nextStart += count;
                due = 0;
                count = Math.min(CHUNK_FRAMES, SONG_FRAMES - nextStart);
            }
            if (due == 0 && count > 0 && (eos || submitted >= nextStart + CHUNK_FRAMES + KaraokeEngine.TRIM)) {
                assertTrue("Model never ran for chunk at " + nextStart, started.tryAcquire(1, TimeUnit.SECONDS));
                assertEquals(nextStart, modelStart);
                assertEquals(256, modelT);
                due = now + (7 * count + 9) / 10;
            }
        }
    }

    private void assertPassthrough() throws Exception {
        configure(C.ENCODING_PCM_FLOAT);
        byte[] bytes = new byte[3001 * 8];
        new Random(281).nextBytes(bytes);
        ByteBuffer input = buffer(bytes.length);
        input.put(bytes).flip();
        processor.queueInput(input);
        assertFalse(input.hasRemaining());
        assertEquals(ByteBuffer.wrap(bytes), processor.getOutput());
        processor.queueEndOfStream();
        assertTrue(processor.isEnded());
        assertFalse(MeeTuneKaraoke.isPreparing());
        assertEquals(0, calls.get());
    }

    private void assertShortSong(int encoding) throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        enable((input, t) -> {
            finish.await();
            return removeLeft.run(input, t);
        });
        configure(encoding);
        int frames = RATE + 317;
        int frameBytes = encoding == C.ENCODING_PCM_FLOAT ? 8 : 4;
        ByteBuffer input = buffer(frames * frameBytes);
        ByteBuffer expected = buffer(input.capacity());
        Random random = new Random(281);
        for (int i = 0; i < frames; i++) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                float right = 2 * random.nextFloat() - 1;
                input.putFloat(0.5f).putFloat(right);
                expected.putFloat(0f).putFloat(right);
            } else {
                short right = (short) random.nextInt();
                input.putShort((short) 123).putShort(right);
                expected.putShort((short) 0).putShort(right);
            }
        }
        input.flip();
        expected.flip();
        ByteBuffer output = buffer(frames * frameBytes);
        while (input.hasRemaining()) {
            int start = input.position();
            processor.queueInput(input);
            assertTrue(input.position() > start);
            assertFalse(processor.getOutput().hasRemaining());
            assertTrue(MeeTuneKaraoke.isPreparing());
        }
        processor.queueEndOfStream();
        assertFalse(processor.getOutput().hasRemaining());
        assertTrue(MeeTuneKaraoke.isPreparing());
        finish.countDown();
        awaitReady(engine, frames);
        while (!processor.isEnded()) {
            ByteBuffer out = processor.getOutput();
            assertTrue(out.hasRemaining());
            output.put(out);
        }
        output.flip();
        assertEquals(expected, output);
        assertEquals(1, calls.get());
        assertFalse(MeeTuneKaraoke.isPreparing());
        processor.flush();
        assertTrue(MeeTuneKaraoke.isPreparing());
        assertFalse(processor.getOutput().hasRemaining());
    }

    private static void awaitReady(KaraokeEngine engine, int frames) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        synchronized (engine) {
            while (engine.readyFrames() < frames) {
                long left = deadline - System.nanoTime();
                assertTrue("Worker did not publish the expected frames", left > 0);
                TimeUnit.NANOSECONDS.timedWait(engine, left);
            }
        }
    }

    private void enable(KaraokeModel model) {
        enable(model, () -> {});
    }

    private void enable(KaraokeModel model, Runnable closeSessions) {
        loadSelected(model, closeSessions);
        MeeTuneKaraoke.setOn(true);
        MeeTuneEq.setPlaying(true);
    }

    private static void loadSelected(KaraokeModel model, Runnable closeSessions) {
        MeeTuneKaraoke.modelLoaded(model, closeSessions);
        assertTrue(MeeTuneKaraoke.isLoaded());
    }

    private void configure(int encoding) throws Exception {
        configure(RATE, encoding);
    }

    private void configure(int rate, int encoding) throws Exception {
        processor = newProcessor();
        processor.configure(new AudioProcessor.AudioFormat(rate, 2, encoding));
        processor.flush();
    }

    private AudioProcessor newProcessor() {
        return MeeTuneKaraoke.processorWithEngineFactory((model, rate, lead, onError) -> {
            assertEquals(0, lead);
            engine = new KaraokeEngine(model, rate, lead, onError, new KaraokeEngineTest.TaggedTransform());
            return engine;
        });
    }

    private static ByteBuffer songFrames(int frames) {
        ByteBuffer input = buffer(frames * 4);
        while (input.hasRemaining()) input.putShort((short) 123).putShort((short) -444);
        input.flip();
        return input;
    }

    private static int assertMix(ByteBuffer output, int left) {
        int frames = output.remaining() / 4;
        assertTrue(frames > 0);
        while (output.hasRemaining()) {
            assertEquals(left, output.getShort());
            assertEquals(-444, output.getShort());
        }
        return frames;
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
