package com.newtube.mobile.ui.playback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * MEETUBE(android auto, issue #253): the car's read-only door to the player's lyrics search. It
 * hands back timed lyrics only (a line the car can show while it is sung); the player's own lyrics
 * screens never use it.
 */
public final class CarLyrics {
    /** A song's lines with each one's start in ms. */
    public static final class Timed {
        @NonNull public final List<String> lines;
        @NonNull public final long[] times;

        Timed(@NonNull List<String> lines, @NonNull long[] times) {
            this.lines = lines;
            this.times = times;
        }

        /** The line sung at {@code positionMs}, or null before the first one or in a gap. */
        @Nullable
        public String lineAt(long positionMs) {
            String line = null;
            for (int i = 0; i < times.length && i < lines.size() && times[i] <= positionMs; i++) {
                line = lines.get(i);
            }
            return line == null || line.trim().isEmpty() ? null : line;
        }
    }

    private CarLyrics() {
    }

    /** {@code result} gets the song's timed lyrics on the main thread, or null when none are timed. */
    public static void fetch(@NonNull String videoId, @Nullable String title, @Nullable String artist,
                             @NonNull Consumer<Timed> result) {
        Lyrics.fetch(videoId, title != null ? title : "", artist != null ? artist : "", song -> {
            Lyrics lyrics = song != null ? song.lyrics(song.shown) : null;
            result.accept(lyrics != null && lyrics.times != null ? new Timed(lyrics.lines, lyrics.times) : null);
        });
    }
}
