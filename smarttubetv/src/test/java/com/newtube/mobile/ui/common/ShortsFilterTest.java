package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
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

    @Test
    public void aShortsTabServedAsATileIsAShort() {
        // #63: the channel's / Subscriptions' "Shorts" tab arrives as a nested-list card with no
        // video; it opened a list of nothing but Shorts, i.e. a blank screen.
        Video tab = tile(" Shorts ", null, null);
        assertTrue(ShortsFilter.isShort(tab));
        assertTrue("guide-style tab with a browse id", ShortsFilter.isShort(tile("#Shorts", "UCabc", null)));

        Video a = video("a", false);
        assertEquals(Arrays.asList(a), ShortsFilter.withoutShorts(Arrays.asList(tab, a)));
    }

    @Test
    public void aChannelOrPlaylistNamedShortsIsKept() {
        Video channel = new Video();
        channel.title = "Shorts";
        channel.channelId = "UCshorts";
        assertFalse("a real channel named Shorts", ShortsFilter.isShort(channel));

        Video playlist = new Video();
        playlist.title = "#Shorts";
        playlist.playlistId = "PLabc";
        assertFalse("a playlist named #Shorts", ShortsFilter.isShort(playlist));

        assertFalse("a nested playlist named #Shorts", ShortsFilter.isShort(tile("#Shorts", "UCabc", "PLabc")));
    }

    @Test
    public void aVideoTitledShortsIsNotATile() {
        Video named = video("v1", false);
        named.title = "Shorts";
        assertFalse(ShortsFilter.isShort(named));

        assertFalse(ShortsFilter.isShort(tile("Shorts and more", null, null)));
    }

    /** A card backed by a MediaItem with nested uploads (a tab / guide tile). */
    private static Video tile(String title, String channelId, String playlistId) {
        Video video = new Video();
        video.title = title;
        video.channelId = channelId;
        video.playlistId = playlistId;
        video.mediaItem = (MediaItem) Proxy.newProxyInstance(MediaItem.class.getClassLoader(),
                new Class<?>[] {MediaItem.class}, (proxy, method, args) -> {
                    if ("hasUploads".equals(method.getName())) {
                        return true;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    return null;
                });
        return video;
    }

    private static Video video(String id, boolean isShorts) {
        Video video = new Video();
        video.videoId = id;
        video.title = id;
        video.isShorts = isShorts;
        return video;
    }
}
