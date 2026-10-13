package com.newtube.mobile.ui.search;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.util.Base64;

/** The encoder reproduces SearchFilterHelper's hand-filled params, so its gap-fill speaks the same format. */
public class SearchFilterParamsTest {
    private static void assertParam(String expected, int date, int duration, int type, int feature, int sort) {
        assertArrayEquals(expected, Base64.getDecoder().decode(expected.replace("%3D", "=")),
                SearchFilterParams.proto(date, duration, type, feature, sort));
    }

    @Test
    public void matchesTheKnownParams() {
        assertParam("EgQIARAB", 1, 0, 0, 0, 0);           // last hour
        assertParam("EgQIBRAB", 5, 0, 0, 0, 0);           // this year
        assertParam("EgQQARgB", 0, 1, 0, 0, 0);           // under 4 minutes
        assertParam("EgQQARgD", 0, 2, 0, 0, 0);           // 4-20 minutes
        assertParam("EgQQARgC", 0, 3, 0, 0, 0);           // over 20 minutes
        assertParam("EgIQAg%3D%3D", 0, 0, 2, 0, 0);       // channels
        assertParam("EgJAAQ%3D%3D", 0, 0, 0, 1, 0);       // live
        assertParam("EgPIAQE%3D", 0, 0, 0, 3, 0);         // HDR
        assertParam("EgdAAXAByAEB", 0, 0, 0, 6, 0);       // live + 4K + HDR
        assertParam("CAI%3D", 0, 0, 0, 0, 1);             // sort by upload date
        assertParam("EgYIAhABGAE%3D", 2, 1, 0, 0, 0);     // today, under 4 minutes
        assertParam("CAISBBABGAI%3D", 0, 3, 0, 0, 1);     // the table's "this week" entry is really this
    }
}
