package com.newtube.mobile.ui.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MobileGridTest {
    @Test
    public void phonePortraitUsesOneFullWidthCard() {
        assertEquals(1, MobileGrid.computeSpanCountForWidthDp(412));
    }

    @Test
    public void phoneLandscapeUsesTwoCards() {
        assertEquals(2, MobileGrid.computeSpanCountForWidthDp(915));
    }

    @Test
    public void widePhoneLandscapeStaysAtTwoCards() {
        assertEquals(2, MobileGrid.computeSpanCountForWidthDp(1035));
    }

    @Test
    public void tabletPortraitUsesTwoCards() {
        assertEquals(2, MobileGrid.computeSpanCountForWidthDp(800));
    }

    @Test
    public void tabletLandscapeUsesFourCards() {
        assertEquals(4, MobileGrid.computeSpanCountForWidthDp(1280));
    }

    @Test
    public void spanCountNeverDropsBelowOne() {
        assertEquals(1, MobileGrid.computeSpanCountForWidthDp(0));
    }
}
