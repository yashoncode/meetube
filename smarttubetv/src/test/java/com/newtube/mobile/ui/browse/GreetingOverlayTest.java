package com.newtube.mobile.ui.browse;

import static org.junit.Assert.assertEquals;

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
}
