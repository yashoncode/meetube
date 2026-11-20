package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Starting a song, turning karaoke on, or seeking shows a loading indicator while the
 * MeeAI Model processes its first chunk. The song then plays with vocals removed while
 * the worker stays ahead of playback. If the phone falls behind, that part plays with vocals.
 */
public final class MeeTuneKaraoke {
    private static volatile boolean sOn;
    private static volatile int sEnableGeneration;
    private static int sPreparingProcessors;
    @Nullable
    private static LoadedModel sLoaded;
    private static final CopyOnWriteArrayList<Runnable> sStateListeners = new CopyOnWriteArrayList<>();

    private MeeTuneKaraoke() {
    }

    public static boolean isOn() {
        return sOn;
    }

    public static synchronized boolean isPreparing() {
        return sOn && sPreparingProcessors > 0;
    }

    public static synchronized void setOn(boolean on) {
        if (sOn == on) return;
        if (on) sEnableGeneration++;
        sOn = on;
        postStateChange();
        KaraokePack.notifyListeners();
    }

    private static final class LoadedModel {
        final KaraokeModel model;
        final Runnable closeSessions;
        int workers;

        LoadedModel(KaraokeModel model, Runnable closeSessions) {
            this.model = model;
            this.closeSessions = closeSessions;
        }
    }

    // Only in-memory bookkeeping takes this lock. The pack opens and closes native sessions off-thread.
    static synchronized boolean isLoaded() {
        return sLoaded != null;
    }

    static synchronized void modelLoaded(KaraokeModel model, Runnable closeSessions) {
        sLoaded = new LoadedModel(model, closeSessions);
    }

    /** The model was deleted: stop, and close its sessions once no song is separating with them. */
    static synchronized void removeModel() {
        LoadedModel removed = sLoaded;
        sLoaded = null;
        setOn(false);
        if (removed != null && removed.workers == 0) removed.closeSessions.run();
    }

    @Nullable
    private static synchronized LoadedModel bind(Processor processor, @Nullable LoadedModel reusable) {
        if (!sOn || !MeeTuneEq.isPlaying() || sLoaded == null) return null;
        if (sLoaded != reusable) sLoaded.workers++;
        processor.mEnableGeneration = sEnableGeneration;
        return sLoaded;
    }

    private static synchronized void workerStopped(LoadedModel used) {
        // Deletion removes the model from the lookup immediately, even during inference.
        if (--used.workers == 0 && sLoaded != used) used.closeSessions.run();
    }

    /** Each title chip listens for the lifetime of its attachment to a window. */
    static void addStateListener(Runnable listener) {
        sStateListeners.addIfAbsent(listener);
    }

    static void removeStateListener(Runnable listener) {
        sStateListeners.remove(listener);
    }

