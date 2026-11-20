package com.newtube.mobile.ui.browse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class GreetingOverlayTest {
    @Test
    public void greetsByTheHour() {
        assertEquals(3, GreetingOverlay.period(4));
        assertEquals(0, GreetingOverlay.period(5));
        assertEquals(0, GreetingOverlay.period(11));
        assertEquals(1, GreetingOverlay.period(12));
        assertEquals(2, GreetingOverlay.period(17));
        assertEquals(3, GreetingOverlay.period(21));
        assertEquals(3, GreetingOverlay.period(0));
    }

    @Test
    public void neverTheSameQuoteTwiceInARow() {
        java.util.Random random = new java.util.Random(7);
        int last = -1;
        for (int i = 0; i < 500; i++) {
            int next = GreetingOverlay.nextQuote(30, last, random);
            assertNotEquals(last, next);
            assertEquals(true, next >= 0 && next < 30);
            last = next;
        }
        assertEquals(0, GreetingOverlay.nextQuote(1, 0, random));
    }
}
