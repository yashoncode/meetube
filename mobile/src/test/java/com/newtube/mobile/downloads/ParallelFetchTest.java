package com.newtube.mobile.downloads;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ParallelFetchTest {
    private static final int SIZE = 5 * 1024 * 1024 + 12345; // a few chunks with a short last one
    private static final String CRLF = "\r\n";

    private final byte[] mPayload = new byte[SIZE];
    private final ExecutorService mPool = Executors.newCachedThreadPool();
    private final Queue<String> mRanges = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mConcurrent = new AtomicInteger();
    private final AtomicInteger mPeak = new AtomicInteger();
    /** The first two ranges are answered 503 once. */
    private final Set<String> mFlaky = ConcurrentHashMap.newKeySet();
    private volatile boolean mFlakyOn;
    private volatile boolean mIgnoreRange;
    private ServerSocket mServer;
    private String mUrl;
    private File mDir;

    @Before
    public void setUp() throws Exception {
        new Random(7).nextBytes(mPayload);
        mDir = Files.createTempDirectory("pf").toFile();
        mServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!mServer.isClosed()) {
                try {
                    Socket socket = mServer.accept();
                    mPool.execute(() -> serve(socket));
                } catch (IOException e) {
                    return;
                }
            }
        });
        accept.setDaemon(true);
        accept.start();
        mUrl = "http://127.0.0.1:" + mServer.getLocalPort() + "/s";
    }

    @After
    public void tearDown() throws IOException {
        mServer.close();
        mPool.shutdownNow();
    }

    /** A keep-alive HTTP/1.1 connection: GETs with an optional Range, answered from mPayload. */
    private void serve(Socket socket) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = s.getOutputStream();
            String requestLine;
            while ((requestLine = in.readLine()) != null && !requestLine.isEmpty()) {
                String range = null;
                for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
                    if (h.regionMatches(true, 0, "Range:", 0, 6)) range = h.substring(6).trim();
                }
                mRanges.add(String.valueOf(range));
                mPeak.accumulateAndGet(mConcurrent.incrementAndGet(), Math::max);
                try {
                    if (mFlakyOn && range != null && mFlaky.size() < 2 && mFlaky.add(range)) {
                        out.write(("HTTP/1.1 503 Busy" + CRLF + "Content-Length: 0" + CRLF + CRLF).getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        continue;
                    }
                    int from = 0, to = SIZE - 1;
                    String head = "HTTP/1.1 200 OK" + CRLF;
                    if (range != null && !mIgnoreRange) {
                        String[] p = range.substring("bytes=".length()).split("-");
                        from = Integer.parseInt(p[0]);
                        to = Math.min(SIZE - 1, Integer.parseInt(p[1]));
                        head = "HTTP/1.1 206 Partial Content" + CRLF
                                + "Content-Range: bytes " + from + "-" + to + "/" + SIZE + CRLF;
                    }
                    Thread.sleep(20); // a round trip, so connections overlap
                    out.write((head + "Content-Length: " + (to - from + 1) + CRLF + CRLF).getBytes(StandardCharsets.US_ASCII));
                    out.write(mPayload, from, to - from + 1);
                    out.flush();
                } finally {
                    mConcurrent.decrementAndGet();
                }
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private StreamFetcher fetcher(int workers) {
        StreamFetcher fetcher = new StreamFetcher(new OkHttpClient(), "test-agent");
        fetcher.setMaxWorkers(workers);
        return fetcher;
    }

    @Test
    public void fetchesTheWholeStreamOnSeveralConnections() throws Exception {
        File part = new File(mDir, "a.video");
        long length = fetcher(4).fetch(mUrl, part, SIZE, 0, (bytes, total) -> true);

        assertEquals(SIZE, length);
        assertArrayEquals(mPayload, Files.readAllBytes(part.toPath()));
        assertTrue("ranges overlapped, peak=" + mPeak.get(), mPeak.get() > 1);
        assertEquals(ParallelFetch.Record.read(ParallelFetch.sidecar(part)).count(), mRanges.size()); // each chunk once
    }

    @Test
    public void aPauseKeepsFinishedChunksAndTheResumeFetchesOnlyTheRest() throws Exception {
        File part = new File(mDir, "b.video");
        try {
            fetcher(2).fetch(mUrl, part, SIZE, 0, (bytes, total) -> bytes < 2 * ParallelFetch.MIN_CHUNK);
            fail("expected the pause");
        } catch (StreamFetcher.CancelledException expected) {
        }
        ParallelFetch.Record record = ParallelFetch.Record.read(ParallelFetch.sidecar(part));
        assertTrue(record != null && record.done.cardinality() > 0);
        mRanges.clear();

        fetcher(2).fetch(mUrl, part, SIZE, 0, (bytes, total) -> true);

        assertArrayEquals(mPayload, Files.readAllBytes(part.toPath()));
        for (int i = record.done.nextSetBit(0); i >= 0; i = record.done.nextSetBit(i + 1)) {
            String range = "bytes=" + record.start(i) + "-" + (record.end(i) - 1);
            assertFalse("refetched finished chunk " + range, mRanges.contains(range));
        }
        assertFalse(mRanges.isEmpty());
    }

    @Test
    public void aServerThatIgnoresRangesFallsBackToOneRequest() throws Exception {
        mIgnoreRange = true;
        File part = new File(mDir, "c.audio");
        long length = fetcher(4).fetch(mUrl, part, SIZE, 0, (bytes, total) -> true);

        assertEquals(SIZE, length);
        assertArrayEquals(mPayload, Files.readAllBytes(part.toPath()));
        assertFalse(ParallelFetch.sidecar(part).exists());
    }

    @Test
    public void aPartDownloadKeepsItsHeaderAndShiftsTheRest() throws Exception {
        byte[] header = "HEADER".getBytes(StandardCharsets.US_ASCII);
        int from = 1_000_000; // the stream bytes the file continues with
        File part = new File(mDir, "d.video");
        Files.write(part.toPath(), header);
        long fileLength = header.length + (SIZE - from);

        fetcher(3).fetch(mUrl, part, fileLength, from - header.length, (bytes, total) -> true);

        byte[] got = Files.readAllBytes(part.toPath());
        assertArrayEquals(header, Arrays.copyOf(got, header.length));
        assertArrayEquals(Arrays.copyOfRange(mPayload, from, SIZE), Arrays.copyOfRange(got, header.length, got.length));
    }

    @Test
    public void aFlakyServerCostsConnectionsNotTheDownload() throws Exception {
        mFlakyOn = true;
        File part = new File(mDir, "e.video");
        fetcher(4).fetch(mUrl, part, SIZE, 0, (bytes, total) -> true);
        assertArrayEquals(mPayload, Files.readAllBytes(part.toPath()));
    }

    @Test
    public void aRecordForAnotherLengthIsDropped() throws Exception {
        File part = new File(mDir, "f.video");
        Files.write(part.toPath(), new byte[100]);
        new ParallelFetch.Record(0, 999, ParallelFetch.MIN_CHUNK).writeHeader(ParallelFetch.sidecar(part));
        ParallelFetch.dropIfStale(part, SIZE);
        assertFalse(part.exists());
        assertFalse(ParallelFetch.sidecar(part).exists());
    }

    @Test
    public void throttleGrowsWhileFasterThenHoldsAndBacksOffOnFailure() {
        ParallelFetch.Throttle t = new ParallelFetch.Throttle(2, 5, 0, 0);
        t.onChunk(500, 1000);
        t.onChunk(1000, 2000); // first round: 2 bytes/ms, grows
        assertEquals(3, t.level);
        t.onChunk(1500, 3000);
        t.onChunk(1600, 4000);
        t.onChunk(2000, 5000); // 3 bytes/ms > 2 * 1.1: grows
        assertEquals(4, t.level);
        for (int i = 1; i <= 4; i++) t.onChunk(2000 + i * 500, 5000 + i * 1500); // 3 bytes/ms: flat, holds
        assertEquals(4, t.level);
        for (int i = 1; i <= 8; i++) t.onChunk(4000 + i * 100, 11000 + i * 9000); // much faster, but frozen
        assertEquals(4, t.level);
        assertTrue(t.onFailure());
        assertEquals(3, t.level);
        assertTrue(t.onFailure());
        assertTrue(t.onFailure());
        assertFalse(t.onFailure()); // the last connection retries instead
        assertEquals(1, t.level);
    }

    @Test
    public void chunkSizeStaysBetweenOneAndEightMiB() {
        assertEquals(ParallelFetch.MIN_CHUNK, ParallelFetch.chunkSize(3_000_000, 6));
        assertEquals(ParallelFetch.MAX_CHUNK, ParallelFetch.chunkSize(2_000_000_000L, 6));
        long mid = ParallelFetch.chunkSize(60L << 20, 6); // 60 MiB / 18
        assertTrue(mid > ParallelFetch.MIN_CHUNK && mid < ParallelFetch.MAX_CHUNK && mid % 0x10000 == 0);
    }
}
