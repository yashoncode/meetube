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
        return video != null && (video.isShorts || isShortsTile(video));
    }

    /**
     * A card that is not a video but a way into a Shorts list - the channel's (or Subscriptions')
     * "Shorts" tab served as a tile (#63). Opening it lands on a list whose every item is a Short,
     * so after filtering the user saw a blank "Shorts" screen; the tile goes with them.
     *
     * <p>The title alone is not enough: a real channel or playlist may be called "Shorts". A tab
     * tile is a nested list (MediaServiceCore's TabMediaItem/GuideMediaItem: {@code hasUploads()})
     * with no video and no playlist behind it, while a channel card has no nested list and a
     * playlist card has its playlist id - both are kept.
     */
    static boolean isShortsTile(Video video) {
        return video.videoId == null && video.playlistId == null && video.hasNestedItems()
                && isShortsName(video.getTitle());
    }

    private static boolean isShortsName(CharSequence title) {
        if (title == null) {
            return false;
        }
        String name = title.toString().trim();
        return "Shorts".equalsIgnoreCase(name) || "#Shorts".equalsIgnoreCase(name);
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
