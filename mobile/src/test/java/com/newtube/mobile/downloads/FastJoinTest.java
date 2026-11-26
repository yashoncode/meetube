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
import java.nio.ByteBuffer;
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
            FastJoin.join(joined, null, false, 0, 0, stream.getChannel(), (bytes, total) -> true);
            fail("a classic MP4 has its samples in the header, not in fragments");
        } catch (FastJoin.Unsupported expected) {
            assertEquals(0, out.length());
        }
    }

    /** A part download holds the header and only the fragments that cover the part (ClipRanges): same cut. */
    @Test
    public void mp4PartFromAFileThatStartsMidStream() throws Exception {
        File part = midStreamPart();
        String md5 = "ae29b41384035c9b5e0fcf4ac4b64d61";
        assertEquals(md5, md5(cut(fixture("video.mp4"), fixture("audio.m4a"), false, 6_000, 12_000, 5_851)));
        assertEquals(md5, md5(cut(part, fixture("audio.m4a"), false, 6_000, 12_000, 5_851)));
    }

    @Test
    public void webmPart() throws Exception {
        assertEquals("cec4b031050d4ce1e00eed165b8fa7d8",
                md5(cut(fixture("video.webm"), fixture("audio.webm"), true, 6_000, 12_000, 5_799)));
    }

    @Test
    public void m4aPart() throws Exception {
        assertEquals("c29b99c5a8efbfa673c77b2f6f233e4c", md5(cut(null, fixture("audio.m4a"), false, 3_000, 8_000, 5_015)));
    }

    @Test
    public void wholeJoinOfAMidStreamFileIsRefusedBeforeWriting() throws Exception {
        File out = tmp.newFile();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            FastJoin.join(midStreamPart(), null, false, 0, 0, stream.getChannel(), (bytes, total) -> true);
            fail("without a part to cut, a file that starts mid-stream would play from the wrong time");
        } catch (FastJoin.Unsupported expected) {
            assertEquals(0, out.length());
        }
    }

    private void assertJoin(String video, String audio, boolean webm, String md5, long durationMs) throws Exception {
        assertEquals(md5, md5(cut(fixture(video), fixture(audio), webm, 0, 0, durationMs)));
    }

    private File cut(File video, File audio, boolean webm, long startMs, long endMs, long durationMs) throws Exception {
        File out = tmp.newFile();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            assertEquals(durationMs, FastJoin.join(video, audio, webm, startMs * 1000, endMs * 1000,
                    stream.getChannel(), (bytes, total) -> true));
        }
        return out;
    }

    /** video.mp4's header and its second fragment (5.4 s on), without the first or the sidx. */
    private File midStreamPart() throws Exception {
        byte[] whole = Files.readAllBytes(fixture("video.mp4").toPath());
        ByteBuffer b = ByteBuffer.wrap(whole);
        int sidx = -1;
        int secondMoof = -1;
        int moofs = 0;
        for (int o = 0; o < whole.length; o += b.getInt(o)) {
            int type = b.getInt(o + 4);
            if (type == 0x73696478) {
                sidx = o;
            } else if (type == 0x6d6f6f66 && ++moofs == 2) {
                secondMoof = o;
            }
        }
        File part = tmp.newFile();
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(whole, 0, sidx);
            out.write(whole, secondMoof, whole.length - secondMoof);
        }
        return part;
    }

    private File join(String video, String audio, boolean webm) throws IOException, URISyntaxException {
        File out = tmp.newFile();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            FastJoin.join(fixture(video), fixture(audio), webm, 0, 0, stream.getChannel(), (bytes, total) -> true);
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
