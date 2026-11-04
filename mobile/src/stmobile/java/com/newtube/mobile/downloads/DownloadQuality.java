package com.newtube.mobile.downloads;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/** Persistent default video-download quality. A height of zero means ask on every download. */
public final class DownloadQuality {
    public static final int ASK_EVERY_TIME = 0;
    private static final String PREFS = "meetube_downloads";
    private static final String KEY_HEIGHT = "download_quality_height";
    private static final int[] HEIGHTS = {144, 240, 360, 480, 720, 1080, 1440, 2160};

    private DownloadQuality() {
    }

    public static int preferredHeight(Context context) {
        int height = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_HEIGHT, ASK_EVERY_TIME);
        return isValid(height) ? height : ASK_EVERY_TIME;
    }

    public static void setPreferredHeight(Context context, int height) {
        if (!isValid(height)) {
            throw new IllegalArgumentException("unsupported download quality: " + height);
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_HEIGHT, height).apply();
    }

    public static boolean isValid(int height) {
        if (height == ASK_EVERY_TIME) {
            return true;
        }
        for (int available : HEIGHTS) {
            if (available == height) {
                return true;
            }
        }
        return false;
    }

    public static int[] heights() {
        return HEIGHTS.clone();
    }

    public static CharSequence label(Context context) {
        int height = preferredHeight(context);
        return height == ASK_EVERY_TIME
                ? context.getString(R.string.mobile_download_quality_ask)
                : context.getString(R.string.mobile_download_quality_resolution, height);
    }

    /** Best video at or below the saved height; if none is lower, use the lowest available video. */
    @Nullable
    public static DownloadOption pick(@Nullable List<DownloadOption> options, int preferredHeight) {
        if (options == null || preferredHeight <= ASK_EVERY_TIME) {
            return null;
        }
        DownloadOption lower = null;
        DownloadOption lowest = null;
        for (DownloadOption option : options) {
            if (option == null || option.kind != DownloadOption.KIND_VIDEO || option.height <= 0) {
                continue;
            }
            if (lowest == null || option.height < lowest.height) {
                lowest = option;
            }
            if (option.height <= preferredHeight && (lower == null || option.height > lower.height)) {
                lower = option;
            }
        }
        return lower != null ? lower : lowest;
    }
}