    private static void postStateChange() {
        if (!sStateListeners.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                for (Runnable listener : sStateListeners) listener.run();
            });
        }
    }

    public static AudioProcessor processor(Context context) {
        KaraokePack.prepareStorage(context);
        return processorWithEngineFactory(KaraokeEngine::new);
    }

    interface EngineFactory {
        KaraokeEngine create(KaraokeModel model, int rate, int leadFrames, Consumer<Throwable> onError);
    }

    static AudioProcessor processorWithEngineFactory(EngineFactory factory) {
        return new Processor(factory);
    }

    private static final class Processor extends BaseAudioProcessor {
        private static final int BLOCK = 4096;
        private static final long PREPARE_READY_SECONDS = 2;
        private static final long MAX_BUFFER_SECONDS = 40;
        private final EngineFactory mEngineFactory;
        private final float[] mSamples = new float[2 * BLOCK];
        private KaraokeEngine mEngine;
        private LoadedModel mEngineModel;
        private boolean mSeparating;
        private boolean mPreparing;
        private int mEnableGeneration;

        Processor(EngineFactory engineFactory) {
            mEngineFactory = engineFactory;
        }

        private void setPreparing(boolean preparing) {
            if (mPreparing == preparing) return;
            mPreparing = preparing;
            synchronized (MeeTuneKaraoke.class) {
                boolean wasPreparing = isPreparing();
                sPreparingProcessors += preparing ? 1 : -1;
                if (wasPreparing != isPreparing()) postStateChange();
            }
        }

        @Override
        protected AudioFormat onConfigure(AudioFormat format) {
            if (format.channelCount != 2
                    || (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT)) {
                return AudioFormat.NOT_SET;
            }
            return format;
        }

        private void updateMode(boolean hasInput) {
            if (sOn && MeeTuneEq.isPlaying()) {
                if (mSeparating && mEnableGeneration == sEnableGeneration) return;
                if ((hasInput || mEngine != null) && startEngine(false)) return;
            }
            if (mSeparating) {
                mEngine.beginRawDrain();
                mSeparating = false;
                setPreparing(false);
            }
            if (mEngine != null && mEngine.bufferedInputFrames() == 0) {
                releaseEngine();
            }
        }

        private boolean startEngine(boolean flush) {
            int rate = inputAudioFormat.sampleRate;
            LoadedModel reusable = mEngine != null && mEngine.sampleRate == rate && !mEngine.hasFailed()
                    ? mEngineModel : null;
            LoadedModel next = bind(this, reusable);
            if (next == null) return false;
            if (next == reusable) {
                if (flush) mEngine.reset();
                else mEngine.restartAtReadPosition();
            } else {
                KaraokeEngine replacement;
                try {
                    replacement = mEngineFactory.create(next.model, rate, 0,
                            error -> Log.e("MeeTuneKaraoke", "Vocal removal failed", error));
                } catch (RuntimeException | Error e) {
                    workerStopped(next);
                    throw e;
                }
                if (mEngine != null) {
                    // A quick off/on can leave original PCM at the read cursor. Only a flush discards it.
                    if (!flush) mEngine.moveUnreadInputTo(replacement);
                    stopWorker();
                }
                mEngine = replacement;
                mEngineModel = next;
            }
            mSeparating = true;
            setPreparing(true);
            return true;
        }

        @Override
        public void queueInput(ByteBuffer input) {
            if (!input.hasRemaining() || hasPendingOutput()) {
                return;
            }
            int frameBytes = inputAudioFormat.bytesPerFrame;
            int frames = Math.min(BLOCK, input.remaining() / frameBytes);
            if (frames == 0) {
                return;
            }
            updateMode(true);
            if (!mSeparating && mEngine != null) return; // Drain accepted frames before passthrough.
            if (mEngine == null) {
                int limit = input.limit();
                input.limit(input.position() + frames * frameBytes);
                ByteBuffer output = replaceOutputBuffer(input.remaining());
                output.put(input).flip();
                input.limit(limit);
                return;
            }

            long capacity = Math.max(MAX_BUFFER_SECONDS * inputAudioFormat.sampleRate, mEngine.prepareInputFrames());
            long space = capacity - mEngine.bufferedInputFrames();
            if (space <= 0) {
                return;
            }
            frames = (int) Math.min(frames, space);
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                for (int i = 0; i < 2 * frames; i++) {
                    mSamples[i] = input.getFloat();
                }
            } else {
                for (int i = 0; i < 2 * frames; i++) {
                    mSamples[i] = input.getShort() / 32768f;
                }
            }
            mEngine.write(mSamples, frames);
            if (!mPreparing) {
                // The sink exhausts pending output before offering more input. Returning one
                // frame per new frame keeps the worker's head start until the stream ends.
                produceOutput(frames);
            }
        }

        @Override
        public ByteBuffer getOutput() {
            if (hasPendingOutput()) {
                return super.getOutput();
            }
            updateMode(false);
            if (mEngine == null) {
                return EMPTY_BUFFER;
            }
            boolean ended = super.isEnded();
            if (mPreparing) {
                int buffered = mEngine.bufferedInputFrames();
                int rate = inputAudioFormat.sampleRate;
                boolean inputReady = ended || buffered >= mEngine.prepareInputFrames();
                long target = PREPARE_READY_SECONDS * rate;
                if ((!inputReady || mEngine.readyFrames() < Math.min(target, buffered)) && !mEngine.hasFailed()) {
                    return EMPTY_BUFFER;
                }
                setPreparing(false);
                produceOutput(BLOCK);
            } else if (ended || !mSeparating) {
                produceOutput(BLOCK);
            }
            return super.getOutput();
        }

        private void produceOutput(int maxFrames) {
            int frames = mSeparating ? mEngine.read(mSamples, maxFrames) : mEngine.readRaw(mSamples, maxFrames);
            if (frames == 0) {
                return;
            }
            ByteBuffer output = replaceOutputBuffer(frames * inputAudioFormat.bytesPerFrame);
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                for (int i = 0; i < 2 * frames; i++) {
                    output.putFloat(mSamples[i]);
                }
            } else {
                for (int i = 0; i < 2 * frames; i++) {
                    int pcm = Math.round(mSamples[i] * 32768f);
                    output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm)));
                }
            }
            output.flip();
            if (!mSeparating && mEngine.bufferedInputFrames() == 0) releaseEngine();
        }

        @Override
        protected void onQueueEndOfStream() {
            if (mEngine != null) {
                mEngine.endOfStream();
                if (mEngine.isDrained()) setPreparing(false);
            }
        }

        @Override
        public boolean isEnded() {
            return super.isEnded() && (mEngine == null || mEngine.isDrained());
        }

        @Override
        protected void onFlush() {
            if (mEngine != null && (!isActive() || !startEngine(true))) {
                releaseEngine();
            }
        }

        @Override
        protected void onReset() {
            releaseEngine();
        }

        private void releaseEngine() {
            if (mEngine != null) {
                stopWorker();
            }
            mSeparating = false;
            setPreparing(false);
        }

        private void stopWorker() {
            LoadedModel used = mEngineModel;
            mEngine.release(() -> workerStopped(used));
            mEngine = null;
            mEngineModel = null;
        }
    }
}
