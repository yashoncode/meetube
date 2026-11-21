package com.newtube.mobile.ui.meetune;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MEETUBE(android auto, issue #253): a YouTube Music page as rows for the car's lists. A song is a
 * row to play; an album, playlist, artist or mood is a row that opens its own page. Read only:
 * MeeTune's screens never call it.
 */
public final class CarMusic {
    public static final String HOME = "FEmusic_home";
    public static final String LIKED = "VLLM";
    public static final String HISTORY = "FEmusic_history";
    public static final String LIBRARY = "FEmusic_library_landing";
    /** YouTube Music's own mixes for the account (empty when signed out). */
    public static final String MIXED_FOR_YOU = "FEmusic_mixed_for_you";
    /** The songs whole in the player's cache: they play with no network. */
    public static final String CACHED = MeeTune.CACHED;

    public static final class Row {
        @NonNull public final String title;
        @Nullable public final String subtitle;
        @Nullable public final String art;
        /** Set for a song. */
        @Nullable public final String videoId;
        /** Set for a page (with {@link #params}) when {@link #videoId} is null. */
        @Nullable public final String browseId;
        @Nullable public final String params;
        /** The shelf it sits on ("Quick picks", "Listen again"): the car's section heading. */
        @Nullable public final String group;

        /** A song the car already has (its art a web link). */
        public Row(@NonNull String videoId, @NonNull String title, @Nullable String subtitle, @Nullable String art) {
            this.videoId = videoId;
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            browseId = null;
            params = null;
            group = null;
        }

        Row(MusicApi.Item item, @Nullable String group) {
            this.group = group;
            title = item.title;
            subtitle = item.artist != null ? item.artist : item.subtitle;
            art = item.art != null ? MusicApi.sized(item.art, 544) : null;
            videoId = item.videoId;
            browseId = item.videoId == null ? item.browseId : null;
            params = item.videoId == null ? item.params : null;
        }
    }

    private CarMusic() {
    }

    /** The page's songs and links, top to bottom, each once. Blocking; throws on a network failure. */
    @NonNull
    public static List<Row> page(@NonNull Context context, @NonNull String browseId, @Nullable String params)
            throws Exception {
        if (MeeTuneLibrary.isPlaylist(browseId)) {
            return rows(MeeTuneLibrary.songs(context, browseId)); // a playlist made on the phone
        }
        if (CACHED.equals(browseId)) {
            return rows(MeeTuneSongCache.songs(context));
        }
        return rows(MusicApi.browse(context, browseId, params));
    }

    /** MeeTune's library on this phone, no account needed: its playlists, and albums and playlists saved. */
    @NonNull
    public static List<Row> phoneLibrary(@NonNull Context context) {
        MusicApi.Shelf shelf = MeeTuneLibrary.shelf(context);
        shelf.items.removeIf(item -> MeeTuneLibrary.NEW.equals(item.browseId));
        List<MusicApi.Shelf> shelves = new ArrayList<>();
        shelves.add(shelf);
        return rows(shelves);
    }

    /** Where a song whole in the player's cache plays from with no network, or null. */
    @Nullable
    public static String cachedUri(@NonNull Context context, @NonNull String videoId) {
        return MeeTuneSongCache.cachedUri(context, videoId);
    }

    /** YouTube Music's songs for a spoken or typed query, best match first. Blocking. */
    @NonNull
    public static List<Row> searchSongs(@NonNull Context context, @NonNull String query) throws Exception {
        return rows(MusicApi.search(context, query, MusicApi.FILTERS[1]));
    }

    /** YouTube Music's radio after a song, the song itself left out. Blocking. */
    @NonNull
    public static List<Row> radio(@NonNull Context context, @NonNull String videoId) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (MusicApi.Item item : MusicApi.radio(context, videoId, "RDAMVM" + videoId)) {
            if (!videoId.equals(item.videoId)) {
                rows.add(new Row(item, null));
            }
        }
        return rows;
    }

    public static boolean signedIn(@NonNull Context context) {
        return MusicApi.signedIn(context);
    }

    /** Blocking. Signed out, whether the song is in the phone's Liked music playlist. */
    public static boolean liked(@NonNull Context context, @NonNull String videoId) throws Exception {
        if (signedIn(context)) {
            return MusicApi.liked(context, videoId);
        }
        String playlist = phoneLiked(context, false);
        if (playlist != null) {
            for (MusicApi.Shelf shelf : MeeTuneLibrary.songs(context, playlist)) {
                for (MusicApi.Item item : shelf.items) {
                    if (videoId.equals(item.videoId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Blocking: likes the song, or takes the like back. Signed in on YouTube Music; signed out (as
     * with no account) in a phone playlist, Liked music, made at the first like.
     */
    public static void like(@NonNull Context context, @NonNull String videoId, @Nullable String title,
                            @Nullable String artist, @Nullable String art, boolean like) throws Exception {
        if (signedIn(context)) {
            MusicApi.rate(context, videoId, like ? "like" : "removelike");
            return;
        }
        String playlist = phoneLiked(context, like);
        if (playlist == null) {
            return;
        }
        if (like) {
            MusicApi.Item song = new MusicApi.Item();
            song.kind = MusicApi.SONG;
            song.videoId = videoId;
            song.title = title;
            song.artist = artist;
            song.art = art;
            List<MusicApi.Item> one = new ArrayList<>();
            one.add(song);
            MeeTuneLibrary.add(context, playlist, one);
        } else {
            MeeTuneLibrary.remove(context, playlist, videoId);
        }
    }

    /** The page Liked music opens: YouTube Music's, or signed out the phone's (null before a first like). */
    @Nullable
    public static String likedPage(@NonNull Context context) {
        return signedIn(context) ? LIKED : phoneLiked(context, false);
    }

    /** The phone playlist the car's likes go in, made if {@code make} (again, if it was deleted). */
    @Nullable
    private static synchronized String phoneLiked(Context context, boolean make) {
        String id = MeeTune.prefs(context).getString("car_liked", null);
        if (id != null && MeeTuneLibrary.exists(context, id)) {
            return id;
        }
        if (!make) {
            return null;
        }
        id = MeeTuneLibrary.create(context, context.getString(R.string.meetune_liked));
        MeeTune.prefs(context).edit().putString("car_liked", id).apply();
        return id;
    }

    /** What MeeTune was playing on the phone when it was sent to the car. */
    public static final class Phone {
        /** The song, then its Up next (queued songs first, then the radio's). */
        public final List<Row> songs = new ArrayList<>();
        /** Per song: the downloaded file it plays from, or null. */
        public final List<String> files = new ArrayList<>();
        public long position;
    }

    /**
     * Main thread. The car carries on with MeeTune's song from the phone: the song, where it is, and
     * its Up next; the phone's player pauses. Null (the phone left alone) when it has no song.
     */
    @Nullable
    public static Phone takeFromPhone(@Nullable com.liskovsoft.smartyoutubetv2.common.app.models.data.Video playing,
                                      @Nullable androidx.media3.exoplayer.ExoPlayer player) {
        if (playing == null || player == null || !MeeTune.isMusic(playing)) {
            return null;
        }
        List<com.liskovsoft.smartyoutubetv2.common.app.models.data.Video> order = new ArrayList<>();
        order.add(playing);
        List<com.liskovsoft.smartyoutubetv2.common.app.models.data.Video> queued =
                com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist.instance().getAllAfterCurrent();
        if (queued != null) { // null with nothing queued
            order.addAll(queued);
        }
        List<com.liskovsoft.smartyoutubetv2.common.app.models.data.Video> upNext = MeeTune.upNext(playing);
        if (upNext != null) {
            order.addAll(upNext);
        }
        Phone phone = new Phone();
        Set<String> seen = new LinkedHashSet<>();
        for (com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video : order) {
            if (video == null || video.videoId == null || !MeeTune.isMusic(video) || !seen.add(video.videoId)) {
                continue; // a MeeTube video queued after the song stays on the phone
            }
            MusicApi.Item item = MeeTune.item(video);
            String art = item.art != null ? item.art : MeeTune.art(video);
            phone.songs.add(new Row(video.videoId, item.title != null ? item.title : "",
                    item.artist != null ? item.artist : MeeTune.artist(item.subtitle), MusicApi.sized(art, 544)));
            phone.files.add(video.localUri);
        }
        phone.position = player.getCurrentPosition();
        player.pause();
        return phone;
    }

    /** The songs played in MeeTune on this phone, newest first: MeeTune's History. */
    @NonNull
    public static List<Row> recents(@NonNull Context context) {
        return rows(MeeTune.history(context));
    }

    /** A song the car played goes into MeeTune's History too. Main thread. */
    public static void remember(@NonNull Context context, @NonNull String videoId, @Nullable String title,
                                @Nullable String artist, @Nullable String art) {
        MeeTune.remember(context, videoId, title, artist, art);
    }

    /** Car settings (MeeTune's settings page has them): similar songs after the queue's end. */
    public static boolean autoplay(@NonNull Context context) {
        return MeeTune.prefs(context).getBoolean("car_autoplay", true);
    }

    static void setAutoplay(@NonNull Context context, boolean on) {
        MeeTune.prefs(context).edit().putBoolean("car_autoplay", on).apply();
    }

    /** The line being sung in the title's place on the car's screen. */
    public static boolean lyrics(@NonNull Context context) {
        return MeeTune.prefs(context).getBoolean("car_lyrics", false);
    }

    public static void setLyrics(@NonNull Context context, boolean on) {
        MeeTune.prefs(context).edit().putBoolean("car_lyrics", on).apply();
    }

    /** The sound the car fetches at most: MeeTune's Streaming quality for the network it is on. */
    public static int maxAudioBitrate(@NonNull Context context) {
        return MeeTune.maxAudioBitrate(context);
    }

    private static List<Row> rows(List<MusicApi.Shelf> shelves) {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MusicApi.Shelf shelf : shelves) {
            if (shelf.header) {
                continue; // the album or artist itself, already the page the car is on
            }
            for (MusicApi.Item item : shelf.items) {
                String key = item.videoId != null ? "v" + item.videoId : "b" + item.browseId + "|" + item.params;
                if (item.title != null && (item.videoId != null || item.browseId != null) && seen.add(key)) {
                    rows.add(new Row(item, shelf.title));
                }
            }
        }
        return rows;
    }
}
