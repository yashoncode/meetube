package com.newtube.mobile.downloads;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

/** The one-line status every surface shows for an item: card meta, notification, menus. */
final class DownloadProgressText {
    private DownloadProgressText() {
    }

    /** MEETUBE(issue #265): " · 2.4 MB/s · 1:23 left" once the rate is known, else nothing. */
    static String speed(Context context, DownloadItem item) {
        float rate = item.bytesPerSec;
        if (rate < 1f) return "";
        String text = " · " + DownloadOptions.formatBytes(Math.round(rate)) + "/s";
        long left = item.secondsLeft();
        return left < 0 ? text : text + " · " + context.getString(R.string.mobile_download_time_left, clock(left));
    }

    /** 1:23, or 1:02:03 from an hour. */
    static String clock(long seconds) {
        long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    static String of(Context context, DownloadItem item) {
        switch (item.state) {
            case DownloadItem.STATE_QUEUED:
                return context.getString(R.string.mobile_download_state_queued);
            case DownloadItem.STATE_DOWNLOADING: {
                int percent = item.progressPercent();
                String done = DownloadOptions.formatBytes(item.bytesDone);
                if (percent < 0) {
                    return context.getString(R.string.mobile_download_state_downloading_bytes, done);
                }
                return context.getString(R.string.mobile_download_state_downloading, percent,
                        done, DownloadOptions.formatBytes(item.bytesTotal)) + speed(context, item);
            }
            case DownloadItem.STATE_PAUSED: {
                int percent = item.progressPercent();
                if (percent < 0) {
                    return context.getString(R.string.mobile_download_badge_paused);
                }
                return context.getString(R.string.mobile_download_state_paused, percent,
                        DownloadOptions.formatBytes(item.bytesDone), DownloadOptions.formatBytes(item.bytesTotal));
            }
            case DownloadItem.STATE_PROCESSING:
                return context.getString(R.string.mobile_download_state_processing);
            case DownloadItem.STATE_FAILED:
                return item.error != null ? item.error : context.getString(R.string.mobile_download_error_generic);
            case DownloadItem.STATE_DONE:
            default: {
                String quality = item.isAudioOnly()
                        ? context.getString(R.string.mobile_download_audio_only)
                        : item.qualityLabel != null ? item.qualityLabel : "";
                String size = DownloadOptions.formatBytes(item.bytesTotal);
                return size.isEmpty() ? quality : quality + com.newtube.mobile.ui.common.MetaSeparator.DOT + size;
            }
        }
    }
}
