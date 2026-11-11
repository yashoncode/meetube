package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Arrays;
import java.util.Collections;

/** MEETUBE(issue #227): what a Shorts feed page adds to a Home shelf's queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShortsFeedTailTest {
    @Test
    public void aPageAddsItsNewPlayableShortsOnce() {
        Video queued = video("queued");
        Video fresh = video("fresh");
        Video watched = video("watched");
        watched.percentWatched = 95;
        ShortsSeen.add("seen");

        assertEquals(Collections.singletonList(fresh), ShortsFeedTail.fresh(
                Arrays.asList(video("queued"), fresh, video("fresh"), watched, video("seen"), video(null), null),
                Collections.singletonList(queued)));
        assertEquals(Collections.emptyList(), ShortsFeedTail.fresh(null, Collections.singletonList(queued)));
    }

    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        return video;
    }
}
