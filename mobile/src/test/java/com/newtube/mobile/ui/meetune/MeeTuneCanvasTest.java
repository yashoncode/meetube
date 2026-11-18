package com.newtube.mobile.ui.meetune;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class MeeTuneCanvasTest {
    private static final List<String[]> INDEX = Arrays.asList(
            new String[] {"her", "JVKE", "her", "https://x/4.mp4"},
            new String[] {"Flowers", "Miley Cyrus", "Endless Summer Vacation", "https://x/2.mp4"});

    @Test
    public void matchesWholeWordsAndIgnoresDecoration() {
        assertEquals("https://x/2.mp4", MeeTuneCanvas.match(INDEX, "Flowers (Official Video)", "Miley Cyrus", null));
        assertEquals("https://x/4.mp4", MeeTuneCanvas.match(INDEX, "her", "JVKE feat. Someone", "her"));
        assertNull(MeeTuneCanvas.match(INDEX, "Mother", "JVKE", null)); // "her" is not a word of "mother"
        assertNull(MeeTuneCanvas.match(INDEX, "Flowers", "Someone Else", null)); // the artist must match
        assertNull(MeeTuneCanvas.match(INDEX, "Flowers", "Miley Cyrus", "Another Album")); // a different album
    }

    @Test
    public void partsGoMostReplayedFirstAndApart() {
        float[] scores = new float[100];
        scores[50] = 1f;
        scores[51] = 0.9f; // 2.4 s from the peak: too close to be a part of its own
        scores[80] = 0.8f;
        scores[1] = 0.95f; // the intro: never a part
        long length = 240_000;
        assertEquals(120_000, MeeTuneCanvas.partStart(scores, length, 0));
        assertEquals(192_000, MeeTuneCanvas.partStart(scores, length, 1));
        // no graph: set places through the video, round and round
        assertEquals(72_000, MeeTuneCanvas.partStart(null, length, 0));
        assertEquals(72_000, MeeTuneCanvas.partStart(null, length, 4));
    }

    @Test
    public void readsLengthsAndTitles() {
        assertEquals(225_000, MeeTuneCanvas.durationMs("3:45"));
        assertEquals(3_723_000, MeeTuneCanvas.durationMs("1:02:03"));
        assertEquals(0, MeeTuneCanvas.durationMs("Song"));
        assertTrue(MeeTuneCanvas.sameTitle("flowers", "flowers remastered"));
        assertFalse(MeeTuneCanvas.sameTitle("love", "love is lonely tonight again forever")); // far longer: another song
        assertFalse(MeeTuneCanvas.sameTitle("mother", "her"));
    }
}
