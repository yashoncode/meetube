package com.newtube.mobile.ui.meetune;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
