package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.google.common.collect.ImmutableList;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Arrays;

/**
 * MEETUBE(issue #58): a quality picked in the sheet carries to the next videos of the session. On a
 * video without that exact stream (another codec, another frame rate, no such rung) it used to
 * leave Auto in place, which opened at 240p while the gear row still read "1080p".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3TrackAdapterVideoTest {
    private DefaultTrackSelector selector;
    private Media3TrackAdapter adapter;

    @Before
    public void setUp() {
        selector = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        adapter = new Media3TrackAdapter(selector);
    }

    private static Format video(String id, String codecs, int height, float fps, int bitrate) {
        return new Format.Builder()
                .setId(id)
                .setSampleMimeType(codecs.startsWith("vp09") ? MimeTypes.VIDEO_VP9
                        : codecs.startsWith("av01") ? MimeTypes.VIDEO_AV1 : MimeTypes.VIDEO_H264)
                .setCodecs(codecs)
                .setWidth(height * 16 / 9)
                .setHeight(height)
                .setFrameRate(fps)
                .setAverageBitrate(bitrate)
                .build();
    }

    private static Tracks tracks(Format... formats) {
        TrackGroup group = new TrackGroup("video", formats);
        int[] support = new int[formats.length];
        Arrays.fill(support, C.FORMAT_HANDLED);
        return new Tracks(ImmutableList.of(new Tracks.Group(group, true, support, new boolean[formats.length])));
    }

    /** The pick as the quality sheet stores it: a concrete track of the video it was made on. */
    private static FormatItem picked(Format format) {
        return ExoFormatItem.from(Media3FormatConverter.toMediaTrack(TrackSelectorManager.RENDERER_INDEX_VIDEO, format));
    }

    private Format videoOverride() {
        TrackSelectionOverride found = null;
        for (TrackSelectionOverride override : selector.getParameters().overrides.values()) {
            if (override.getType() == C.TRACK_TYPE_VIDEO) {
                found = override;
            }
        }
        assertNotNull("a video override (the pick), not Auto", found);
        return found.mediaTrackGroup.getFormat(found.trackIndices.get(0));
    }

    private static final Format VP9_1080_30 = video("248", "vp09.00.40.08", 1080, 30, 2_600_000);

    @Test
    public void theSameItagStillWins() {
        adapter.selectFormat(picked(VP9_1080_30));
        adapter.onTracksChanged(tracks(
                video("137", "avc1.640028", 1080, 30, 4_400_000),
                video("248", "vp09.00.40.08", 1080, 30, 2_500_000),
                video("133", "avc1.4d4015", 240, 30, 250_000)));

        assertEquals("248", videoOverride().id);
    }

    @Test
    public void aPickPlaysItsRungInAnotherCodec() {
        adapter.selectFormat(picked(VP9_1080_30));
        adapter.onTracksChanged(tracks(
                video("399", "av01.0.08M.08", 1080, 30, 1_900_000),
                video("137", "avc1.640028", 1080, 30, 4_400_000),
                video("133", "avc1.4d4015", 240, 30, 250_000)));

        assertEquals("137", videoOverride().id); // AVC before AV1, as the sheet ranks them
    }

    @Test
    public void aPickPlaysItsRungAtAnotherFrameRate() {
        adapter.selectFormat(picked(video("137", "avc1.640028", 1080, 30, 4_400_000)));
        adapter.onTracksChanged(tracks(
                video("399", "av01.0.08M.08", 1080, 25, 1_900_000),
                video("133", "avc1.4d4015", 240, 25, 250_000)));

        assertEquals("399", videoOverride().id);
    }

    /** An HLS group behind dash-mpd+hls: every variant has the same id ("1:"), the 240p first. */
    @Test
    public void aSharedIdIsNotAnExactMatch() {
        Format hls1080 = video("1:", "vp09.00.40.08", 1080, 24, 2_934_892);
        adapter.selectFormat(picked(hls1080));
        adapter.onTracksChanged(tracks(
                video("1:", "avc1.4D4015", 240, 24, 310_506),
                video("1:", "avc1.640028", 1080, 24, 4_706_990),
                video("1:", "vp09.00.20.08", 240, 24, 270_097),
                hls1080));

        assertEquals(1080, videoOverride().height);
    }

    @Test
    public void aPickAboveTheVideosBestPlaysItsBest() {
        adapter.selectFormat(picked(VP9_1080_30));
        adapter.onTracksChanged(tracks(
                video("247", "vp09.00.31.08", 720, 30, 1_400_000),
                video("242", "vp09.00.21.08", 240, 30, 220_000)));

        assertEquals("247", videoOverride().id);
    }

    /** Issue #85: a 2160p60 the decoder reports beyond it - listed apart, pinned only when picked from that list. */
    @Test
    public void aRungBeyondTheDecoderPinsOnlyWhenPickedAsSuch() {
        Format vp9_2160_60 = video("315", "vp09.00.51.08", 2160, 60, 18_000_000);
        Format vp9_1080_60 = video("303", "vp09.00.41.08", 1080, 60, 4_000_000);
        TrackGroup group = new TrackGroup("video", vp9_2160_60, vp9_1080_60);
        Tracks tracks = new Tracks(ImmutableList.of(new Tracks.Group(group, true,
                new int[] {C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_HANDLED}, new boolean[2])));
        adapter.onTracksChanged(tracks);

        assertEquals(1, adapter.getFormats(TrackSelectorManager.RENDERER_INDEX_VIDEO).size());
        assertEquals("315", adapter.getVideoFormatsBeyondDecoder().get(0).getFormatId());

        adapter.selectFormat(picked(vp9_2160_60)); // any other 2160p60 pick: the nearest playable rung
        assertEquals("303", videoOverride().id);

        FormatItem pick = adapter.getVideoFormatsBeyondDecoder().get(0);
        Media3TrackAdapter.allowBeyondDecoder(pick);
        adapter.selectFormat(pick);
        assertEquals("315", videoOverride().id);
    }
}
