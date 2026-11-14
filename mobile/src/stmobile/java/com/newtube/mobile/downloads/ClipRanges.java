package com.newtube.mobile.downloads;

import androidx.annotation.Nullable;

/**
 * Issue #241: the bytes a part of a DASH MP4 stream needs, so a part download fetches only them.
 *
 * <p>A YouTube MP4 stream is a header (ftyp + moov, the format's {@code init} range), an index (the
 * sidx box, its {@code index} range) and fragments (moof + mdat). Each fragment starts on a keyframe
 * and the sidx lists every fragment's size and duration, so the fragments covering the part are one
 * byte range. The part file is the header followed by those fragments, without the sidx (its offsets
 * would point at bytes the file no longer has); fragments carry their own decode times (tfdt), so
 * the muxer still sees the real timestamps and cuts on them.
 *
 * <p>ponytail: MP4 only. WebM (VP9 + Opus, 1440p and up) keeps its index in Cues; those parts still
 * download whole and are cut afterwards.
 */
final class ClipRanges {
    /** The header bytes to write first, then the stream's bytes {@code from..to} to append. */
    static final class Plan {
        final byte[] header;
        final long from;
        final long to;
        /** The stream time the first fetched fragment starts at. */
        final long startMs;

        Plan(byte[] header, long from, long to, long startMs) {
            this.header = header;
            this.from = from;
            this.to = to;
            this.startMs = startMs;
        }

        long fileLength() {
            return header.length + (to - from + 1);
        }
    }

    private ClipRanges() {
    }

    /** "0-740" -> {0, 740}; null when absent or not a range. */
    @Nullable
    static long[] range(@Nullable String text) {
        if (text == null) {
            return null;
        }
        int dash = text.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        try {
            long from = Long.parseLong(text.substring(0, dash).trim());
            long to = Long.parseLong(text.substring(dash + 1).trim());
            return to >= from ? new long[] {from, to} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param headerAndIndex the stream's bytes from 0 through the end of the index range
     * @param indexStart where the sidx box starts in the stream
     * @return null when the bytes hold no usable sidx (the part then downloads whole)
     */
    @Nullable
    static Plan plan(byte[] headerAndIndex, int initEnd, int indexStart, long startMs, long endMs) {
        if (indexStart < 0 || indexStart + 32 > headerAndIndex.length || initEnd >= indexStart) {
            return null;
        }
        int p = indexStart;
        long boxSize = u32(headerAndIndex, p);
        if (boxSize < 32 || indexStart + boxSize > headerAndIndex.length
                || headerAndIndex[p + 4] != 's' || headerAndIndex[p + 5] != 'i'
                || headerAndIndex[p + 6] != 'd' || headerAndIndex[p + 7] != 'x') {
            return null;
        }
        int version = headerAndIndex[p + 8] & 0xFF;
        p += 12 + 4; // size, type, version + flags, reference_ID
        long timescale = u32(headerAndIndex, p);
        p += 4;
        long time;
        long firstOffset;
        if (version == 0) {
            time = u32(headerAndIndex, p);
            firstOffset = u32(headerAndIndex, p + 4);
            p += 8;
        } else {
            time = u64(headerAndIndex, p);
            firstOffset = u64(headerAndIndex, p + 8);
            p += 16;
        }
        int count = ((headerAndIndex[p + 2] & 0xFF) << 8) | (headerAndIndex[p + 3] & 0xFF);
        p += 4;
        if (timescale <= 0 || count == 0 || p + 12L * count > indexStart + boxSize) {
            return null;
        }

        long offset = indexStart + boxSize + firstOffset;
        long from = -1;
        long to = -1;
        long firstMs = 0;
        for (int i = 0; i < count; i++, p += 12) {
            long size = u32(headerAndIndex, p) & 0x7FFFFFFFL;
            long duration = u32(headerAndIndex, p + 4);
            if ((headerAndIndex[p] & 0x80) != 0) {
                return null; // a reference to another sidx: not what YouTube sends
            }
            long segStartMs = time * 1000 / timescale;
            long segEndMs = (time + duration) * 1000 / timescale;
            if (segEndMs > startMs && segStartMs < endMs) {
                if (from < 0) {
                    from = offset;
                    firstMs = segStartMs;
                }
                to = offset + size - 1;
            }
            offset += size;
            time += duration;
        }
        if (from < 0) {
            return null;
        }
        byte[] header = new byte[initEnd + 1];
        System.arraycopy(headerAndIndex, 0, header, 0, header.length);
        return new Plan(header, from, to, firstMs);
    }

    private static long u32(byte[] b, int p) {
        return ((b[p] & 0xFFL) << 24) | ((b[p + 1] & 0xFFL) << 16) | ((b[p + 2] & 0xFFL) << 8) | (b[p + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int p) {
        return (u32(b, p) << 32) | u32(b, p + 4);
    }
}
