package com.newtube.mobile.downloads;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Joins a DASH video part and a DASH audio part into one ordinary MP4 (or WebM, for the VP9 +
 * Opus rungs above 1080p) without re-encoding, by
 * copying samples through {@link MediaExtractor} -> {@link MediaMuxer}. The two YouTube parts
 * are fragmented MP4 (moof/mdat with a sidx), which the platform extractor reads natively; the
 * muxer rewrites them as a classic moov-indexed file that any player and the gallery accept.
 *
 * <p>Samples are interleaved by presentation time so the output plays from a slow disk without
 * seeking back and forth between a video block and an audio block.
 */
final class MediaMuxHelper {
    /** Sample sizes are bounded by the largest keyframe; 4K VP9 keyframes stay well under this. */
    private static final int VIDEO_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final int AUDIO_BUFFER_BYTES = 1024 * 1024;

    interface Progress {
        /** @return false to abort. */
        boolean onProgress(long presentationTimeUs, long durationUs);
    }

    private MediaMuxHelper() {
    }

    /**
     * @return the media duration in ms when the tracks report it, else 0
     */
    static long mux(@Nullable File videoPart, @Nullable File audioPart, MediaMuxer muxer, Progress progress)
            throws IOException {
        return mux(videoPart, audioPart, muxer, 0, 0, 0, 0, progress);
    }

    /**
     * Same, keeping only {@code clipStartUs..clipEndUs} when {@code clipEndUs > clipStartUs} (issue #241).
     * No re-encoding, so the cut starts on the video keyframe at or before the start (a few seconds
     * early at most); every timestamp is shifted so the file starts at zero.
     *
     * <p>{@code videoPartStartUs}/{@code audioPartStartUs}: where a part file that holds only some
     * fragments ({@link ClipRanges}) starts in the stream, else 0. The fragments carry their stream
     * time, but should an extractor count from the file's first sample instead, that start is added
     * back, so the cut and the audio's sync hold either way.
     */
    static long mux(@Nullable File videoPart, @Nullable File audioPart, MediaMuxer muxer,
                    long clipStartUs, long clipEndUs, long videoPartStartUs, long audioPartStartUs,
                    Progress progress) throws IOException {
        Track video = videoPart != null ? Track.open(videoPart, "video/", VIDEO_BUFFER_BYTES) : null;
        Track audio = audioPart != null ? Track.open(audioPart, "audio/", AUDIO_BUFFER_BYTES) : null;

        if (video == null && audio == null) {
            throw new IOException("no tracks to mux");
        }
        if (video != null) {
            video.placeAt(videoPartStartUs);
        }
        if (audio != null) {
            audio.placeAt(audioPartStartUs);
        }

        try {
            long durationUs = 0;
            if (video != null) {
                video.muxerTrack = muxer.addTrack(video.format);
                durationUs = Math.max(durationUs, video.durationUs());
            }
            if (audio != null) {
                audio.muxerTrack = muxer.addTrack(audio.format);
                durationUs = Math.max(durationUs, audio.durationUs());
            }

            long baseUs = 0;
            long endUs = Long.MAX_VALUE;
            if (clipEndUs > clipStartUs) {
                baseUs = clipStartUs;
                if (video != null) {
                    video.seekTo(clipStartUs);
                    baseUs = Math.max(0, video.time());
                }
                if (audio != null) {
                    audio.seekTo(baseUs);
                    // The muxer refuses negative times: drop audio from before the first frame.
                    while (audio.time() >= 0 && audio.time() < baseUs) {
                        audio.extractor.advance();
                    }
                }
                endUs = clipEndUs;
                durationUs = (durationUs > 0 ? Math.min(durationUs, clipEndUs) : clipEndUs) - baseUs;
            }
            if (video != null) {
                video.baseUs = baseUs;
                video.endUs = endUs;
                video.endOnKeyframe = true; // frames after a cut mid-GOP could reference a dropped one
            }
            if (audio != null) {
                audio.baseUs = baseUs;
                // With video, audio ends where the video's last GOP does (set when it finishes).
                audio.endUs = video != null ? Long.MAX_VALUE : endUs;
            }

            muxer.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastReportUs = -1;
            while (true) {
                Track next = pickNext(video, audio);
                if (next == null) {
                    break;
                }
                if (!next.writeSample(muxer, info)) {
                    next.finished = true;
                    if (next == video && audio != null && video.stoppedAtUs >= 0) {
                        audio.endUs = video.stoppedAtUs;
                        durationUs = video.stoppedAtUs - video.baseUs;
                    }
                    continue;
                }
                if (info.presentationTimeUs - lastReportUs >= 2_000_000 || lastReportUs < 0) {
                    lastReportUs = info.presentationTimeUs;
                    if (!progress.onProgress(info.presentationTimeUs, durationUs)) {
                        throw new IOException("cancelled");
                    }
                }
            }

            muxer.stop();
            return durationUs / 1000;
        } finally {
            if (video != null) {
                video.release();
            }
            if (audio != null) {
                audio.release();
            }
        }
    }

