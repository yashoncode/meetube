package com.newtube.mobile.downloads;

import android.util.Log;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches one stream of known length as {@code Range} chunks on several connections at once, each
 * chunk written straight to its place in the part file: nothing is merged after the last byte.
 *
 * <p>{@code <part>.chunks} records the finished chunks (header line {@code c1 base total chunk},
 * then one index per line, appended only after the chunk's bytes are synced), so a pause, a process
 * death or a fresh link after a 403 fetches only the chunks still missing. {@code base} is the
 * contiguous prefix the file held when the record was made: a part download's header (issue #241)
 * or a part fetched sequentially by an older version.
 *
 * <p>Concurrency adapts: it starts at {@link #START_WORKERS}, adds a connection while a round of
 * chunks at the new level is clearly faster than the last, and drops one on every failure (a 429,
 * 503 or a stalled socket is the server or the network saying "fewer"). {@link #CONNECTIONS} caps
 * all fetches together, so several downloads with a video and an audio stream each stay polite.
 */
final class ParallelFetch {
    private static final String TAG = ParallelFetch.class.getSimpleName();
    static final long MIN_CHUNK = 1L << 20;
    static final long MAX_CHUNK = 8L << 20;
    static final int START_WORKERS = 3;
    private static final int MAX_ATTEMPTS = 8;
    /** Connections across every running fetch. */
    private static final Semaphore CONNECTIONS = new Semaphore(8);
    /** Chunk workers, the audio stream of a job, thumbnails. */
    static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "meetube-download-io");
        t.setDaemon(true);
        return t;
    });

    /** The server ignored the range or disagrees on the length: fetch this stream sequentially. */
    static final class NotRangeable extends IOException {
        NotRangeable(String why) {
            super(why);
        }
    }

    private final OkHttpClient mClient;
    private final String mUserAgent;
    private final String mUrl;
    private final File mTarget;
    private final long mTotal;
    private final long mShift;
    private final int mMaxWorkers;
    private final StreamFetcher.Progress mProgress;

    private final Object mLock = new Object();
    private final ArrayDeque<Integer> mPending = new ArrayDeque<>();
    private final Set<Call> mCalls = new HashSet<>();
    private Record mRecord;
    private Throttle mThrottle;
    private FileChannel mChannel;
    private FileOutputStream mLog;
    private int mRunning;
    private int mPeak;
    private int mFinished;
    private int mFailures;
    private long mCommitted;
    private long mLive;
    private boolean mAborted;
    @Nullable private IOException mFatal;

    ParallelFetch(OkHttpClient client, String userAgent, String url, File target, long total, long shift,
                  int maxWorkers, StreamFetcher.Progress progress) {
        mClient = client;
        mUserAgent = userAgent;
        mUrl = url;
        mTarget = target;
        mTotal = total;
        mShift = shift;
        mMaxWorkers = Math.max(1, maxWorkers);
        mProgress = progress;
    }

    /** Monotonic, and real under Robolectric (whose SystemClock stands still). */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    static File sidecar(File part) {
        return new File(part.getParentFile(), part.getName() + ".chunks");
    }

    /** Deletes a part and its chunk record. */
    static void reset(File part) {
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        //noinspection ResultOfMethodCallIgnored
        sidecar(part).delete();
    }

    /** A part whose chunk record was made for a stream of another length starts over. */
    static void dropIfStale(File part, long expectedLength) {
        Record record = Record.read(sidecar(part));
        if (record != null && record.total != expectedLength) {
            reset(part);
        }
    }

    /** Chunk size for {@code bytes}: about three chunks per connection, 1 to 8 MiB. */
    static long chunkSize(long bytes, int maxWorkers) {
        long size = (bytes / (Math.max(1, maxWorkers) * 3L) + 0xFFFF) & ~0xFFFFL; // 64 KiB steps
        return Math.max(MIN_CHUNK, Math.min(MAX_CHUNK, size));
    }

    long run() throws IOException {
        File side = sidecar(mTarget);
        mRecord = Record.read(side);
        if (mRecord != null && mRecord.total != mTotal) {
            if (mShift != 0) {
                throw new IOException("stale part");
            }
            reset(mTarget);
            mRecord = null;
        }
        if (mRecord == null) {
            long base = mTarget.exists() ? Math.min(mTarget.length(), mTotal) : 0;
            if (base >= mTotal) {
                return mTotal;
            }
            mRecord = new Record(base, mTotal, chunkSize(mTotal - base, mMaxWorkers));
            mRecord.writeHeader(side); // before the file grows: a torn header leaves the prefix as it was
        }

        long startMs = nowMs();
        int count = mRecord.count();
        mCommitted = mRecord.base;
        for (int i = 0; i < count; i++) {
            if (mRecord.done.get(i)) {
                mFinished++;
                mCommitted += mRecord.size(i);
            } else {
                mPending.add(i);
            }
        }
        long resumedBytes = mCommitted;
        if (mPending.isEmpty()) {
            return mTotal;
        }
        mThrottle = new Throttle(Math.min(START_WORKERS, mMaxWorkers), mMaxWorkers, startMs, mCommitted);

        try (RandomAccessFile file = new RandomAccessFile(mTarget, "rw");
             FileOutputStream log = new FileOutputStream(side, true)) {
            if (file.length() < mTotal) {
                file.setLength(mTotal);
            }
            mChannel = file.getChannel();
            mLog = log;
            coordinate(count);
        }

        long ms = Math.max(1, nowMs() - startMs);
        NetPath.log("download parallel part=" + mTarget.getName() + " chunks=" + count
                + " chunk=" + (mRecord.chunk >> 10) + "k peak=" + mPeak + " ms=" + ms
                + " kBps=" + (mTotal - resumedBytes) / ms);
        return mTotal;
    }

    private void coordinate(int count) throws IOException {
        while (true) {
            synchronized (mLock) {
                while (mFatal == null && !mPending.isEmpty() && mRunning < mThrottle.level) {
                    mRunning++;
                    mPeak = Math.max(mPeak, mRunning);
                    POOL.execute(this::work);
                }
                if (mFatal != null || mFinished == count) {
                    break;
                }
                try {
                    mLock.wait(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mFatal = new StreamFetcher.CancelledException();
                    break;
                }
            }
            if (!mProgress.onBytes(bytes(), mTotal)) {
                synchronized (mLock) {
                    if (mFatal == null) mFatal = new StreamFetcher.CancelledException();
                }
                break;
            }
        }
        stopWorkers();
        if (mFatal != null) {
            throw mFatal;
        }
        mProgress.onBytes(mTotal, mTotal);
    }

    private long bytes() {
        synchronized (mLock) {
            return mCommitted + mLive;
        }
    }

    /** Stops every worker and waits until none can touch the file any more. */
    private void stopWorkers() {
        synchronized (mLock) {
            mAborted = true;
            for (Call call : mCalls) {
                call.cancel();
            }
            mLock.notifyAll();
            while (mRunning > 0) {
                try {
                    mLock.wait(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void work() {
        try {
            while (true) {
                int index;
                synchronized (mLock) {
                    Integer next = mAborted || mFatal != null ? null : mPending.poll();
                    if (next == null) {
                        return;
                    }
                    index = next;
                }
                if (!acquireConnection()) {
                    requeue(index);
                    return;
                }
                boolean retire = false;
                long backoffMs = 0;
                try {
                    fetchChunk(index);
                    commit(index);
                } catch (StreamFetcher.UrlRefusedException | NotRangeable e) {
                    requeue(index);
                    fail(e);
                    return;
                } catch (IOException e) {
                    requeue(index);
                    synchronized (mLock) {
                        if (mAborted) {
                            return;
                        }
                        retire = mThrottle.onFailure();
                        if (!retire && ++mFailures >= MAX_ATTEMPTS) {
                            fail(e);
                            return;
                        }
                        backoffMs = retire ? 0 : Math.min(15_000, 1_000L << Math.min(mFailures, 4));
                    }
                    Log.w(TAG, "chunk " + index + " failed (" + (retire ? "fewer connections" : "retry in " + backoffMs + "ms") + "): " + e);
                } finally {
                    CONNECTIONS.release();
                }
                if (retire) {
                    return;
                }
                if (backoffMs > 0) {
                    synchronized (mLock) {
                        long until = nowMs() + backoffMs;
                        long left;
                        while (!mAborted && (left = until - nowMs()) > 0) {
                            mLock.wait(left);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            fail(new StreamFetcher.CancelledException());
        } catch (RuntimeException e) {
            fail(new IOException("chunk worker crashed", e));
        } finally {
            synchronized (mLock) {
                mRunning--;
                mLock.notifyAll();
            }
        }
    }

    private boolean acquireConnection() throws InterruptedException {
        while (!CONNECTIONS.tryAcquire(250, TimeUnit.MILLISECONDS)) {
            synchronized (mLock) {
                if (mAborted || mFatal != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void fetchChunk(int index) throws IOException {
        long from = mRecord.start(index);
        long end = mRecord.end(index); // exclusive
        Call call = mClient.newCall(new Request.Builder()
                .url(mUrl)
                .header("User-Agent", mUserAgent)
                .header("Range", "bytes=" + (from + mShift) + "-" + (end - 1 + mShift))
                .build());
        synchronized (mLock) {
            if (mAborted) {
                throw new IOException("aborted");
            }
            mCalls.add(call);
        }
        long got = 0;
        try (Response response = call.execute()) {
            int code = response.code();
            if (code == 403 || code == 410 || code == 404) {
                throw new StreamFetcher.UrlRefusedException(code);
            }
            if (code == 200) {
                if (mShift != 0) {
                    throw new IOException("range ignored on a part download");
                }
                throw new NotRangeable("range ignored");
            }
            if (code != 206) {
                throw new IOException("stream HTTP " + code);
            }
            long announced = mShift != 0 ? -1 : StreamFetcher.totalFromContentRange(response.header("Content-Range"));
            if (announced > 0 && announced != mTotal) {
                throw new NotRangeable("length " + announced + " != " + mTotal);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty body");
            }
            byte[] buf = new byte[64 * 1024];
            long position = from;
            try (InputStream in = body.byteStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (position + n > end) {
                        throw new IOException("chunk longer than asked");
                    }
                    ByteBuffer bytes = ByteBuffer.wrap(buf, 0, n);
                    while (bytes.hasRemaining()) {
                        position += mChannel.write(bytes, position);
                    }
                    got += n;
                    synchronized (mLock) {
                        if (mAborted) {
                            throw new IOException("aborted");
                        }
                        mLive += n;
                    }
                }
            }
            if (position != end) {
                throw new IOException("short chunk: " + (position - from) + "/" + (end - from));
            }
        } finally {
            synchronized (mLock) {
                mCalls.remove(call);
                mLive -= got;
            }
        }
    }

    /** The chunk's bytes are on disk before the record says so; the record line is the commit. */
    private void commit(int index) throws IOException {
        mChannel.force(false);
        synchronized (mLock) {
            mLog.write((index + "\n").getBytes(StandardCharsets.US_ASCII));
            mCommitted += mRecord.size(index);
            mFinished++;
            mFailures = 0;
            mThrottle.onChunk(nowMs(), mCommitted);
            mLock.notifyAll();
        }
    }

    private void requeue(int index) {
        synchronized (mLock) {
            mPending.addFirst(index);
            mLock.notifyAll();
        }
    }

    private void fail(IOException e) {
        synchronized (mLock) {
            if (mFatal == null) {
                mFatal = e;
            }
            mLock.notifyAll();
        }
    }

    /** The chunk grid of one part file and which chunks are finished. */
    static final class Record {
        final long base;
        final long total;
        final long chunk;
        final BitSet done = new BitSet();

        Record(long base, long total, long chunk) {
            this.base = base;
            this.total = total;
            this.chunk = chunk;
        }

        int count() {
            return (int) ((total - base + chunk - 1) / chunk);
        }

        long start(int index) {
            return base + index * chunk;
        }

        long end(int index) {
            return Math.min(total, start(index) + chunk);
        }

        long size(int index) {
            return end(index) - start(index);
        }

        void writeHeader(File file) throws IOException {
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(("c1 " + base + " " + total + " " + chunk + "\n").getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
        }

        /** Null when absent or unreadable; a torn last line (a death mid-append) is skipped. */
        @Nullable
        static Record read(File file) {
            if (!file.isFile()) {
                return null;
            }
            try (BufferedReader in = new BufferedReader(new FileReader(file))) {
                String[] head = String.valueOf(in.readLine()).split(" ");
                if (head.length != 4 || !"c1".equals(head[0])) {
                    return null;
                }
                Record record = new Record(Long.parseLong(head[1]), Long.parseLong(head[2]), Long.parseLong(head[3]));
                if (record.base < 0 || record.chunk <= 0 || record.total <= record.base) {
                    return null;
                }
                int count = record.count();
                String line;
                while ((line = in.readLine()) != null) {
                    try {
                        int index = Integer.parseInt(line.trim());
                        if (index >= 0 && index < count) {
                            record.done.set(index);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                return record;
            } catch (IOException | NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * How many connections one fetch uses. Grows by one after a round of {@code level} chunks
     * if that round was at least 10% faster than the one before (the first round always grows),
     * stops growing at the first round that was not (the link or the server is saturated), and
     * gives one back on each failure, never below one.
     */
    static final class Throttle {
        final int max;
        int level;
        private boolean mFrozen;
        private double mLastRate = -1;
        private long mWindowMs;
        private long mWindowBytes;
        private int mChunks;

        Throttle(int start, int max, long nowMs, long bytes) {
            this.max = Math.max(1, max);
            this.level = Math.max(1, Math.min(start, this.max));
            mWindowMs = nowMs;
            mWindowBytes = bytes;
        }

        void onChunk(long nowMs, long bytes) {
            if (mFrozen || level >= max || ++mChunks < level) {
                return;
            }
            double rate = (bytes - mWindowBytes) / (double) Math.max(1, nowMs - mWindowMs);
            if (mLastRate >= 0 && rate < mLastRate * 1.1) {
                mFrozen = true;
                return;
            }
            mLastRate = rate;
            level++;
            mWindowMs = nowMs;
            mWindowBytes = bytes;
            mChunks = 0;
        }

        /** @return true when the failing worker should stop (one connection fewer). */
        boolean onFailure() {
            mFrozen = true;
            if (level <= 1) {
                return false;
            }
            level--;
            return true;
        }
    }
}
