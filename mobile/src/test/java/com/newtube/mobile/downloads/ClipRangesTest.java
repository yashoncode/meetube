package com.newtube.mobile.downloads;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ClipRangesTest {
    /** A 10-byte header, then a v0 sidx: three 2 s fragments of 100, 200 and 300 bytes. */
    private static byte[] stream() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        out.writeInt(32 + 3 * 12);
        out.writeBytes("sidx");
        out.writeInt(0); // version 0, flags
        out.writeInt(1); // reference_ID
        out.writeInt(1000); // timescale
        out.writeInt(0); // earliest presentation time
        out.writeInt(0); // first offset
        out.writeShort(0);
        out.writeShort(3);
        for (int size : new int[] {100, 200, 300}) {
            out.writeInt(size);
            out.writeInt(2000);
            out.writeInt(0x90000000);
        }
        return bytes.toByteArray();
    }

    @Test
    public void takesTheFragmentsThatCoverThePart() throws IOException {
        byte[] stream = stream();
        int fragments = stream.length; // the first fragment starts right after the sidx

        ClipRanges.Plan plan = ClipRanges.plan(stream, 9, 10, 2500, 3500);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, plan.header);
        assertEquals(fragments + 100, plan.from);
        assertEquals(fragments + 299, plan.to);
        assertEquals(2000, plan.startMs);

        plan = ClipRanges.plan(stream, 9, 10, 1000, 4500);
        assertEquals(fragments, plan.from);
        assertEquals(fragments + 599, plan.to);
        assertEquals(10 + 600, plan.fileLength());
    }

    @Test
    public void noPlanPastTheEndOrWithoutAnIndex() throws IOException {
        assertNull(ClipRanges.plan(stream(), 9, 10, 7000, 9000));
        assertNull(ClipRanges.plan(new byte[64], 9, 10, 0, 1000));
        assertNull(ClipRanges.range("x"));
        assertArrayEquals(new long[] {0, 740}, ClipRanges.range("0-740"));
    }
}
