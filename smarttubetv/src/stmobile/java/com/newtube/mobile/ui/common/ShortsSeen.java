package com.newtube.mobile.ui.common;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * METUBE(shorts): the shorts played this session, so the Shorts queue never serves one twice
 * (the reel feed repeats itself across pages and refreshes).
 */
public final class ShortsSeen {
    // ponytail: process-lifetime memory only; persist it when "seen yesterday" should count too.
    private static final Set<String> sSeen = new HashSet<>();

    private ShortsSeen() {
    }

    public static synchronized void add(@Nullable String videoId) {
        if (videoId != null) {
            sSeen.add(videoId);
        }
    }

    public static synchronized boolean contains(@Nullable String videoId) {
        return videoId != null && sSeen.contains(videoId);
    }
}
