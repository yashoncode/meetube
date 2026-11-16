package com.newtube.mobile.downloads;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DownloadSpeedTest {
    @Test
    public void rateAndTimeLeftFollowTheBytes() {
        DownloadItem item = new DownloadItem("a", "v", "t", null, null, DownloadOption.KIND_VIDEO,
                "720p", "22", null, 0);
        item.bytesTotal = 10_000_000;
        item.sampleSpeed(1_000); // the baseline
        assertEquals(-1, item.secondsLeft());
        item.bytesDone = 2_000_000;
        item.sampleSpeed(3_000); // 1 MB/s
        assertEquals(1_000_000f, item.bytesPerSec, 1f);
        assertEquals(8, item.secondsLeft());
        item.sampleSpeed(20_000); // a long gap (a pause): starts over, no stale rate
        assertEquals(0f, item.bytesPerSec, 0f);
        assertEquals("1:02:03", DownloadProgressText.clock(3723));
        assertEquals("0:08", DownloadProgressText.clock(8));
    }
}
