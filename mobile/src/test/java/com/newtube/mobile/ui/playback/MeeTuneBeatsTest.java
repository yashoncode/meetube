package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** MEETUNE(automix): the grid fitted through the model's beats and downbeats, and the front end's shape. */
public class MeeTuneBeatsTest {
    private static final int RATE = 48_000;

    @Test
    public void fitsTempoAndBarThroughJitteredAndMissedBeats() {
        Random random = new Random(3);
        for (double bpm : new double[] {84, 124, 128, 174}) {
            double period = 60_000 / bpm;
            double bar = 5_317; // a bar line
            List<Double> beats = new ArrayList<>();
            List<Double> downs = new ArrayList<>();
            for (int k = -2; k < 70; k++) {
                if (k % 13 == 5) continue; // the model missed it
                double t = bar + k * period + random.nextGaussian() * 6;
                beats.add(t);
                if (Math.floorMod(k, 4) == 0 && k % 3 != 0) downs.add(t); // and some downbeats
            }
            MeeTuneBeats.Audio audio = new MeeTuneBeats.Audio(RATE);
            audio.firstMs = 5_000;
            for (int i = 0; i < RATE * 30; i++) audio.add(0.1f);
            MeeTuneBeats.Grid grid = MeeTuneBeats.grid(array(beats), array(downs), audio);
            System.out.println(bpm + " -> " + grid);
            assertTrue(bpm + " bpm", grid != null && grid.strong());
            assertEquals(bpm + " bpm", bpm, grid.bpm(), 0.1);
            double bars = (grid.firstMs - bar) / (4 * period);
            assertEquals(bpm + " bpm bar", Math.round(bars), bars, 0.01);
        }
    }

    @Test
    public void aLooseIntroDoesNotBendTheGridOfTheSteadyPart() {
        Random random = new Random(9);
        List<Double> beats = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < 14; i++) beats.add(t += 300 + random.nextDouble() * 500); // no drums yet
        double drop = t + 700; // then 112 BPM, a bar line here
        double period = 60_000 / 112.0;
        List<Double> downs = new ArrayList<>();
        for (int k = 0; k < 56; k++) {
            beats.add(drop + k * period);
            if (k % 4 == 0) downs.add(drop + k * period);
        }
        MeeTuneBeats.Audio audio = new MeeTuneBeats.Audio(RATE);
        audio.firstMs = 0;
        for (int i = 0; i < RATE * 40; i++) audio.add(0.1f);
        MeeTuneBeats.Grid grid = MeeTuneBeats.grid(array(beats), array(downs), audio);
        System.out.println("loose intro -> " + grid + " in=" + grid.mixInMs());
        assertTrue(grid.strong());
        assertEquals(112, grid.bpm(), 0.05);
        assertEquals(drop, grid.mixInMs(), 2);
    }

    @Test
    public void scatteredBeatsAreNotStrongAndFewAreNone() {
        Random random = new Random(5);
        double[] beats = new double[40];
        double t = 0;
        for (int i = 0; i < beats.length; i++) beats[i] = t += 300 + random.nextDouble() * 400;
        MeeTuneBeats.Audio audio = new MeeTuneBeats.Audio(RATE);
        audio.firstMs = 0;
        MeeTuneBeats.Grid grid = MeeTuneBeats.grid(beats, new double[0], audio);
        System.out.println("rubato -> " + grid);
        assertTrue(grid == null || !grid.strong());
        assertNull(MeeTuneBeats.grid(new double[] {0, 500, 1000}, new double[0], audio));
    }

    @Test
    public void mixesInPastASilentIntroAndOutBeforeTheMusicStops() {
        double period = 500; // 120 BPM, a bar is 2 s
        MeeTuneBeats.Audio audio = new MeeTuneBeats.Audio(RATE);
        audio.firstMs = 0;
        Random random = new Random(1);
        for (int i = 0; i < RATE * 32; i++) {
            double ms = i * 1000.0 / RATE;
            audio.add((float) (random.nextGaussian() * (ms >= 6_000 && ms < 24_000 ? 0.3 : 0.002)));
        }
        double[] beats = new double[64];
        for (int k = 0; k < beats.length; k++) beats[k] = k * period;
        double[] downs = {0, 2_000, 4_000, 8_000};
        MeeTuneBeats.Grid grid = MeeTuneBeats.grid(beats, downs, audio);
        System.out.println("intro/outro -> " + grid + " in=" + grid.mixInMs() + " end=" + grid.musicEndMs());
        assertEquals(6_000, grid.mixInMs(), 1);
        assertEquals(24_000, grid.musicEndMs(), 1);
    }

    @Test
    public void frontEndGivesFiftyFramesOf128MelsASecond() {
        float[] tone = new float[RATE * 2];
        for (int i = 0; i < tone.length; i++) tone[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / RATE));
        float[] pcm = BeatThis.resample(tone, tone.length, RATE);
        assertEquals(BeatThis.RATE * 2, pcm.length, 2);
        float[] mel = BeatThis.mel(pcm);
        assertEquals(2 * BeatThis.RATE / 441 + 1, mel.length / 128);
        // 440 Hz lands in one low band, and the top bands (well above it) stay near silent.
        int frame = 50 * 128;
        int loudest = 0;
        for (int m = 1; m < 128; m++) if (mel[frame + m] > mel[frame + loudest]) loudest = m;
        System.out.println("440 Hz -> band " + loudest + " = " + mel[frame + loudest] + ", top " + mel[frame + 127]);
        assertTrue(loudest > 10 && loudest < 40);
        assertTrue(mel[frame + 127] < 0.01 * mel[frame + loudest]);
    }

    private static double[] array(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
