package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** MEETUNE(automix): a beat grid from sound, as the probe hands it over (48 kHz mono, onsets, analysis). */
public class MeeTuneBeatsTest {
    private static final int RATE = 48_000;

    @Test
    public void findsTempoPhaseAndBarOfADrumLoop() {
        for (double bpm : new double[] {92, 124, 128, 140, 174}) {
            double period = 60_000 / bpm;
            double firstBeatMs = 317; // a bar's first beat (its kick is the loudest)
            MeeTuneBeats.Grid grid = gridOf(drums(bpm, firstBeatMs, 0, 30_000, 30), 5_000);
            System.out.println(bpm + " -> " + grid);
            assertTrue(bpm + " bpm", grid != null && grid.strong());
            boolean half = grid.bpm() < bpm * 0.75; // half tempo (fast songs): mixes the same
            assertEquals(bpm + " bpm", bpm, half ? grid.bpm() * 2 : grid.bpm(), 0.3);
            // A beat 20 s in (a crossfade extrapolates the grid that far) lands within 15 ms.
            double beat = 5_000 + firstBeatMs + Math.round(20_000 / period) * period;
            double off = grid.scaled(half ? 0.5 : 1).phase(beat);
            assertTrue(bpm + " bpm off by " + off, Math.min(off, 1 - off) * period < 15);
            if (!half) { // the grid starts on a bar's first beat
                double bars = (grid.firstMs - 5_000 - firstBeatMs) / (4 * period);
                assertEquals(bpm + " bpm bar", Math.round(bars), bars, 0.02);
            }
        }
    }

    @Test
    public void mixesInPastASilentIntroAndOutBeforeTheMusicStops() {
        double bpm = 120; // a bar is 2 s
        MeeTuneBeats.Grid grid = gridOf(drums(bpm, 0, 6_000, 24_000, 32), 0);
        System.out.println("intro/outro -> " + grid + " in=" + grid.mixInMs() + " end=" + grid.musicEndMs());
        assertTrue(grid.strong());
        assertEquals(6_000, grid.mixInMs(), 40);
        assertEquals(24_000, grid.musicEndMs(), 40);
    }

    @Test
    public void noiseHasNoGrid() {
        Random random = new Random(7);
        float[] noise = new float[RATE * 30];
        for (int i = 0; i < noise.length; i++) noise[i] = (float) (random.nextGaussian() * 0.1);
        MeeTuneBeats.Grid grid = gridOf(noise, 0);
        System.out.println("noise -> " + grid);
        assertTrue(grid == null || !grid.strong());
    }

    private static MeeTuneBeats.Grid gridOf(float[] samples, double startMs) {
        MeeTuneBeats.Onsets onsets = new MeeTuneBeats.Onsets(RATE);
        onsets.firstMs = startMs;
        for (float s : samples) onsets.add(s);
        return MeeTuneBeats.analyze(onsets);
    }

    /**
     * Kicks on the beat (the bar's first the loudest), hats on the off-beat, over a quiet hiss; the drums play
     * from {@code fromMs} to {@code untilMs} on a grid with a bar line at {@code firstBeatMs}.
     */
    private static float[] drums(double bpm, double firstBeatMs, double fromMs, double untilMs, int seconds) {
        Random random = new Random(1);
        float[] out = new float[RATE * seconds];
        for (int i = 0; i < out.length; i++) out[i] = (float) (random.nextGaussian() * 0.002);
        double period = 60_000 / bpm;
        int beat = 0;
        for (double t = firstBeatMs; t < seconds * 1000; t += period, beat++) {
            if (t < fromMs || t >= untilMs) continue;
            double level = beat % 4 == 0 ? 0.9 : 0.5;
            int at = (int) (t * RATE / 1000);
            for (int i = 0; i < RATE / 5 && at + i < out.length; i++) { // kick: a falling 55 Hz thump
                out[at + i] += (float) (level * Math.exp(-i / (RATE * 0.05)) * Math.sin(2 * Math.PI * 55 * i / RATE));
            }
            int hat = (int) ((t + period / 2) * RATE / 1000);
            for (int i = 0; i < RATE / 50 && hat + i < out.length; i++) { // hat: a short noise burst
                out[hat + i] += (float) (0.15 * Math.exp(-i / (RATE * 0.005)) * random.nextGaussian());
            }
        }
        return out;
    }
}
