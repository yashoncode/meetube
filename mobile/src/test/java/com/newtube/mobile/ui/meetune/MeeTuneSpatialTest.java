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
        MeeTuneEq.setPlaying(false);
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
