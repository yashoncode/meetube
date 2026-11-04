package com.newtube.mobile.ui.browse;

import org.junit.Test;

public class FeedPlaybackSettingsTest {
    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownModes() {
        FeedPlaybackSettings.setMode(null, -1);
    }
}
