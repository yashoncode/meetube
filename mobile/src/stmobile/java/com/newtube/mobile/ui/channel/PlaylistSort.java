package com.newtube.mobile.ui.channel;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MEETUBE(playlist page): sorts only facts carried by loaded cards, leaving Manual untouched. */
final class PlaylistSort {
    static final int MANUAL = 0;
    static final int NEWEST = 1;
    static final int OLDEST = 2;
    static final int POPULAR = 3;
    private static final Pattern VIEWS = Pattern.compile(
            "(?i)([0-9]+(?:[.,\\u00a0 ][0-9]+)*)\\s*(k|m|b|lakh|crore)?\\s+views?\\b");
    private static final Pattern AGE = Pattern.compile(
            "(?i)(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago");

    private PlaylistSort() {
    }

    static int label(int sort) {
        switch (sort) {
            case NEWEST: return R.string.mobile_playlist_sort_newest;
            case OLDEST: return R.string.mobile_playlist_sort_oldest;
            case POPULAR: return R.string.mobile_playlist_sort_popular;
            default: return R.string.mobile_playlist_sort_manual;
        }
    }

    static boolean available(List<Video> videos, int sort) {
        long now = System.currentTimeMillis();
        for (Video video : videos) {
            if (value(video, sort, now) >= 0) {
                return true;
            }
        }
        return sort == MANUAL;
    }

    static List<Video> apply(List<Video> videos, int sort) {
        List<Video> result = new ArrayList<>(videos);
        if (sort == MANUAL) {
            return result;
        }
        long now = System.currentTimeMillis();
        Map<Video, Long> values = new HashMap<>();
        for (Video video : videos) {
            values.put(video, value(video, sort, now));
        }
        // MEETUBE(playlist page): stable ties preserve Manual order; missing facts always come last.
        java.util.Collections.sort(result, (a, b) -> {
            long left = values.get(a);
            long right = values.get(b);
            if (left < 0 || right < 0) {
                return left < 0 ? (right < 0 ? 0 : 1) : -1;
            }
            return sort == OLDEST ? Long.compare(left, right) : Long.compare(right, left);
        });
        return result;
    }

    private static long value(Video video, int sort, long now) {
        if (sort == POPULAR) {
            if (video.mediaItem instanceof YouTubeMediaItem) {
                int count = ((YouTubeMediaItem) video.mediaItem).getViewCount();
                if (count > 0) {
                    return count;
                }
            }
            CharSequence info = video.getSecondTitleFull();
            Matcher views = VIEWS.matcher(info != null ? info : "");
            if (!views.find()) {
                return -1;
            }
            String number = views.group(1).replace("\u00a0", "").replace(" ", "");
            String unit = views.group(2);
            double scale = unit == null ? 1 : unit.equalsIgnoreCase("k") ? 1_000
                    : unit.equalsIgnoreCase("m") ? 1_000_000 : unit.equalsIgnoreCase("b") ? 1_000_000_000
                    : unit.equalsIgnoreCase("lakh") ? 100_000 : 10_000_000;
            number = unit == null ? number.replace(",", "").replace(".", "") : number.replace(',', '.');
            try {
                return Math.round(Double.parseDouble(number) * scale);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        long published = video.mediaItem != null ? video.mediaItem.getPublishedDate() : -1;
        if (published > 0) {
            return published < 100_000_000_000L ? published * 1_000 : published;
        }
        String date = video.mediaItem != null ? video.mediaItem.getProductionDate() : null;
        if (date == null) {
            CharSequence info = video.getSecondTitleFull();
            date = info != null ? info.toString() : "";
        }
        Matcher age = AGE.matcher(date);
        if (age.find()) {
            String unit = age.group(2).toLowerCase(Locale.US);
            long seconds = unit.equals("second") ? 1 : unit.equals("minute") ? 60
                    : unit.equals("hour") ? 3_600 : unit.equals("day") ? 86_400
                    : unit.equals("week") ? 604_800 : unit.equals("month") ? 2_629_800 : 31_557_600;
            try {
                return Math.max(0, now - Long.parseLong(age.group(1)) * seconds * 1_000);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        for (String pattern : new String[] {"yyyy-MM-dd", "MMM d, yyyy", "d MMM yyyy", "d MMMM yyyy"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ENGLISH);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date parsed = format.parse(date, position);
            if (parsed != null && position.getIndex() == date.length()) {
                return parsed.getTime();
            }
        }
        return -1;
    }
}
