package com.newtube.mobile.ui.meetune;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import org.junit.After;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MeeTuneSpatialTest {
    private static final int FRAMES = 48_000;

    @After
    public void tearDown() {
        MeeTuneSpatial.setAmount(0f);
        MeeTuneSpatial.setBass(0f);
        MeeTuneSpatial.setWide(false);
        MeeTuneEq.setPlaying(false);
    }

    @Test
    public void wideSpreadsTheStereoAndNeverClips() throws Exception {
        MeeTuneEq.setPlaying(true);
        MeeTuneSpatial.setWide(true);
        for (float sample : run(loudStereo())) {
            assertTrue("sample " + sample, Math.abs(sample) < 1f);
        }
        float[] in = new float[FRAMES * 2]; // only a difference between the ears: no middle at all
        for (int f = 0; f < FRAMES; f++) {
            in[f * 2] = 0.1f * (float) Math.sin(2 * Math.PI * 1_000 * f / 48_000.0);
            in[f * 2 + 1] = -in[f * 2];
        }
        float[] out = run(in);
        double inPower = 0;
        double outPower = 0;
        for (int i = FRAMES; i < out.length; i++) { // the second half: the glide is done
            inPower += in[i] * in[i];
            outPower += out[i] * out[i];
        }
        double db = 10 * Math.log10(outPower / inPower);
        assertTrue("side " + db + " dB", db > 3);
    }

    /** A -3 dBFS 1 kHz tone, opposite in each ear: Wide pushes it far past full scale, and must only turn it down. */
    @Test
    public void wideTurnsLoudPeaksDownWithoutDistortingThem() throws Exception {
        MeeTuneEq.setPlaying(true);
        MeeTuneSpatial.setWide(true);
        float[] in = new float[FRAMES * 2];
        for (int f = 0; f < FRAMES; f++) {
            in[f * 2] = 0.708f * (float) Math.sin(2 * Math.PI * 1_000 * f / 48_000.0);
            in[f * 2 + 1] = -in[f * 2];
        }
        float[] out = run(in);
        double re = 0;
        double im = 0;
        double power = 0;
        int n = FRAMES / 2; // the second half, a whole number of cycles
        for (int f = FRAMES / 2; f < FRAMES; f++) {
            float x = out[f * 2];
            re += x * Math.cos(2 * Math.PI * 1_000 * f / 48_000.0);
            im += x * Math.sin(2 * Math.PI * 1_000 * f / 48_000.0);
            power += x * x;
            assertTrue("sample " + x, Math.abs(x) < 1f);
        }
        double fundamental = 2 * (re * re + im * im) / n;
        double thd = Math.sqrt(Math.max(0, power - fundamental) / fundamental);
        assertTrue("distortion " + thd * 100 + " %", thd < 0.01);
    }

    @Test
    public void cinemaOnFullScaleSoundNeverClips() throws Exception {
        MeeTuneEq.setPlaying(true);
        MeeTuneSpatial.setAmount(1.3f);
        MeeTuneSpatial.setBass(1f);
        float[] out = run(loudStereo());
        for (int i = 0; i < out.length; i++) {
            assertTrue("sample " + i + " = " + out[i], Math.abs(out[i]) < 1f);
        }
    }

    @Test
    public void theHeadModelKeepsACentredSongCentredAndAsLoud() throws Exception {
        MeeTuneEq.setPlaying(true);
        MeeTuneSpatial.setAmount(1f);
        float[] in = new float[FRAMES * 2];
        for (int f = 0; f < FRAMES; f++) {
            in[f * 2] = in[f * 2 + 1] = 0.3f * (float) Math.sin(2 * Math.PI * 1_000 * f / 48_000.0);
        }
        float[] out = run(in);
        double inPower = 0;
        double leftPower = 0;
        double rightPower = 0;
        for (int i = FRAMES; i < out.length; i += 2) { // the second half: the glide is done
            inPower += in[i] * in[i];
            leftPower += out[i] * out[i];
            rightPower += out[i + 1] * out[i + 1];
        }
        // The room differs per ear by design, so centred means equally loud, not identical.
        double balance = 10 * Math.log10(leftPower / rightPower);
        double db = 10 * Math.log10(leftPower / inPower);
        assertTrue("balance " + balance + " dB", Math.abs(balance) < 1);
        assertTrue("level " + db + " dB", db > -6 && db < 1);
    }

    @Test
    public void offCopiesTheSoundUntouched() throws Exception {
        MeeTuneEq.setPlaying(true);
        MeeTuneSpatial.setAmount(0f);
        float[] in = loudStereo();
        float[] out = run(in);
        for (int i = 0; i < in.length; i++) {
            assertEquals(in[i], out[i], 0f);
        }
    }

    @Test
    public void strongOnFullScaleSoundNeverClipsAndChangesIt() throws Exception {
        MeeTuneEq.setPlaying(true);
        MeeTuneSpatial.setAmount(1f);
        float[] in = loudStereo();
        float[] out = run(in);
        boolean changed = false;
        for (int i = 0; i < out.length; i++) {
            assertTrue("sample " + i + " = " + out[i], Math.abs(out[i]) < 1f);
            changed |= out[i] != in[i];
        }
        assertTrue(changed);
    }

    @Test
    public void aMeeTubeVideoKeepsItsSound() throws Exception {
        MeeTuneEq.setPlaying(false);
        MeeTuneSpatial.setAmount(1f);
        float[] in = loudStereo();
        float[] out = run(in);
        for (int i = 0; i < in.length; i++) {
            assertEquals(in[i], out[i], 0f);
        }
    }

    /** Full-scale, different in each ear: a 440 Hz left and a 3 kHz right. */
    private static float[] loudStereo() {
        float[] pcm = new float[FRAMES * 2];
        for (int f = 0; f < FRAMES; f++) {
            pcm[f * 2] = (float) Math.sin(2 * Math.PI * 440 * f / 48_000.0);
            pcm[f * 2 + 1] = (float) Math.sin(2 * Math.PI * 3_000 * f / 48_000.0);
        }
        return pcm;
    }

    private static float[] run(float[] in) throws Exception {
        AudioProcessor processor = MeeTuneSpatial.processor();
        processor.configure(new AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT));
        processor.flush();
        ByteBuffer buffer = ByteBuffer.allocateDirect(in.length * 4).order(ByteOrder.nativeOrder());
        for (float sample : in) {
            buffer.putFloat(sample);
        }
        buffer.flip();
        processor.queueInput(buffer);
        ByteBuffer output = processor.getOutput();
        float[] out = new float[output.remaining() / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = output.getFloat();
        }
        assertEquals(in.length, out.length);
        return out;
    }
}
