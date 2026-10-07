package com.newtube.mobile.ui.common;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(shorts): NewTube has no Shorts. Every list the phone shows - the feeds, search results,
 * channel pages and their tabs, playlists, Up next - drops them, always; there is no setting (the
 * shared "Hide content" Shorts rows and the Shorts section are not shown on the phone, and nothing
 * here reads their prefs). A Short opened from a shared youtube.com/shorts/ link still plays in the
 * normal player: links are not lists.
 *
 * <p>A Short is what the item itself says it is ({@link Video#isShorts}: a reel renderer, the Shorts
 * badge or tile style, or a lockup whose tap opens the reel player - see MediaServiceCore's
 * ItemWrapper.isShorts), never a guess from its duration - a guess would also hide ordinary videos.
 */
public final class ShortsFilter {
    private ShortsFilter() {
    }

    public static boolean isShort(Video video) {
        return video != null && video.isShorts;
    }

    /** {@code videos} without its Shorts, in order (the same list when it has none). */
    public static List<Video> withoutShorts(List<Video> videos) {
        if (videos == null || !containsShort(videos)) {
            return videos;
        }
        List<Video> result = new ArrayList<>(videos.size());
        for (Video video : videos) {
            if (!isShort(video)) {
                result.add(video);
            }
        }
        return result;
    }

    /**
     * Whether a channel section is the channel's Shorts section - the one to leave out entirely:
     * named Shorts (YouTube's label, or this app's {@code localizedLabel}) and holding nothing but
     * Shorts (or nothing at all, when the service already emptied it). Any other section keeps its
     * tab and its next page even when its first page filtered down to zero cards.
     */
    public static boolean isShortsSection(String title, String localizedLabel, List<Video> videos) {
        if (title == null) {
            return false;
        }
        String name = title.trim();
        boolean named = "Shorts".equalsIgnoreCase(name)
                || (localizedLabel != null && localizedLabel.trim().equalsIgnoreCase(name));
        if (!named) {
            return false;
        }
        if (videos != null) {
            for (Video video : videos) {
                if (video != null && !isShort(video)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsShort(List<Video> videos) {
        for (Video video : videos) {
            if (isShort(video)) {
                return true;
            }
        }
        return false;
    }
}
