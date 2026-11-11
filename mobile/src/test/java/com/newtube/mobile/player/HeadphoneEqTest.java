package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** MEETUBE(autoeq): the ParametricEQ.txt parser and the biquads hit the gains AutoEq asks for. */
public class HeadphoneEqTest {
    private static final int RATE = 48_000;

    /** |H(e^jw)| in dB for {b0, b1, b2, a1, a2}. */
    private static double gainDb(double[] c, double hz) {
        double w = 2 * Math.PI * hz / RATE;
        double nr = c[0] + c[1] * Math.cos(w) + c[2] * Math.cos(2 * w);
        double ni = -c[1] * Math.sin(w) - c[2] * Math.sin(2 * w);
        double dr = 1 + c[3] * Math.cos(w) + c[4] * Math.cos(2 * w);
        double di = -c[3] * Math.sin(w) - c[4] * Math.sin(2 * w);
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
    }

    @Test
    public void filtersHitTheirGains() {
        HeadphoneEq.Profile profile = HeadphoneEq.Profile.parse("Preamp: -6.1 dB\n"
                + "Filter 1: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.41\n"
                + "Filter 2: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70\n"
                + "Filter 3: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70\n"
                + "Filter 4: ON PK Fc 30000 Hz Gain 3.0 dB Q 1.00\n");
        assertEquals(-6.1, profile.preampDb, 1e-9);
        double[][] c = profile.coefficients(RATE);
        assertEquals(3, c.length); // 30 kHz is past Nyquist at 48 kHz
        assertEquals(6.0, gainDb(c[0], 1000), 0.01);
        assertEquals(0.0, gainDb(c[0], 20), 0.1);
        assertEquals(6.4, gainDb(c[1], 1), 0.05);
        assertEquals(0.0, gainDb(c[1], 15000), 0.1);
        assertEquals(-2.1, gainDb(c[2], 23900), 0.1);
        assertEquals(0.0, gainDb(c[2], 50), 0.05);
    }

    @Test
    public void noFiltersIsNoProfile() {
        assertNull(HeadphoneEq.Profile.parse("Preamp: -1 dB\n"));
        assertNull(HeadphoneEq.Profile.parse("<html>404</html>"));
    }
}
