package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** NEWTUBE(shorts): NewTube shows no Shorts, whatever the stored Hide content prefs say. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShortsFilterTest {
    @Test
    public void everyShortIsDroppedAndTheRestKeepsItsOrder() {
        Video a = video("a", false);
        Video s1 = video("s1", true);
        Video b = video("b", false);
        Video s2 = video("s2", true);

        assertEquals(Arrays.asList(a, b), ShortsFilter.withoutShorts(Arrays.asList(s1, a, s2, b)));
    }

    @Test
    public void aListWithoutShortsIsReturnedAsIs() {
        List<Video> feed = Arrays.asList(video("a", false), video("b", false));
        assertSame(feed, ShortsFilter.withoutShorts(feed));
        assertNull(ShortsFilter.withoutShorts(null));
    }

    @Test
    public void aShelfOfShortsLeavesNothing() {
        assertEquals(new ArrayList<Video>(),
                ShortsFilter.withoutShorts(Arrays.asList(video("s1", true), video("s2", true))));
    }

    @Test
    public void nullSlotsAreNotShorts() {
        Video a = video("a", false);
        assertEquals(Arrays.asList(null, a),
                ShortsFilter.withoutShorts(Arrays.asList(null, video("s", true), a)));
        assertFalse(ShortsFilter.isShort(null));
    }

    @Test
    public void theChannelsShortsSectionIsNamedShortsAndHoldsOnlyShorts() {
        List<Video> shorts = Arrays.asList(video("s1", true), video("s2", true));
        assertTrue(ShortsFilter.isShortsSection("Shorts", "Shorts", shorts));
        assertTrue(ShortsFilter.isShortsSection(" SHORTS ", null, shorts));
        assertTrue("localized label", ShortsFilter.isShortsSection("Cortos", "Cortos", shorts));
        assertTrue("already emptied by the service", ShortsFilter.isShortsSection("Shorts", null, new ArrayList<>()));
    }

    @Test
    public void aNormalSectionWhoseFirstPageIsAllShortsIsNotTheShortsSection() {
        List<Video> shorts = Arrays.asList(video("s1", true), video("s2", true));
        assertFalse(ShortsFilter.isShortsSection("Videos", "Shorts", shorts));
        assertFalse(ShortsFilter.isShortsSection(null, "Shorts", shorts));
        assertFalse("a Shorts-named row with real videos is kept",
                ShortsFilter.isShortsSection("Shorts", "Shorts", Arrays.asList(video("s1", true), video("a", false))));
    }

    private static Video video(String id, boolean isShorts) {
        Video video = new Video();
        video.videoId = id;
        video.title = id;
        video.isShorts = isShorts;
        return video;
    }
}
