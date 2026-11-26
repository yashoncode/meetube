package com.newtube.mobile.downloads;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Joins a downloaded video part and audio part into one file by copying bytes, not samples.
 *
 * <p>MediaExtractor -> MediaMuxer ({@link MediaMuxHelper}) moves one sample per call, and the
 * platform muxer waits for its writer thread on every one: about 0.3 ms a sample, so a 15-minute
 * 1080p video spent 45 s joining after a 5 s download. YouTube's parts are already laid out in
 * runs of contiguous samples (an MP4 fragment's mdat, a WebM cluster's blocks), so this keeps those
 * runs as they are and only writes the index around them: an MP4 gets a classic moov with the
 * sample tables, a WebM gets one Tracks element for both tracks and clusters that interleave them.
 * The index goes first, so the gallery and players open the file without seeking to its end.
 *
 * <p>A part of the video (issue #241) is cut the way {@link MediaMuxHelper} cuts it: from the video
 * keyframe at or before the start (an MP4 sample, a WebM cluster) to the first one at or after the
 * end, the audio from where that keyframe shows, everything shifted to start at zero.
 *
 * <p>Everything is read and planned before a byte is written. Anything YouTube does not send
 * (two tracks in one part, a whole part that does not start at zero, an unknown-size cluster)
 * throws {@link Unsupported} at that stage, with the output untouched, and the caller muxes instead.
 */
final class FastJoin {
    /** The parts are outside what this handles; nothing was written. */
    static final class Unsupported extends IOException {
        Unsupported(String message) {
            super(message);
        }
    }

    interface Progress {
        /** @return false to abort. */
        boolean onCopied(long bytes, long totalBytes);
    }

    private FastJoin() {
    }

    /**
     * @param clipStartUs with {@code clipEndUs > clipStartUs}, keeps only that part (stream time)
     * @return the duration in ms
     */
    static long join(@Nullable File video, @Nullable File audio, boolean webm, long clipStartUs, long clipEndUs,
                     FileChannel out, Progress progress) throws IOException {
        List<FileInputStream> streams = new ArrayList<>();
        try {
            FileChannel v = video != null ? open(video, streams) : null;
            FileChannel a = audio != null ? open(audio, streams) : null;
            return webm ? Webm.join(v, a, clipStartUs, clipEndUs, out, progress)
                    : Mp4.join(v, a, clipStartUs, clipEndUs, out, progress);
        } finally {
            for (FileInputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static FileChannel open(File file, List<FileInputStream> streams) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        streams.add(stream);
        return stream.getChannel();
    }

    // ---------------------------------------------------------------------------------------- MP4

    private static final class Mp4 {
        static final int FTYP = 0x66747970, MOOV = 0x6d6f6f76, MOOF = 0x6d6f6f66, MVHD = 0x6d766864,
                TRAK = 0x7472616b, MVEX = 0x6d766578, TREX = 0x74726578, TKHD = 0x746b6864, EDTS = 0x65647473,
                ELST = 0x656c7374, MDIA = 0x6d646961, MDHD = 0x6d646864, MINF = 0x6d696e66, STBL = 0x7374626c,
                STSD = 0x73747364, STSZ = 0x7374737a, TRAF = 0x74726166, TFHD = 0x74666864, TFDT = 0x74666474,
                TRUN = 0x7472756e, MDAT = 0x6d646174, STTS = 0x73747473, CTTS = 0x63747473, STSS = 0x73747373,
                STSC = 0x73747363, STCO = 0x7374636f, CO64 = 0x636f3634, ISOM = 0x69736f6d, ISO2 = 0x69736f32,
                MP41 = 0x6d703431;
        static final int MAX_HEADER_BOX = 64 << 20;

        /** One part: its header boxes and every sample, grouped in the runs the fragments hold. */
        static final class Track {
            final FileChannel in;
            byte[] mvhd;
            long movieTimescale;
            byte[] trak;
            int trackId;
            long timescale;
            long trexDuration;
            long trexSize;
            long trexFlags;
            int editEntries;
            long mediaTime; // the one edit's media_time: the stream time shown at zero
            Ints sizes = new Ints();
            Ints durations = new Ints();
            Ints ctos = new Ints();
            Ints nonSync = new Ints(); // 1-based numbers of non-sync samples
            boolean anyCto;
            boolean signedCto;
            long firstDts; // the first fragment's decode time: not zero in a part of the video
            long totalDuration; // the decode time after the last sample
            Longs runPos = new Longs();
            Longs runBytes = new Longs();
            Ints runCount = new Ints();
            Longs runDts = new Longs();
            long[] runOut;
            boolean cut;
            long cutEdit; // after a cut: the first sample's composition offset, shown at zero

            Track(FileChannel in) {
                this.in = in;
            }

            int count() {
                return sizes.size;
            }

            /** Each sample's presentation time, with the edit applied (zero is where the stream shows). */
            long[] presentation() throws Unsupported {
                if (editEntries > 1 || mediaTime < 0) {
                    throw new Unsupported("edit list with " + editEntries + " entries");
                }
                long[] times = new long[count()];
                long dts = firstDts;
                for (int i = 0; i < times.length; i++) {
                    times[i] = dts + ctos.get(i) - mediaTime;
                    dts += durations.get(i);
                }
                return times;
            }

            boolean sync(int i) {
                return Arrays.binarySearch(nonSync.values, 0, nonSync.size, i + 1) < 0;
            }

            /** Keeps samples {@code from..to-1} (decode order), the runs split at the cut, and starts them at zero. */
            void keep(int from, int to) {
                Ints keptSizes = new Ints();
                Ints keptDurations = new Ints();
                Ints keptCtos = new Ints();
                Ints keptNonSync = new Ints();
                long[] dts = new long[count() + 1];
                for (int i = 0; i < count(); i++) {
                    dts[i + 1] = dts[i] + durations.get(i);
                }
                for (int i = from; i < to; i++) {
                    keptSizes.add(sizes.get(i));
                    keptDurations.add(durations.get(i));
                    keptCtos.add(ctos.get(i));
                    if (!sync(i)) {
                        keptNonSync.add(i - from + 1);
                    }
                }
                Longs pos = new Longs();
                Longs bytes = new Longs();
                Ints counts = new Ints();
                Longs runStart = new Longs();
                int first = 0;
                for (int r = 0; r < runPos.size; r++) {
                    int last = first + runCount.get(r);
                    int a = Math.max(from, first);
                    int b = Math.min(to, last);
                    if (a < b) {
                        long p = runPos.get(r);
                        for (int i = first; i < a; i++) {
                            p += sizes.get(i);
                        }
                        long length = 0;
                        for (int i = a; i < b; i++) {
                            length += sizes.get(i);
                        }
                        pos.add(p);
                        bytes.add(length);
                        counts.add(b - a);
                        runStart.add(dts[a] - dts[from]);
                    }
                    first = last;
                }
                cutEdit = ctos.get(from);
                sizes = keptSizes;
                durations = keptDurations;
                ctos = keptCtos;
                nonSync = keptNonSync;
                runPos = pos;
                runBytes = bytes;
                runCount = counts;
                runDts = runStart;
                firstDts = 0;
                totalDuration = dts[to] - dts[from];
                cut = true;
            }
        }

        static long join(@Nullable FileChannel video, @Nullable FileChannel audio, long clipStartUs, long clipEndUs,
                         FileChannel out, Progress progress) throws IOException {
            Track v = video != null ? parse(video) : null;
            Track a = audio != null ? parse(audio) : null;
            if (v == null && a == null) {
                throw new Unsupported("no parts");
            }
            if (clipEndUs > clipStartUs) {
                cut(v, a, clipStartUs, clipEndUs);
            } else if ((v != null && v.firstDts != 0) || (a != null && a.firstDts != 0)) {
                throw new Unsupported("part does not start at zero");
            }
            List<Track> tracks = new ArrayList<>();
            if (v != null) {
                tracks.add(v);
            }
            if (a != null) {
                tracks.add(a);
            }

            // Runs in time order across tracks: the file interleaves them as YouTube cut them.
            List<int[]> order = new ArrayList<>();
            int[] next = new int[tracks.size()];
            long dataBytes = 0;
            while (true) {
                int pick = -1;
                for (int i = 0; i < tracks.size(); i++) {
                    Track t = tracks.get(i);
                    if (next[i] >= t.runPos.size) {
                        continue;
                    }
                    if (pick < 0) {
                        pick = i;
                        continue;
                    }
                    Track p = tracks.get(pick);
                    // a.dts / a.ts < b.dts / b.ts without division
                    if (t.runDts.get(next[i]) * p.timescale < p.runDts.get(next[pick]) * t.timescale) {
                        pick = i;
                    }
                }
                if (pick < 0) {
                    break;
                }
                Track t = tracks.get(pick);
                dataBytes += t.runBytes.get(next[pick]);
                order.add(new int[] {pick, next[pick]++});
            }

            byte[] ftyp = ftyp();
            boolean co64 = false;
            byte[] moov = moov(tracks, order, 0, co64);
            long header = ftyp.length + moov.length + 16L;
            if (header + dataBytes > 0xFFFFFFFFL) {
                co64 = true;
                header = ftyp.length + moov(tracks, order, 0, true).length + 16L;
            }
            moov = moov(tracks, order, header, co64);

            Buf head = new Buf();
            head.write(ftyp, 0, ftyp.length);
            head.write(moov, 0, moov.length);
            head.u32(1).u32(MDAT).u64(16 + dataBytes);
            writeFully(out, head.toByteArray());

            long copied = 0;
            for (int[] item : order) {
                Track t = tracks.get(item[0]);
                long bytes = t.runBytes.get(item[1]);
                copy(t.in, t.runPos.get(item[1]), bytes, out);
                copied += bytes;
                if (!progress.onCopied(copied, dataBytes)) {
                    throw new StreamFetcher.CancelledException();
                }
            }

            long durationMs = 0;
            for (Track t : tracks) {
                durationMs = Math.max(durationMs, t.totalDuration * 1000 / t.timescale);
            }
            return durationMs;
        }

        /**
         * The video from the keyframe at or before the start to the first one at or after the end,
         * the audio from the sample nearest where that keyframe shows to where the video stops.
         */
        static void cut(@Nullable Track v, @Nullable Track a, long startUs, long endUs) throws Unsupported {
            long baseUs = startUs;
            long stopUs = endUs; // audio alone: the part's end
            if (v != null) {
                long[] times = v.presentation();
                long start = startUs * v.timescale / 1_000_000;
                long end = endUs * v.timescale / 1_000_000;
                int n = times.length;
                int from = -1;
                for (int i = 0; i < n; i++) {
                    if (v.sync(i) && (from < 0 || times[i] <= start)) {
                        from = i;
                    }
                }
                if (from < 0) {
                    throw new Unsupported("no keyframe");
                }
                int to = n;
                for (int i = from + 1; i < n; i++) {
                    if (v.sync(i) && times[i] >= end) {
                        to = i;
                        break;
                    }
                }
                for (int i = from; i < to; i++) {
                    if (times[i] < times[from]) {
                        throw new Unsupported("a frame shows before the keyframe it starts on");
                    }
                }
                v.keep(from, to);
                baseUs = times[from] * 1_000_000 / v.timescale;
                // The audio stops where the video does: at the keyframe after the end, or its last frame.
                stopUs = (to < n ? times[to] : times[from] + v.totalDuration) * 1_000_000 / v.timescale;
            }
            if (a != null) {
                long[] times = a.presentation();
                long base = baseUs * a.timescale / 1_000_000;
                int n = times.length;
                int from = 0;
                while (from < n && times[from] < base) {
                    from++;
                }
                // ponytail: nearest sample, off by up to half an audio frame (~11 ms); an empty edit if that ever shows.
                if (from > 0 && (from == n || base - times[from - 1] < times[from] - base)) {
                    from--;
                }
                if (from >= n) {
                    throw new Unsupported("audio ends before the cut");
                }
                if (times[from] - base > a.durations.get(from)) {
                    throw new Unsupported("audio starts after the cut"); // the muxer keeps the gap
                }
                long stop = stopUs * a.timescale / 1_000_000;
                int to = from + 1;
                while (to < n && times[to] < stop) {
                    to++;
                }
                a.keep(from, to);
            }
        }

        static Track parse(FileChannel in) throws IOException {
            Track t = new Track(in);
            long length = in.size();
            long pos = 0;
            while (pos + 8 <= length) {
                ByteBuffer h = read(in, pos, 8);
                long size = h.getInt() & 0xFFFFFFFFL;
                int type = h.getInt();
                int headerSize = 8;
                if (size == 1) {
                    size = read(in, pos + 8, 8).getLong();
                    headerSize = 16;
                } else if (size == 0) {
                    size = length - pos;
                }
                if (size < headerSize || pos + size > length) {
                    throw new Unsupported("box past the end");
                }
                if (type == MOOV || type == MOOF) {
                    if (size > MAX_HEADER_BOX) {
                        throw new Unsupported("huge header box");
                    }
                    byte[] box = new byte[(int) size];
                    read(in, pos, (int) size).get(box);
                    if (type == MOOV) {
                        parseMoov(t, box);
                    } else {
                        if (t.trak == null) {
                            throw new Unsupported("fragment before the header");
                        }
                        parseMoof(t, box, pos, length);
                    }
                }
                pos += size;
            }
            if (t.trak == null || t.count() == 0) {
                throw new Unsupported("not a fragmented part");
            }
            return t;
        }

        static void parseMoov(Track t, byte[] b) throws IOException {
            for (int o = 8; o + 8 <= b.length; o += size(b, o)) {
                int type = u32i(b, o + 4);
                if (type == MVHD) {
                    t.mvhd = Arrays.copyOfRange(b, o, o + size(b, o));
                    t.movieTimescale = u32(b, o + (b[o + 8] == 1 ? 28 : 20));
                } else if (type == TRAK) {
                    if (t.trak != null) {
                        throw new Unsupported("more than one track");
                    }
                    t.trak = Arrays.copyOfRange(b, o, o + size(b, o));
                } else if (type == MVEX) {
                    int trex = child(b, o, TREX);
                    if (trex >= 0) {
                        t.trexDuration = u32(b, trex + 20);
                        t.trexSize = u32(b, trex + 24);
                        t.trexFlags = u32(b, trex + 28);
                    }
                }
            }
            if (t.mvhd == null || t.trak == null) {
                throw new Unsupported("no movie header");
            }
            byte[] trak = t.trak;
            int tkhd = child(trak, 0, TKHD);
            int mdhd = path(trak, MDIA, MDHD);
            int stsz = path(trak, MDIA, MINF, STBL, STSZ);
            if (tkhd < 0 || mdhd < 0 || stsz < 0 || path(trak, MDIA, MINF, STBL, STSD) < 0) {
                throw new Unsupported("incomplete track header");
            }
            if (u32(trak, stsz + 16) != 0) {
                throw new Unsupported("samples in the header: not fragmented");
            }
            int elst = path(trak, EDTS, ELST);
            if (elst >= 0) {
                boolean v1 = trak[elst + 8] == 1;
                t.editEntries = u32i(trak, elst + 12);
                if (t.editEntries > 0) {
                    t.mediaTime = v1 ? u64(trak, elst + 24) : u32i(trak, elst + 20);
                }
            }
            t.trackId = u32i(trak, tkhd + (trak[tkhd + 8] == 1 ? 28 : 20));
            t.timescale = u32(trak, mdhd + (trak[mdhd + 8] == 1 ? 28 : 20));
            if (t.timescale <= 0 || t.movieTimescale <= 0) {
                throw new Unsupported("no timescale");
            }
        }

        static void parseMoof(Track t, byte[] b, long moofPos, long fileLength) throws IOException {
            for (int o = 8; o + 8 <= b.length; o += size(b, o)) {
                if (u32i(b, o + 4) != TRAF) {
                    continue;
                }
                int end = o + size(b, o);
                long base = moofPos;
                long dDuration = t.trexDuration;
                long dSize = t.trexSize;
                long dFlags = t.trexFlags;
                long nextData = -1;
                for (int c = o + 8; c + 8 <= end; c += size(b, c)) {
                    int type = u32i(b, c + 4);
                    if (type == TFHD) {
                        int flags = u32i(b, c + 8) & 0xFFFFFF;
                        if (u32i(b, c + 12) != t.trackId) {
                            throw new Unsupported("fragment of another track");
                        }
                        int p = c + 16;
                        if ((flags & 0x1) != 0) {
                            base = u64(b, p);
                            p += 8;
                        }
                        if ((flags & 0x2) != 0) {
                            p += 4;
                        }
                        if ((flags & 0x8) != 0) {
                            dDuration = u32(b, p);
                            p += 4;
                        }
                        if ((flags & 0x10) != 0) {
                            dSize = u32(b, p);
                            p += 4;
                        }
                        if ((flags & 0x20) != 0) {
                            dFlags = u32(b, p);
                        }
                        nextData = base;
                    } else if (type == TFDT) {
                        long decodeTime = b[c + 8] == 1 ? u64(b, c + 12) : u32(b, c + 12);
                        if (t.count() == 0) {
                            t.firstDts = t.totalDuration = decodeTime; // a part of the video starts mid-stream
                        }
                        long gap = decodeTime - t.totalDuration;
                        if (gap != 0) {
                            if (gap < 0) {
                                throw new Unsupported("fragment out of step: " + decodeTime + " vs " + t.totalDuration);
                            }
                            // A hole: the previous sample lasts until this fragment starts.
                            int last = t.count() - 1;
                            t.durations.set(last, (int) (t.durations.get(last) + gap));
                            t.totalDuration = decodeTime;
                        }
                    } else if (type == TRUN) {
                        if (nextData < 0) {
                            throw new Unsupported("run before its fragment header");
                        }
                        int version = b[c + 8];
                        int flags = u32i(b, c + 8) & 0xFFFFFF;
                        long count = u32(b, c + 12);
                        int p = c + 16;
                        long dataStart = nextData;
                        if ((flags & 0x1) != 0) {
                            dataStart = base + u32i(b, p);
                            p += 4;
                        }
                        long firstFlags = -1;
                        if ((flags & 0x4) != 0) {
                            firstFlags = u32(b, p);
                            p += 4;
                        }
                        int fields = Integer.bitCount(flags & 0xF00);
                        if (p + count * 4 * fields > c + size(b, c)) {
                            throw new Unsupported("run past its box");
                        }
                        long runBytes = 0;
                        long runDts = t.totalDuration;
                        for (int i = 0; i < count; i++) {
                            long duration = dDuration;
                            long size = dSize;
                            long sampleFlags = i == 0 && firstFlags >= 0 ? firstFlags : dFlags;
                            int cto = 0;
                            if ((flags & 0x100) != 0) {
                                duration = u32(b, p);
                                p += 4;
                            }
                            if ((flags & 0x200) != 0) {
                                size = u32(b, p);
                                p += 4;
                            }
                            if ((flags & 0x400) != 0) {
                                sampleFlags = u32(b, p);
                                p += 4;
                            }
                            if ((flags & 0x800) != 0) {
                                cto = u32i(b, p);
                                p += 4;
                                if (cto != 0) {
                                    t.anyCto = true;
                                }
                                if (version != 0 && cto < 0) {
                                    t.signedCto = true;
                                }
                            }
                            if (size <= 0 || size > Integer.MAX_VALUE || duration > Integer.MAX_VALUE) {
                                throw new Unsupported("odd sample");
                            }
                            t.sizes.add((int) size);
                            t.durations.add((int) duration);
                            t.ctos.add(cto);
                            if ((sampleFlags & 0x10000) != 0) {
                                t.nonSync.add(t.count());
                            }
                            runBytes += size;
                            t.totalDuration += duration;
                        }
                        if (dataStart < 0 || dataStart + runBytes > fileLength) {
                            throw new Unsupported("samples past the end");
                        }
                        if (count > 0) {
                            t.runPos.add(dataStart);
                            t.runBytes.add(runBytes);
                            t.runCount.add((int) count);
                            t.runDts.add(runDts);
                        }
                        nextData = dataStart + runBytes;
                    }
                }
            }
        }

        static byte[] ftyp() {
            Buf b = new Buf();
            int at = b.open(FTYP);
            b.u32(ISOM).u32(0x200).u32(ISOM).u32(ISO2).u32(MP41);
            b.close(at);
            return b.toByteArray();
        }

        static byte[] moov(List<Track> tracks, List<int[]> order, long dataStart, boolean co64) throws IOException {
            // Output offset of every run, in the order they are written.
            long offset = dataStart;
            for (Track t : tracks) {
                t.runOut = new long[t.runPos.size];
            }
            for (int[] item : order) {
                Track t = tracks.get(item[0]);
                t.runOut[item[1]] = offset;
                offset += t.runBytes.get(item[1]);
            }

            Track first = tracks.get(0);
            long movieTimescale = first.movieTimescale;
            long movieDuration = 0;
            Buf traks = new Buf();
            for (int i = 0; i < tracks.size(); i++) {
                movieDuration = Math.max(movieDuration, trak(traks, tracks.get(i), i + 1, movieTimescale, co64));
            }

            Buf b = new Buf();
            int at = b.open(MOOV);
            byte[] mvhd = first.mvhd.clone();
            putDuration(mvhd, mvhd[8] == 1 ? 32 : 24, mvhd[8] == 1, movieDuration);
            putU32(mvhd, mvhd.length - 4, tracks.size() + 1); // next_track_ID
            b.write(mvhd, 0, mvhd.length);
            byte[] t = traks.toByteArray();
            b.write(t, 0, t.length);
            b.close(at);
            return b.toByteArray();
        }

        /** @return the track's duration in the movie timescale */
        static long trak(Buf b, Track t, int trackId, long movieTimescale, boolean co64) throws IOException {
            byte[] src = t.trak;
            long duration = t.totalDuration * movieTimescale / t.timescale;
            int edts = child(src, 0, EDTS);
            byte[] edtsOut = null;
            if (t.cut) {
                // The source's edit spans the whole stream: one that shows the first kept sample at zero.
                if (t.cutEdit != 0) {
                    if (duration > 0xFFFFFFFFL || t.cutEdit < 0) {
                        throw new Unsupported("cut edit out of range");
                    }
                    Buf e = new Buf();
                    int eAt = e.open(EDTS);
                    int lAt = e.full(ELST);
                    e.u32(1).u32(duration).u32(t.cutEdit).u32(0x10000);
                    e.close(lAt);
                    e.close(eAt);
                    edtsOut = e.toByteArray();
                }
            } else if (edts >= 0) {
                edtsOut = Arrays.copyOfRange(src, edts, edts + size(src, edts));
                int elst = child(edtsOut, 0, ELST);
                if (elst >= 0) {
                    boolean v1 = edtsOut[elst + 8] == 1;
                    long entries = u32(edtsOut, elst + 12);
                    long sum = 0;
                    int p = elst + 16;
                    for (int i = 0; i < entries; i++) {
                        long segment = v1 ? u64(edtsOut, p) : u32(edtsOut, p);
                        segment = segment * movieTimescale / t.movieTimescale;
                        putDuration(edtsOut, p, v1, segment);
                        sum += segment;
                        p += v1 ? 20 : 12;
                    }
                    if (sum > 0) {
                        duration = sum;
                    }
                }
            }

            int at = b.open(TRAK);
            for (int o = 8; o + 8 <= src.length; o += size(src, o)) {
                int type = u32i(src, o + 4);
                if (type == TKHD) {
                    byte[] tkhd = Arrays.copyOfRange(src, o, o + size(src, o));
                    boolean v1 = tkhd[8] == 1;
                    putU32(tkhd, v1 ? 28 : 20, trackId);
                    putDuration(tkhd, v1 ? 36 : 28, v1, duration);
                    b.write(tkhd, 0, tkhd.length);
                    if (t.cut && edtsOut != null) {
                        b.write(edtsOut, 0, edtsOut.length);
                    }
                } else if (type == EDTS) {
                    if (!t.cut) {
                        b.write(edtsOut, 0, edtsOut.length);
                    }
                } else if (type == MDIA) {
                    mdia(b, t, src, o, co64);
                } else {
                    b.write(src, o, size(src, o));
                }
            }
            b.close(at);
            return duration;
        }

        static void mdia(Buf b, Track t, byte[] src, int mdia, boolean co64) throws IOException {
            int at = b.open(MDIA);
            int end = mdia + size(src, mdia);
            for (int o = mdia + 8; o + 8 <= end; o += size(src, o)) {
                int type = u32i(src, o + 4);
                if (type == MDHD) {
                    byte[] mdhd = Arrays.copyOfRange(src, o, o + size(src, o));
                    boolean v1 = mdhd[8] == 1;
                    putDuration(mdhd, v1 ? 32 : 24, v1, t.totalDuration);
                    b.write(mdhd, 0, mdhd.length);
                } else if (type == MINF) {
                    int minfAt = b.open(MINF);
                    int minfEnd = o + size(src, o);
                    for (int m = o + 8; m + 8 <= minfEnd; m += size(src, m)) {
                        if (u32i(src, m + 4) == STBL) {
                            stbl(b, t, src, m, co64);
                        } else {
                            b.write(src, m, size(src, m));
                        }
                    }
                    b.close(minfAt);
                } else {
                    b.write(src, o, size(src, o));
                }
            }
            b.close(at);
        }

        static void stbl(Buf b, Track t, byte[] src, int stbl, boolean co64) throws IOException {
            int at = b.open(STBL);
            int stsd = child(src, stbl, STSD);
            b.write(src, stsd, size(src, stsd));
            int n = t.count();

            int box = b.full(STTS);
            Buf entries = new Buf();
            int runs = 0;
            for (int i = 0; i < n; ) {
                int j = i;
                while (j < n && t.durations.get(j) == t.durations.get(i)) {
                    j++;
                }
                entries.u32(j - i).u32(t.durations.get(i));
                runs++;
                i = j;
            }
            b.u32(runs).append(entries);
            b.close(box);

            if (t.anyCto) {
                box = b.full(CTTS, t.signedCto ? 1 : 0);
                entries = new Buf();
                runs = 0;
                for (int i = 0; i < n; ) {
                    int j = i;
                    while (j < n && t.ctos.get(j) == t.ctos.get(i)) {
                        j++;
                    }
                    entries.u32(j - i).u32(t.ctos.get(i));
                    runs++;
                    i = j;
                }
                b.u32(runs).append(entries);
                b.close(box);
            }

            if (t.nonSync.size > 0) {
                box = b.full(STSS);
                b.u32(n - t.nonSync.size);
                int k = 0;
                for (int i = 1; i <= n; i++) {
                    if (k < t.nonSync.size && t.nonSync.get(k) == i) {
                        k++;
                    } else {
                        b.u32(i);
                    }
                }
                b.close(box);
            }

            box = b.full(STSZ);
            boolean same = true;
            for (int i = 1; i < n && same; i++) {
                same = t.sizes.get(i) == t.sizes.get(0);
            }
            if (same) {
                b.u32(t.sizes.get(0)).u32(n);
            } else {
                b.u32(0).u32(n);
                for (int i = 0; i < n; i++) {
                    b.u32(t.sizes.get(i));
                }
            }
            b.close(box);

            box = b.full(STSC);
            entries = new Buf();
            runs = 0;
            for (int i = 0; i < t.runCount.size; i++) {
                if (i == 0 || t.runCount.get(i) != t.runCount.get(i - 1)) {
                    entries.u32(i + 1).u32(t.runCount.get(i)).u32(1);
                    runs++;
                }
            }
            b.u32(runs).append(entries);
            b.close(box);

            box = b.full(co64 ? CO64 : STCO);
            b.u32(t.runOut.length);
            for (long offset : t.runOut) {
                if (co64) {
                    b.u64(offset);
                } else {
                    b.u32(offset);
                }
            }
            b.close(box);
            b.close(at);
        }

        static int size(byte[] b, int o) throws Unsupported {
            long size = u32(b, o);
            if (size < 8 || o + size > b.length) {
                throw new Unsupported("bad box size");
            }
            return (int) size;
        }

        /** Offset of the first {@code type} box inside the box at {@code box}; -1 when absent. */
        static int child(byte[] b, int box, int type) throws Unsupported {
            int end = box + size(b, box);
            for (int o = box + 8; o + 8 <= end; o += size(b, o)) {
                if (u32i(b, o + 4) == type) {
                    return o;
                }
            }
            return -1;
        }

        static int path(byte[] b, int... types) throws Unsupported {
            int box = 0;
            for (int type : types) {
                box = child(b, box, type);
                if (box < 0) {
                    return -1;
                }
            }
            return box;
        }

        static void putDuration(byte[] b, int o, boolean v1, long value) throws Unsupported {
            if (v1) {
                putU32(b, o, value >>> 32);
                putU32(b, o + 4, value);
            } else {
                if (value > 0xFFFFFFFFL) {
                    throw new Unsupported("duration overflows a version 0 box");
                }
                putU32(b, o, value);
            }
        }
    }

    // --------------------------------------------------------------------------------------- WebM

    private static final class Webm {
        static final int EBML = 0x1A45DFA3, SEGMENT = 0x18538067, SEEK_HEAD = 0x114D9B74, SEEK = 0x4DBB,
                SEEK_ID = 0x53AB, SEEK_POS = 0x53AC, INFO = 0x1549A966, TIMECODE_SCALE = 0x2AD7B1, DURATION = 0x4489,
                TRACKS = 0x1654AE6B, TRACK_ENTRY = 0xAE, TRACK_NUMBER = 0xD7, CUES = 0x1C53BB6B, CUE_POINT = 0xBB,
                CUE_TIME = 0xB3, CUE_TRACK_POSITIONS = 0xB7, CUE_TRACK = 0xF7, CUE_CLUSTER_POSITION = 0xF1,
                CLUSTER = 0x1F43B675, TIMECODE = 0xE7, SIMPLE_BLOCK = 0xA3, BLOCK_GROUP = 0xA0, BLOCK = 0xA1;
        static final int MAX_HEADER_ELEMENT = 16 << 20;

        /** One part: its header elements and every block, by cluster. */
        static final class Part {
            final Reader in;
            long ebmlEnd;
            byte[] info;
            byte[] trackEntry;
            long timecodeScale = 1_000_000;
            double durationTicks;
            int durationAt = -1; // where Info's Duration value sits in info, and its length
            int durationLength;
            final Longs clusterTime = new Longs();
            final Ints clusterFirst = new Ints();
            final Ints clusterKey = new Ints(); // 1 when the cluster starts on a keyframe
            final Longs blockStart = new Longs();
            final Longs blockLength = new Longs();
            final Longs blockTime = new Longs();
            final Longs blockPatch = new Longs(); // the track number byte of the block's payload

            Part(FileChannel channel) throws IOException {
                in = new Reader(channel);
            }

            int blocks() {
                return blockStart.size;
            }
        }

        static long join(@Nullable FileChannel video, @Nullable FileChannel audio, long clipStartUs, long clipEndUs,
                         FileChannel out, Progress progress) throws IOException {
            if (video == null) {
                throw new Unsupported("webm without video");
            }
            Part v = parse(video);
            Part a = audio != null ? parse(audio) : null;
            if (v.clusterTime.size == 0) {
                throw new Unsupported("no clusters");
            }
            if (a != null && a.timecodeScale != v.timecodeScale) {
                throw new Unsupported("timecode scales differ");
            }

            // A cut keeps the clusters from the one starting on a keyframe at or before the start
            // (YouTube starts one on every keyframe) to the first at or after the end, shifted by base.
            int clusters = v.clusterTime.size;
            int c0 = 0;
            int c1 = clusters;
            long base = 0;
            long stop = Long.MAX_VALUE; // audio from here on is dropped: where the cut video ends
            if (clipEndUs > clipStartUs) {
                long start = clipStartUs * 1000 / v.timecodeScale;
                long end = clipEndUs * 1000 / v.timecodeScale;
                c0 = -1;
                for (int c = 0; c < clusters; c++) {
                    if (v.clusterKey.get(c) == 1 && (c0 < 0 || v.clusterTime.get(c) <= start)) {
                        c0 = c;
                    }
                }
                if (c0 < 0) {
                    throw new Unsupported("no cluster starts on a keyframe");
                }
                for (int c = c0 + 1; c < clusters; c++) {
                    if (v.clusterTime.get(c) >= end) {
                        c1 = c;
                        break;
                    }
                }
                base = v.clusterTime.get(c0);
                if (c1 < clusters) {
                    stop = v.clusterTime.get(c1);
                } else {
                    // The end of the last frame: its time plus the spacing before it (VP9 has no B-frames).
                    long last = Long.MIN_VALUE;
                    long previous = Long.MIN_VALUE;
                    for (int i = v.clusterFirst.get(clusters - 1); i < v.blocks(); i++) {
                        long time = v.blockTime.get(i);
                        if (time > last) {
                            previous = last;
                            last = time;
                        } else if (time > previous && time < last) {
                            previous = time;
                        }
                    }
                    stop = previous != Long.MIN_VALUE ? 2 * last - previous : last + 1;
                }
            }

            // Output clusters are the video's: each keeps its timecode and blocks, and takes the
            // audio blocks up to the next one's, re-timed against it. Merged by time inside.
            int[] audioFirst = new int[clusters + 1];
            long[] clusterSize = new long[clusters];
            int ab = 0;
            while (a != null && ab < a.blocks() && a.blockTime.get(ab) < base) {
                ab++;
            }
            for (int c = c0; c < c1; c++) {
                audioFirst[c] = ab;
                long tc = v.clusterTime.get(c);
                long nextTc = c + 1 < c1 ? v.clusterTime.get(c + 1) : stop;
                long size = 10; // Timecode, 8-byte value
                int vEnd = c + 1 < clusters ? v.clusterFirst.get(c + 1) : v.blocks();
                for (int i = v.clusterFirst.get(c); i < vEnd; i++) {
                    size += v.blockLength.get(i);
                }
                if (a != null) {
                    while (ab < a.blocks() && a.blockTime.get(ab) < nextTc) {
                        long relative = a.blockTime.get(ab) - tc;
                        if (relative < Short.MIN_VALUE || relative > Short.MAX_VALUE) {
                            throw new Unsupported("audio block too far from its cluster");
                        }
                        size += a.blockLength.get(ab);
                        ab++;
                    }
                }
                clusterSize[c] = size;
            }
            audioFirst[c1] = ab;

            byte[] info = v.info;
            double ticks = Math.max(v.durationTicks, a != null ? a.durationTicks : 0);
            if (clipEndUs > clipStartUs) {
                ticks = Math.min(ticks, stop) - base;
                if (v.durationAt >= 0) {
                    info = info.clone();
                    long bits = v.durationLength == 4 ? Float.floatToIntBits((float) ticks) : Double.doubleToLongBits(ticks);
                    for (int i = 0; i < v.durationLength; i++) {
                        info[v.durationAt + i] = (byte) (bits >>> (8 * (v.durationLength - 1 - i)));
                    }
                }
            }

            byte[] tracks = tracks(v, a);
            int cuePointBytes = 27;
            long cuesBytes = 12 + (long) cuePointBytes * (c1 - c0);
            int seekHeadBytes = 68;
            long infoPos = seekHeadBytes;
            long tracksPos = infoPos + info.length;
            long cuesPos = tracksPos + tracks.length;
            long clusterPos = cuesPos + cuesBytes;
            long segmentBytes = clusterPos;
            for (int c = c0; c < c1; c++) {
                segmentBytes += 12 + clusterSize[c];
            }

            Buf head = new Buf();
            byte[] ebml = v.in.bytes(0, (int) v.ebmlEnd);
            head.write(ebml, 0, ebml.length);
            head.id(SEGMENT).size8(segmentBytes);
            head.id(SEEK_HEAD).size1(63);
            seek(head, INFO, infoPos);
            seek(head, TRACKS, tracksPos);
            seek(head, CUES, cuesPos);
            head.write(info, 0, info.length);
            head.write(tracks, 0, tracks.length);
            head.id(CUES).size8(cuesBytes - 12);
            long position = clusterPos;
            for (int c = c0; c < c1; c++) {
                head.id(CUE_POINT).size1(25);
                head.id(CUE_TIME).size1(8).u64(v.clusterTime.get(c) - base);
                head.id(CUE_TRACK_POSITIONS).size1(13);
                head.id(CUE_TRACK).size1(1).u8(1);
                head.id(CUE_CLUSTER_POSITION).size1(8).u64(position);
                position += 12 + clusterSize[c];
            }
            writeFully(out, head.toByteArray());

            long total = segmentBytes - clusterPos;
            long copied = 0;
            Buf pending = new Buf();
            for (int c = c0; c < c1; c++) {
                long tc = v.clusterTime.get(c);
                pending.reset();
                pending.id(CLUSTER).size8(clusterSize[c]);
                pending.id(TIMECODE).size1(8).u64(tc - base);
                int vi = v.clusterFirst.get(c);
                int vEnd = c + 1 < clusters ? v.clusterFirst.get(c + 1) : v.blocks();
                int ai = audioFirst[c];
                int aEnd = audioFirst[c + 1];
                while (vi < vEnd || ai < aEnd) {
                    boolean takeAudio = ai < aEnd && (vi >= vEnd || a.blockTime.get(ai) < v.blockTime.get(vi));
                    if (takeAudio) {
                        byte[] block = a.in.bytes(a.blockStart.get(ai), (int) a.blockLength.get(ai));
                        int patch = (int) (a.blockPatch.get(ai) - a.blockStart.get(ai));
                        long relative = a.blockTime.get(ai) - tc;
                        block[patch] = (byte) 0x82; // track 2
                        block[patch + 1] = (byte) (relative >> 8);
                        block[patch + 2] = (byte) relative;
                        pending.write(block, 0, block.length);
                        ai++;
                    } else {
                        // Consecutive video blocks of one cluster sit back to back: one copy.
                        long start = v.blockStart.get(vi);
                        long end = start + v.blockLength.get(vi);
                        vi++;
                        while (vi < vEnd && v.blockStart.get(vi) == end
                                && (ai >= aEnd || a.blockTime.get(ai) >= v.blockTime.get(vi))) {
                            end += v.blockLength.get(vi);
                            vi++;
                        }
                        if (pending.size() > 0) {
                            copied += pending.size();
                            writeFully(out, pending.toByteArray());
                            pending.reset();
                        }
                        copy(v.in.channel, start, end - start, out);
                        copied += end - start;
                    }
                }
                if (pending.size() > 0) {
                    copied += pending.size();
                    writeFully(out, pending.toByteArray());
                    pending.reset();
                }
                if (!progress.onCopied(copied, total)) {
                    throw new StreamFetcher.CancelledException();
                }
            }

            return (long) (ticks * v.timecodeScale / 1_000_000);
        }

        static byte[] tracks(Part v, @Nullable Part a) throws IOException {
            Buf b = new Buf();
            long size = v.trackEntry.length + (a != null ? a.trackEntry.length : 0);
            b.id(TRACKS).size8(size);
            b.write(v.trackEntry, 0, v.trackEntry.length);
            if (a != null) {
                byte[] entry = a.trackEntry.clone();
                // TrackEntry header, then its children: find TrackNumber (1 byte, value 1) and make it 2.
                Reader r = new Reader(entry);
                long p = r.headerEnd(0);
                boolean done = false;
                while (p < entry.length) {
                    long id = r.id(p);
                    long dataAt = r.headerEnd(p);
                    long dataSize = r.size(p + r.idLength(p));
                    if (id == TRACK_NUMBER) {
                        if (dataSize != 1 || entry[(int) dataAt] != 1) {
                            throw new Unsupported("audio track number");
                        }
                        entry[(int) dataAt] = 2;
                        done = true;
                        break;
                    }
                    p = dataAt + dataSize;
                }
                if (!done) {
                    throw new Unsupported("no audio track number");
                }
                b.write(entry, 0, entry.length);
            }
            return b.toByteArray();
        }

        static void seek(Buf b, int id, long position) {
            b.id(SEEK).size1(18);
            b.id(SEEK_ID).size1(4).u32(id);
            b.id(SEEK_POS).size1(8).u64(position);
        }

        static Part parse(FileChannel channel) throws IOException {
            Part part = new Part(channel);
            Reader r = part.in;
            long length = channel.size();
            if (r.id(0) != EBML) {
                throw new Unsupported("not EBML");
            }
            long p = r.headerEnd(0) + r.size(r.idLength(0));
            part.ebmlEnd = p;
            if (r.id(p) != SEGMENT) {
                throw new Unsupported("no segment");
            }
            long segmentSize = r.size(p + r.idLength(p));
            long q = r.headerEnd(p);
            long end = segmentSize < 0 ? length : q + segmentSize;
            if (end > length) {
                throw new Unsupported("segment past the end");
            }
            while (q < end) {
                long id = r.id(q);
                long dataAt = r.headerEnd(q);
                long dataSize = r.size(q + r.idLength(q));
                if (dataSize < 0 || dataAt + dataSize > end) {
                    throw new Unsupported("element of unknown size");
                }
                if (id == INFO || id == TRACKS) {
                    if (dataSize > MAX_HEADER_ELEMENT) {
                        throw new Unsupported("huge header element");
                    }
                    if (id == INFO) {
                        part.info = r.bytes(q, (int) (dataAt + dataSize - q));
                        info(part, q, dataAt, dataAt + dataSize);
                    } else {
                        tracks(part, dataAt, dataAt + dataSize);
                    }
                } else if (id == CLUSTER) {
                    cluster(part, dataAt, dataAt + dataSize);
                }
                q = dataAt + dataSize;
            }
            if (part.info == null || part.trackEntry == null) {
                throw new Unsupported("no track header");
            }
            return part;
        }

        static void info(Part part, long infoStart, long from, long to) throws IOException {
            Reader r = part.in;
            for (long p = from; p < to; ) {
                long id = r.id(p);
                long dataAt = r.headerEnd(p);
                long dataSize = r.size(p + r.idLength(p));
                if (id == TIMECODE_SCALE) {
                    part.timecodeScale = r.uint(dataAt, (int) dataSize);
                } else if (id == DURATION) {
                    long bits = r.uint(dataAt, (int) dataSize);
                    part.durationTicks = dataSize == 4 ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
                    part.durationAt = (int) (dataAt - infoStart);
                    part.durationLength = (int) dataSize;
                }
                p = dataAt + dataSize;
            }
        }

        static void tracks(Part part, long from, long to) throws IOException {
            Reader r = part.in;
            for (long p = from; p < to; ) {
                long id = r.id(p);
                long dataAt = r.headerEnd(p);
                long dataSize = r.size(p + r.idLength(p));
                if (id == TRACK_ENTRY) {
                    if (part.trackEntry != null) {
                        throw new Unsupported("more than one track");
                    }
                    part.trackEntry = r.bytes(p, (int) (dataAt + dataSize - p));
                }
                p = dataAt + dataSize;
            }
        }

        static void cluster(Part part, long from, long to) throws IOException {
            Reader r = part.in;
            long tc = -1;
            int key = -1;
            for (long p = from; p < to; ) {
                long id = r.id(p);
                long dataAt = r.headerEnd(p);
                long dataSize = r.size(p + r.idLength(p));
                if (dataSize < 0 || dataAt + dataSize > to) {
                    throw new Unsupported("block of unknown size");
                }
                long payload = -1;
                if (id == TIMECODE) {
                    tc = r.uint(dataAt, (int) dataSize);
                    part.clusterTime.add(tc);
                    part.clusterFirst.add(part.blocks());
                } else if (id == SIMPLE_BLOCK) {
                    payload = dataAt;
                } else if (id == BLOCK_GROUP) {
                    for (long g = dataAt; g < dataAt + dataSize; ) {
                        long gid = r.id(g);
                        long gData = r.headerEnd(g);
                        if (gid == BLOCK) {
                            payload = gData;
                        }
                        g = gData + r.size(g + r.idLength(g));
                    }
                    if (payload < 0) {
                        throw new Unsupported("block group without a block");
                    }
                }
                if (payload >= 0) {
                    if (tc < 0) {
                        throw new Unsupported("block before its cluster timecode");
                    }
                    if (r.at(payload) != 0x81) {
                        throw new Unsupported("block of another track");
                    }
                    short relative = (short) ((r.at(payload + 1) << 8) | r.at(payload + 2));
                    if (key < 0) {
                        key = id == SIMPLE_BLOCK && (r.at(payload + 3) & 0x80) != 0 && relative >= 0 ? 1 : 0;
                    }
                    part.blockStart.add(p);
                    part.blockLength.add(dataAt + dataSize - p);
                    part.blockTime.add(tc + relative);
                    part.blockPatch.add(payload);
                }
                p = dataAt + dataSize;
            }
            if (tc < 0) {
                throw new Unsupported("cluster without a timecode");
            }
            part.clusterKey.add(Math.max(key, 0));
        }
    }

    /** EBML reads over a file (64 KiB window) or a byte array. */
    private static final class Reader {
        final FileChannel channel;
        final byte[] array;
        private final ByteBuffer window = ByteBuffer.allocate(64 * 1024);
        private long windowPos = -1;
        private int windowLength;

        Reader(FileChannel channel) {
            this.channel = channel;
            this.array = null;
        }

        Reader(byte[] array) {
            this.channel = null;
            this.array = array;
        }

        int at(long pos) throws IOException {
            if (array != null) {
                if (pos < 0 || pos >= array.length) {
                    throw new Unsupported("element past the end");
                }
                return array[(int) pos] & 0xFF;
            }
            if (pos < windowPos || pos >= windowPos + windowLength) {
                window.clear();
                int n = 0;
                while (window.hasRemaining()) {
                    int read = channel.read(window, pos + n);
                    if (read < 0) {
                        break;
                    }
                    n += read;
                }
                if (n == 0) {
                    throw new Unsupported("element past the end");
                }
                windowPos = pos;
                windowLength = n;
            }
            return window.get((int) (pos - windowPos)) & 0xFF;
        }

        int idLength(long pos) throws IOException {
            int first = at(pos);
            int length = Integer.numberOfLeadingZeros(first) - 23;
            if (first == 0 || length > 4) {
                throw new Unsupported("bad element id");
            }
            return length;
        }

        long id(long pos) throws IOException {
            int length = idLength(pos);
            long id = 0;
            for (int i = 0; i < length; i++) {
                id = (id << 8) | at(pos + i);
            }
            return id;
        }

        int sizeLength(long pos) throws IOException {
            int first = at(pos);
            if (first == 0) {
                throw new Unsupported("bad element size");
            }
            return Integer.numberOfLeadingZeros(first) - 23;
        }

        /** The element size at {@code pos}; -1 when unknown (all ones). */
        long size(long pos) throws IOException {
            int length = sizeLength(pos);
            long value = at(pos) & (0xFF >> length);
            boolean allOnes = value == (0xFF >> length);
            for (int i = 1; i < length; i++) {
                int b = at(pos + i);
                allOnes &= b == 0xFF;
                value = (value << 8) | b;
            }
            return allOnes ? -1 : value;
        }

        long headerEnd(long pos) throws IOException {
            long sizeAt = pos + idLength(pos);
            return sizeAt + sizeLength(sizeAt);
        }

        long uint(long pos, int length) throws IOException {
            if (length > 8) {
                throw new Unsupported("integer too long");
            }
            long value = 0;
            for (int i = 0; i < length; i++) {
                value = (value << 8) | at(pos + i);
            }
            return value;
        }

        byte[] bytes(long pos, int length) throws IOException {
            if (array != null) {
                return Arrays.copyOfRange(array, (int) pos, (int) pos + length);
            }
            byte[] out = new byte[length];
            read(channel, pos, length).get(out);
            return out;
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private static final class Buf extends ByteArrayOutputStream {
        Buf u8(int v) {
            write(v);
            return this;
        }

        Buf u32(long v) {
            write((int) (v >>> 24));
            write((int) (v >>> 16));
            write((int) (v >>> 8));
            write((int) v);
            return this;
        }

        Buf u64(long v) {
            return u32(v >>> 32).u32(v);
        }

        Buf append(Buf other) {
            write(other.buf, 0, other.count);
            return this;
        }

        /** Starts a box; {@link #close} fills its size in. */
        int open(int type) {
            int at = count;
            u32(0).u32(type);
            return at;
        }

        int full(int type) {
            return full(type, 0);
        }

        int full(int type, int version) {
            int at = open(type);
            u32((long) version << 24);
            return at;
        }

        void close(int at) {
            putU32(buf, at, count - at);
        }

        Buf id(int id) {
            if ((id & 0xFF000000) != 0) {
                write(id >>> 24);
            }
            if ((id & 0xFFFF0000) != 0) {
                write(id >>> 16);
            }
            if ((id & 0xFFFFFF00) != 0) {
                write(id >>> 8);
            }
            write(id);
            return this;
        }

        Buf size1(int size) {
            write(0x80 | size);
            return this;
        }

        Buf size8(long size) {
            write(0x01);
            for (int shift = 48; shift >= 0; shift -= 8) {
                write((int) (size >>> shift));
            }
            return this;
        }
    }

    private static final class Ints {
        int[] values = new int[256];
        int size;

        void add(int v) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = v;
        }

        int get(int i) {
            return values[i];
        }

        void set(int i, int v) {
            values[i] = v;
        }
    }

    private static final class Longs {
        long[] values = new long[256];
        int size;

        void add(long v) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = v;
        }

        long get(int i) {
            return values[i];
        }
    }

    static ByteBuffer read(FileChannel in, long pos, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length);
        while (b.hasRemaining()) {
            if (in.read(b, pos + b.position()) < 0) {
                throw new Unsupported("file shorter than its boxes");
            }
        }
        b.flip();
        return b;
    }

    static void writeFully(FileChannel out, byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) {
            out.write(b);
        }
    }

    static void copy(FileChannel in, long pos, long length, FileChannel out) throws IOException {
        while (length > 0) {
            long n = in.transferTo(pos, length, out);
            if (n <= 0) {
                // transferTo may move nothing on some channels: one buffered step instead.
                ByteBuffer b = read(in, pos, (int) Math.min(length, 1 << 20));
                n = b.remaining();
                while (b.hasRemaining()) {
                    out.write(b);
                }
            }
            pos += n;
            length -= n;
        }
    }

    static long u32(byte[] b, int o) {
        return u32i(b, o) & 0xFFFFFFFFL;
    }

    static int u32i(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    static long u64(byte[] b, int o) {
        return (u32(b, o) << 32) | u32(b, o + 4);
    }

    static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }
}
