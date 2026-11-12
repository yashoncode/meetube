package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

/**
 * MEETUBE: once focus rests on a card, its stream info is fetched in the background.
 * The single-slot format info cache then answers the player when OK is pressed, so OK only starts playback.
 * One fetch at a time: the cache is locked per fetch, and a queue would delay the player behind stale cards.
 */
public final class FormatPrefetch {
    private static final long REST_MS = 500;
    private static String sWanted;
    private static boolean sRunning;
    private static final Runnable sFetch = FormatPrefetch::fetch;

    private FormatPrefetch() {
    }

    public static void onFocus(Video video) {
        Utils.removeCallbacks(sFetch);
        sWanted = video != null && video.hasVideo() && !video.isUpcoming ? video.videoId : null;

        if (sWanted != null) {
            Utils.postDelayed(sFetch, REST_MS);
        }
    }

    private static void fetch() {
        if (sRunning || sWanted == null) {
            return; // the running fetch picks up the newest card when it finishes
        }

        String videoId = sWanted;
        sRunning = true;
        RxHelper.execute(YouTubeServiceManager.instance().getMediaItemService().getFormatInfoObserve(videoId),
                result -> {}, error -> done(videoId), () -> done(videoId));
    }

    private static void done(String videoId) {
        sRunning = false;

        if (sWanted != null && !sWanted.equals(videoId)) {
            fetch();
        }
    }
}
