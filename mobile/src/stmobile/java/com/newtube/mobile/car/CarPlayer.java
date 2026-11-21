package com.newtube.mobile.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import java.util.Objects;

/**
 * MEETUBE(android auto, issue #253): what the car sees of its player. The real player, except that
 * while car lyrics are on the line being sung takes the song title's place, and the title moves
 * next to the artist. Only the car's session uses it.
 */
final class CarPlayer extends ForwardingSimpleBasePlayer {
    @Nullable private String mLine;

    CarPlayer(@NonNull Player player) {
        super(player);
    }

    /** Shows {@code line} in place of the title; null shows the song as it is. Main thread. */
    void setLine(@Nullable String line) {
        if (!Objects.equals(line, mLine)) {
            mLine = line;
            invalidateState();
        }
    }

    @NonNull
    @Override
    protected State getState() {
        State state = super.getState();
        if (mLine == null) {
            return state;
        }
        MediaMetadata song = state.currentMetadata;
        CharSequence title = song.title;
        CharSequence artist = song.artist;
        String byline = title == null ? (artist != null ? artist.toString() : null)
                : artist == null ? title.toString() : title + " · " + artist;
        MediaMetadata shown = song.buildUpon().setTitle(mLine).setDisplayTitle(mLine).setArtist(byline)
                .setSubtitle(byline).build(); // with a display title the car's second line is the subtitle
        return state.buildUpon().setPlaylist(state.timeline, state.currentTracks, shown).build();
    }
}
