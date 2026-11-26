package com.newtube.mobile.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Fixtures: the first two fragments (clusters) of real YouTube parts, 144p video and the audio
 * streams of jNQXAC9IVRw. The expected hashes are of joins whose every packet ffmpeg read back
 * identical to the parts' (framecrc: pts, dts, duration, size, crc) and that decode clean.
 */
public class FastJoinTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void mp4VideoAndAudio() throws Exception {
        assertJoin("video.mp4", "audio.m4a", false, "276c8a47ec27e65059aa8c4f3bbf8163", 19_133);
    }

    @Test
    public void m4aAudioOnly() throws Exception {
        assertJoin(null, "audio.m4a", false, "ca2af86a324462311f3cbf8b754da7b6", 19_133);
    }

    @Test
    public void webmVideoAndAudio() throws Exception {
        assertJoin("video.webm", "audio.webm", true, "b7a90080775bf54117c7c9f6d3e2eced", 19_021);
    }

    @Test
    public void unfragmentedInputIsRefusedBeforeWriting() throws Exception {
        File joined = join("video.mp4", "audio.m4a", false);
        File out = tmp.newFile();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            FastJoin.join(joined, null, false, stream.getChannel(), (bytes, total) -> true);
            fail("a classic MP4 has its samples in the header, not in fragments");
        } catch (FastJoin.Unsupported expected) {
            assertEquals(0, out.length());
        }
    }

    private void assertJoin(String video, String audio, boolean webm, String md5, long durationMs) throws Exception {
        File out = tmp.newFile();
        long ms;
        try (FileOutputStream stream = new FileOutputStream(out)) {
            ms = FastJoin.join(fixture(video), fixture(audio), webm, stream.getChannel(), (bytes, total) -> true);
        }
        assertEquals(durationMs, ms);
        assertEquals(md5, md5(out));
    }

    private File join(String video, String audio, boolean webm) throws IOException, URISyntaxException {
        File out = tmp.newFile();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            FastJoin.join(fixture(video), fixture(audio), webm, stream.getChannel(), (bytes, total) -> true);
        }
        return out;
    }

    private static File fixture(String name) throws URISyntaxException {
        return name == null ? null : new File(FastJoinTest.class.getResource("/fastjoin/" + name).toURI());
    }

    private static String md5(File file) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(Files.readAllBytes(file.toPath()));
        return String.format("%032x", new BigInteger(1, digest));
    }
}
