package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Arrays;

/** The song pill's accent is the colour covering most of the cover, not its most vivid speck. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MeeTuneAccentTest {
    private static final int WHITE = 0xFFF2F2F2;

    @Test
    public void aRedSpeckOnAGreyPosterGivesWhite() {
        int[] cover = new int[576];
        Arrays.fill(cover, 0xFF303030);
        Arrays.fill(cover, 0, 20, Color.RED); // ~3% red lettering
        assertEquals(WHITE, MeeTunePlayerSkin.accent(cover));
    }

    @Test
    public void aMostlyBlueCoverWithARedSpeckGivesBlue() {
        int[] cover = new int[576];
        Arrays.fill(cover, 0xFF1E3C8C);
        Arrays.fill(cover, 0, 20, Color.RED);
        float[] hsv = new float[3];
        Color.colorToHSV(MeeTunePlayerSkin.accent(cover), hsv);
        assertTrue("hue " + hsv[0], hsv[0] > 200 && hsv[0] < 240);
    }
}
