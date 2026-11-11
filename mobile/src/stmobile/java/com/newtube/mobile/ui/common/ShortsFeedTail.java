package com.newtube.mobile.ui.common;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MEETUBE(issue #227): a Short opened from Home's shelf had only that shelf's 6 or 7 shorts as its
 * queue, and nothing added to it: the player asks the browse screen for the next page, which on
 * Home is a no-op (the Home walk and the shelf tail page from the grid's scroll, and the grid is
 * behind the player). This pulls the Shorts tab's feed into such a queue, a page at a time, as the
 * swipe nears its end. Main thread only.
 */
public final class ShortsFeedTail {
    /** Pages in a row that added nothing (all seen or already queued) before giving up. */
    private static final int MAX_EMPTY_PAGES = 5;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The last short of the queue this tail extends; another queue does not hold it. */
    @Nullable
    private static Video sTail;
    /** The feed page the next one continues, null before the first. */
    @Nullable
    private static MediaGroup sPage;
    private static boolean sFetching;
    private static int sEmptyPages;

    private ShortsFeedTail() {
    }

    /** The queue just built (Playlist) is one this tail keeps extending. */
    public static void arm() {
        List<Video> queue = Playlist.instance().getAll();
        sTail = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        sPage = null;
        sEmptyPages = 0;
    }

    /** Fetches the feed's next page into the playing queue. False when the queue is not this tail's. */
    public static boolean extend() {
        Video tail = sTail;
        if (tail == null || !holds(Playlist.instance().getAll(), tail)) {
            return false;
        }
        if (sFetching || (sPage != null && sPage.getNextPageKey() == null)) {
            return true;
        }
        sFetching = true;
        ContentService content = YouTubeServiceManager.instance().getContentService();
        int[] added = {0};
        RxHelper.execute(sPage == null ? content.getShortsObserve() : content.continueGroupObserve(sPage),
                group -> MAIN.post(() -> added[0] += onPage(tail, group)),
                error -> MAIN.post(() -> {
                    sFetching = false; // the next short opened asks again
                    android.util.Log.w("ShortsFeedTail", "Shorts feed page: " + error);
                }),
                () -> MAIN.post(() -> {
                    sFetching = false;
                    if (tail != sTail && added[0] == 0) {
                        return; // another queue was armed meanwhile
                    }
                    sEmptyPages = added[0] > 0 ? 0 : sEmptyPages + 1;
                    if (added[0] == 0 && sEmptyPages < MAX_EMPTY_PAGES) {
                        extend(); // a page of watched shorts: the next one, or the swipe stops here
                    }
                }));
        return true;
    }

    private static int onPage(Video tail, @Nullable MediaGroup group) {
        List<Video> queue = Playlist.instance().getAll();
        if (group == null || tail != sTail || !holds(queue, tail)) {
            return 0;
        }
        sPage = group;
        List<Video> more = fresh(VideoGroup.from(group).getVideos(), queue);
        if (more.isEmpty()) {
            return 0;
        }
        for (Video video : more) {
            video.markShorts(); // latched: its group is held weakly
        }
        Playlist.instance().addAll(more);
        sTail = more.get(more.size() - 1);
        return more.size();
    }

    /** {@code page}'s shorts worth queueing: playable, not played lately, not in {@code queue} or twice. */
    static List<Video> fresh(@Nullable List<Video> page, List<Video> queue) {
        List<Video> result = new ArrayList<>();
        if (page == null) {
            return result;
        }
        Set<String> queued = new HashSet<>();
        for (Video video : queue) {
            queued.add(video.videoId);
        }
        for (Video video : page) {
            if (video != null && video.hasVideo() && video.percentWatched < 90
                    && !ShortsSeen.contains(video.videoId) && queued.add(video.videoId)) {
                result.add(video);
            }
        }
        return result;
    }

    private static boolean holds(List<Video> queue, Video tail) {
        for (Video video : queue) {
            if (video == tail) { // the same object: an equal short of another queue is not this one
                return true;
            }
        }
        return false;
    }
}