    /** The unfinished track whose next sample is earliest. */
    @Nullable
    private static Track pickNext(@Nullable Track a, @Nullable Track b) {
        boolean aLive = a != null && !a.finished;
        boolean bLive = b != null && !b.finished;
        if (aLive && bLive) {
            return a.time() <= b.time() ? a : b;
        }
        if (aLive) {
            return a;
        }
        return bLive ? b : null;
    }

    private static final class Track {
        final MediaExtractor extractor;
        final MediaFormat format;
        final ByteBuffer buffer;
        int muxerTrack = -1;
        boolean finished;
        long baseUs;
        long endUs = Long.MAX_VALUE;
        boolean endOnKeyframe;
        long stoppedAtUs = -1;
        long shiftUs;

        private Track(MediaExtractor extractor, MediaFormat format, int bufferBytes) {
            this.extractor = extractor;
            this.format = format;
            this.buffer = ByteBuffer.allocateDirect(bufferBytes);
        }

        static Track open(File file, String mimePrefix, int bufferBytes) throws IOException {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith(mimePrefix)) {
                        extractor.selectTrack(i);
                        int maxInput = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
                        return new Track(extractor, format, Math.max(bufferBytes, maxInput));
                    }
                }
            } catch (IOException | RuntimeException e) {
                extractor.release();
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
            extractor.release();
            throw new IOException("no " + mimePrefix + "* track in " + file.getName());
        }

        /** Counts from {@code partStartUs} when the extractor counted this part file from zero. */
        void placeAt(long partStartUs) {
            long first = extractor.getSampleTime();
            if (partStartUs > 1_000_000 && first >= 0 && first < partStartUs / 2) {
                shiftUs = partStartUs;
            }
        }

        /** The sample's stream time; -1 at the end. */
        long time() {
            long raw = extractor.getSampleTime();
            return raw < 0 ? raw : raw + shiftUs;
        }

        void seekTo(long streamUs) {
            extractor.seekTo(Math.max(0, streamUs - shiftUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }

        long durationUs() {
            return format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
        }

        /** @return false when the track is exhausted */
        boolean writeSample(MediaMuxer muxer, MediaCodec.BufferInfo info) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            long timeUs = time();
            if (size < 0) {
                return false;
            }
            boolean sync = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            if (timeUs >= endUs && (sync || !endOnKeyframe)) {
                stoppedAtUs = timeUs;
                return false;
            }
            if (timeUs < baseUs) {
                // An open GOP's leading frames sit before the cut: the muxer refuses negative times.
                info.size = 0;
                info.presentationTimeUs = 0;
                extractor.advance();
                return true;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = timeUs - baseUs;
            info.flags = sync ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(muxerTrack, buffer, info);
            extractor.advance();
            return true;
        }

        void release() {
            extractor.release();
        }
    }
}
