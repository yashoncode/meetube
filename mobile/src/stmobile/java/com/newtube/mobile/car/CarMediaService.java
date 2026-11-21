package com.newtube.mobile.car;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.downloads.DownloadItem;
import com.newtube.mobile.downloads.DownloadRegistry;
import com.newtube.mobile.player.CarMediaSource;
import com.newtube.mobile.ui.meetune.CarMusic;
import com.newtube.mobile.ui.playback.CarLyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * MEETUBE(android auto, issue #253): MeeTune in the car, as sound only. Android Auto browses
 * MeeTune's Home, Library, Liked music and the songs downloaded from MeeTune, and plays them on
 * this service's own player: the phone's player, screens and media session never take part (when
 * the car starts a song, the phone's video pauses on its own, losing audio focus). No video ever
 * shows on the car's screen.
 *
 * <p>Media ids: {@code b|<browseId>|<params>} is a YouTube Music page, {@code <parentId>#<videoId>}
 * a song in that page (so a tap queues the page's songs from there), {@code dl} the downloads,
 * {@code mix} Made for you, {@code rec} Recents (MeeTune's History), {@code r|<videoId>} a song's radio.
 */
public final class CarMediaService extends MediaLibraryService {
    private static final String ROOT = "root";
    private static final String LIBRARY = "lib";
    private static final String DOWNLOADS = "dl";
    private static final String MIXES = "mix";
    private static final String RECENTS = "rec";
    /** The queue carried over from the phone's MeeTune. */
    private static final String PHONE = "phone";
    /** The service while a car (Android Auto, or the car's own screen) is connected to it. */
    @Nullable private static volatile CarMediaService sConnected;
    private static final int MIXES_MAX = 10;
    private static final String SEARCH = "s|";
    private static final String RADIO = "r|";
    /** A page's "Shuffle play" row: {@code <parentId>#*}. */
    private static final String SHUFFLE = "#*";
    private static final long LYRIC_TICK_MS = 250;
    /** Songs left in the queue when the radio after the last one is fetched. */
    private static final int RADIO_AHEAD = 1;

    private static final String LIKE = "meetune.car.LIKE";
    private static final String SHUFFLE_MODE = "meetune.car.SHUFFLE";
    private static final String REPEAT = "meetune.car.REPEAT";
    private static final String LYRICS = "meetune.car.LYRICS";
    private static final String START_RADIO = "meetune.car.RADIO";
    private static final String CLOSE = "meetune.car.CLOSE";
    /** Words a spoken request wraps around what to play ("play my liked songs on MeeTune"). */
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "play", "shuffle", "my", "some", "the", "on", "in", "from", "meetune", "mee", "tune", "please",
            "songs", "song", "music", "tracks", "playlist"));

    private final ListeningExecutorService mWork = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** Every row handed to the car, by id: a tap comes back as the id alone. */
    private final Map<String, MediaItem> mItems = new ConcurrentHashMap<>();
    /** The car's controllers connected now (main thread). */
    private final Set<MediaSession.ControllerInfo> mCars = new HashSet<>();
    /** Each page's songs, in order: the queue a tap on one of them plays. */
    private final Map<String, List<MediaItem>> mSongs = new ConcurrentHashMap<>();
    /** Each mix's song, by video id: the first song of its radio. */
    private final Map<String, CarMusic.Row> mSeeds = new ConcurrentHashMap<>();

    @Nullable private ExoPlayer mPlayer;
    @Nullable private DefaultTrackSelector mSelector;
    @Nullable private CarPlayer mShown;
    @Nullable private MediaLibrarySession mSession;
    /** The current song's like, as the account has it (null: unknown or signed out). */
    @Nullable private Boolean mLiked;
    /** The current song's timed lyrics, once found; {@link #mLyricsFor} says whose. */
    @Nullable private CarLyrics.Timed mLyrics;
    @Nullable private String mLyricsFor;
    /** The song whose radio was last added, so it is fetched once. */
    @Nullable private String mRadioFor;
    private final Runnable mLyricTick = this::showLyricLine;
    /** The queue position already given fresh links once after an error (-1: none). */
    private int mRetriedIndex = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        DefaultTrackSelector selector = new DefaultTrackSelector(this);
        selector.setParameters(selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true) // sound only: no video is fetched
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxAudioBitrate(CarMusic.maxAudioBitrate(this)));
        mSelector = selector;
        mPlayer = new ExoPlayer.Builder(this)
                .setTrackSelector(selector)
                .setMediaSourceFactory(new CarMediaSource.Factory(this))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                recover(error);
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                mRetriedIndex = -1;
                onSongChanged();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                showLyricLine();
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean enabled) {
                updateButtons();
            }

            @Override
            public void onRepeatModeChanged(int mode) {
                updateButtons();
            }
        });
        mShown = new CarPlayer(mPlayer);
        mSession = new MediaLibrarySession.Builder(this, mShown, new Callback()).setId("meetune-car").build();
        updateButtons();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controller) {
        return mSession;
    }

    @Override
    public void onDestroy() {
        sConnected = null;
        mCars.clear();
        mMain.removeCallbacksAndMessages(null);
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
        mWork.shutdownNow();
        super.onDestroy();
    }

    /**
     * A media 403 is routine (the first /player client's links are often refused): do what the
     * player's ErrorFixerController and the downloads do, once per song, then mint fresh links by
     * replacing the item (a new source resolves again). A second failure skips the song.
     */
    private void recover(PlaybackException error) {
        ExoPlayer player = mPlayer;
        MediaItem item = player != null ? player.getCurrentMediaItem() : null;
        if (item == null) {
            return;
        }
        int index = player.getCurrentMediaItemIndex();
        String videoId = CarMediaSource.videoId(item.mediaId);
        NetPath.log("car error video=" + videoId + " code=" + error.getErrorCodeName());
        if (index != mRetriedIndex && videoId != null && item.localConfiguration == null) {
            mRetriedIndex = index;
            mWork.execute(() -> {
                VideoInfoService.instance().anchorRouteToVideo(videoId);
                VideoInfoService.instance().markCurrentPlaybackRouteForbidden();
                YouTubeServiceManager.instance().applyNoPlaybackFix();
                mMain.post(() -> {
                    if (mPlayer == player && index < player.getMediaItemCount()) {
                        player.replaceMediaItem(index, item);
                        player.prepare();
                    }
                });
            });
        } else if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem();
            player.prepare();
        }
    }

    private final class Callback implements MediaLibrarySession.Callback {
        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller) {
            if (session.isAutomotiveController(controller) || session.isAutoCompanionController(controller)) {
                mCars.add(controller); // the phone's cast sheet offers the car now (playOnCar)
                sConnected = CarMediaService.this;
            }
            SessionCommands.Builder commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon();
            for (String action : new String[] {LIKE, SHUFFLE_MODE, REPEAT, LYRICS, START_RADIO, CLOSE}) {
                for (String state : new String[] {"", "/1", "/2"}) { // see buttons()
                    commands.add(new SessionCommand(action + state, Bundle.EMPTY));
                }
            }
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands.build())
                    .setMediaButtonPreferences(buttons())
                    .build();
        }

        @Override
        public void onDisconnected(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
            if (mCars.remove(controller) && mCars.isEmpty() && sConnected == CarMediaService.this) {
                sConnected = null;
            }
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller, @NonNull SessionCommand command, @NonNull Bundle args) {
            ExoPlayer player = mPlayer;
            if (player == null) {
                return Futures.immediateFuture(new SessionResult(SessionError.ERROR_INVALID_STATE));
            }
            String action = command.customAction;
            switch (action.contains("/") ? action.substring(0, action.indexOf('/')) : action) {
                case LIKE:
                    toggleLike();
                    break;
                case SHUFFLE_MODE:
                    player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
                    break;
                case REPEAT:
                    int mode = player.getRepeatMode();
                    player.setRepeatMode(mode == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                            : mode == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                    break;
                case LYRICS:
                    CarMusic.setLyrics(CarMediaService.this, !lyricsOn());
                    loadLyrics();
                    showLyricLine();
                    updateButtons();
                    break;
                case START_RADIO:
                    String videoId = currentVideoId();
                    if (videoId != null) {
                        addRadio(videoId, true);
                    }
                    break;
                case CLOSE: // the car's mini player goes once nothing is queued
                    player.stop();
                    player.clearMediaItems();
                    break;
                default:
                    return Futures.immediateFuture(new SessionResult(SessionError.ERROR_NOT_SUPPORTED));
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @Nullable LibraryParams params) {
            if (params != null && params.isRecent) {
                // The phone's media controls ask this to offer a "resume" card: MeeTube has none.
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED));
            }
            // The look the car allows: albums and playlists as big cover tiles, songs as a list.
            Bundle style = new Bundle();
            style.putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true);
            style.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2); // grid
            style.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1); // list
            return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "MeeTune"),
                    new LibraryParams.Builder().setExtras(style).build()));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String mediaId) {
            MediaItem item = mItems.get(mediaId);
            return Futures.immediateFuture(item != null ? LibraryResult.ofItem(item, null)
                    : LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String parentId, int page, int pageSize,
                @Nullable LibraryParams params) {
            return mWork.submit(() -> {
                List<MediaItem> rows;
                try {
                    rows = children(parentId);
                } catch (Exception e) {
                    NetPath.log("car browse failed id=" + parentId + " " + e);
                    return LibraryResult.ofError(SessionError.ERROR_IO);
                }
                int from = Math.min(rows.size(), Math.max(0, page) * Math.max(1, pageSize));
                int to = Math.min(rows.size(), from + Math.max(1, pageSize));
                return LibraryResult.ofItemList(rows.subList(from, to), params);
            });
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String query, @Nullable LibraryParams params) {
            mWork.execute(() -> {
                int count;
                try {
                    count = search(query).size();
                } catch (Exception e) {
                    NetPath.log("car search failed " + e);
                    count = 0;
                }
                session.notifySearchResultChanged(browser, query, count, params);
            });
            return Futures.immediateFuture(LibraryResult.ofVoid());
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String query, int page, int pageSize,
                @Nullable LibraryParams params) {
            List<MediaItem> rows = mSongs.get(SEARCH + query);
            if (rows == null) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
            }
            int from = Math.min(rows.size(), Math.max(0, page) * Math.max(1, pageSize));
            int to = Math.min(rows.size(), from + Math.max(1, pageSize));
            return Futures.immediateFuture(LibraryResult.ofItemList(rows.subList(from, to), params));
        }

        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> items) {
            List<MediaItem> resolved = new ArrayList<>();
            for (MediaItem item : items) {
                if (item.mediaId.endsWith(SHUFFLE)) {
                    continue; // a "Shuffle play" row is no song to queue
                }
                MediaItem known = mItems.get(item.mediaId);
                resolved.add(known != null ? known : item);
            }
            return Futures.immediateFuture(resolved);
        }

        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> items, int startIndex,
                long startPositionMs) {
            // "Play <song> on MeeTube": the Assistant sends only the words; play the songs they find.
            String spoken = items.size() == 1 && items.get(0).mediaId.isEmpty()
                    ? (String) items.get(0).requestMetadata.searchQuery : null;
            if (spoken != null) {
                return mWork.submit(() -> voice(spoken));
            }
            // "Shuffle play": the page's songs, shuffled.
            if (items.size() == 1 && items.get(0).mediaId.endsWith(SHUFFLE)) {
                String id = items.get(0).mediaId;
                List<MediaItem> songs = mSongs.get(id.substring(0, id.length() - SHUFFLE.length()));
                List<MediaItem> shuffled = songs != null ? new ArrayList<>(songs) : new ArrayList<>();
                Collections.shuffle(shuffled);
                return Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(shuffled,
                        shuffled.isEmpty() ? C.INDEX_UNSET : 0, C.TIME_UNSET));
            }
            // A tap on one song plays its page from there: next and previous walk the page.
            if (items.size() == 1) {
                String id = items.get(0).mediaId;
                int hash = id.lastIndexOf('#');
                List<MediaItem> songs = hash > 0 ? mSongs.get(id.substring(0, hash)) : null;
                for (int i = 0; songs != null && i < songs.size(); i++) {
                    if (songs.get(i).mediaId.equals(id)) {
                        return Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(songs, i, startPositionMs));
                    }
                }
            }
            return Futures.transform(onAddMediaItems(session, controller, items),
                    resolved -> new MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs),
                    MoreExecutors.directExecutor());
        }

        /** Play pressed with nothing queued (the car starting up, a steering wheel's play): Recents. */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                @NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, boolean isForPlayback) {
            return mWork.submit(() -> {
                List<MediaItem> songs = songsOf(RECENTS);
                return new MediaSession.MediaItemsWithStartPosition(songs, songs.isEmpty() ? C.INDEX_UNSET : 0, C.TIME_UNSET);
            });
        }
    }

    /**
     * "Hey Google, play ... on MeeTune": my liked songs, my downloads, my recents, my mix, a song's or
     * an artist's radio, or else what a search finds. "Shuffle ..." shuffles it. Blocking.
     */
    private MediaSession.MediaItemsWithStartPosition voice(String spoken) throws Exception {
        List<String> words = new ArrayList<>(Arrays.asList(
                spoken.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split(" ")));
        boolean shuffle = words.contains("shuffle");
        List<String> what = new ArrayList<>();
        for (String word : words) {
            if (!word.isEmpty() && !FILLER.contains(word)) {
                what.add(word);
            }
        }
        String bare = String.join(" ", what);
        List<MediaItem> songs;
        if (bare.matches("(liked|likes|like|favorites|favourites|favorite|favourite|loved)( ones)?")) {
            String likedPage = CarMusic.likedPage(this);
            songs = likedPage != null ? songsOf(page(likedPage, null)) : new ArrayList<>();
        } else if (bare.matches("downloads?|downloaded|offline|saved")) {
            songs = songsOf(DOWNLOADS);
        } else if (bare.matches("recents?|recently played|history|last played")) {
            songs = songsOf(RECENTS);
        } else if (bare.isEmpty() || bare.matches("mix|mixes|made for you|daily mix|supermix|something")) {
            children(MIXES);
            List<MediaItem> mixes = mSongs.get(MIXES + "/folders");
            songs = mixes != null && !mixes.isEmpty() ? songsOf(mixes.get(0).mediaId) : songsOf(RECENTS);
        } else if (what.size() > 1 && (what.get(0).equals("radio") || what.get(what.size() - 1).equals("radio"))) {
            what.remove(what.get(0).equals("radio") ? 0 : what.size() - 1);
            List<MediaItem> found = search(String.join(" ", what));
            songs = found.isEmpty() ? new ArrayList<>() : radioSongs(found.get(0));
        } else if (playlistNamed(bare) != null) { // "play my road trip playlist": one on the phone
            songs = songsOf(playlistNamed(bare));
        } else {
            List<String> query = new ArrayList<>();
            for (String word : words) {
                if (!word.isEmpty() && !word.equals("shuffle") && !word.equals("play") && !word.equals("meetune")) {
                    query.add(word);
                }
            }
            songs = new ArrayList<>(search(String.join(" ", query)));
        }
        if (shuffle) {
            Collections.shuffle(songs);
        }
        NetPath.log("car voice '" + spoken + "' -> '" + bare + "' songs=" + songs.size());
        return new MediaSession.MediaItemsWithStartPosition(songs, songs.isEmpty() ? C.INDEX_UNSET : 0, C.TIME_UNSET);
    }

    /** The page id of the phone library's playlist or album called {@code name}, or null. */
    @Nullable
    private String playlistNamed(String name) {
        if (name.isEmpty()) {
            return null;
        }
        for (CarMusic.Row row : CarMusic.phoneLibrary(this)) {
            if (row.browseId != null && row.title.toLowerCase(Locale.ROOT).equals(name)) {
                return page(row.browseId, row.params);
            }
        }
        return null;
    }

    /** A song, then its radio. Blocking. */
    private List<MediaItem> radioSongs(MediaItem song) throws Exception {
        String videoId = CarMediaSource.videoId(song.mediaId);
        CharSequence title = song.mediaMetadata.title;
        CharSequence artist = song.mediaMetadata.artist;
        mSeeds.put(videoId, new CarMusic.Row(videoId, title != null ? title.toString() : "",
                artist != null ? artist.toString() : null, webArt(song.mediaMetadata.artworkUri)));
        return songsOf(RADIO + videoId);
    }

    /** A folder's songs, in order, loaded if need be. Blocking. */
    private List<MediaItem> songsOf(String parentId) throws Exception {
        children(parentId);
        List<MediaItem> songs = mSongs.get(parentId);
        return songs != null ? new ArrayList<>(songs) : new ArrayList<>();
    }

    /** Blocking; throws on a network failure. */
    private List<MediaItem> children(String parentId) throws Exception {
        List<MediaItem> rows = new ArrayList<>();
        if (ROOT.equals(parentId)) { // the car's tabs (it shows four at most)
            rows.add(folder(page(CarMusic.HOME, null), getString(R.string.meetune_home), null, icon("ic_nav_home_fill"), null));
            rows.add(folder(MIXES, getString(R.string.meetune_car_made_for_you), null, icon("ic_meetune_star_filled"), null));
            rows.add(folder(RECENTS, getString(R.string.meetune_car_recents), null, icon("ic_meetune_history"), null));
            rows.add(folder(LIBRARY, getString(R.string.meetune_library), null, icon("ic_meetune_library"), null));
        } else if (LIBRARY.equals(parentId)) {
            boolean account = CarMusic.signedIn(this);
            String likedPage = CarMusic.likedPage(this); // signed out, the phone playlist the heart fills
            if (likedPage != null) {
                rows.add(folder(page(likedPage, null), getString(R.string.meetune_liked), null,
                        CarArtProvider.tile(this, "ic_meetune_heart_filled", 0xFFFF2D55, 0xFF9E0F35), null));
            }
            rows.add(folder(DOWNLOADS, getString(R.string.meetune_downloads), null,
                    CarArtProvider.tile(this, "ic_meetune_download", 0xFF2D9CFF, 0xFF1446A8), null));
            rows.add(folder(page(CarMusic.CACHED, null), getString(R.string.meetune_cached),
                    getString(R.string.meetune_cached_summary),
                    CarArtProvider.tile(this, "ic_settings_storage", 0xFF1FC98E, 0xFF0B6E5A), null));
            if (account) {
                rows.add(folder(page(CarMusic.HISTORY, null), getString(R.string.meetune_history), null,
                        CarArtProvider.tile(this, "ic_meetune_history", 0xFF9B5CFF, 0xFF4316A0), null));
            }
            // The library on this phone (no account needed), then the account's.
            for (CarMusic.Row row : CarMusic.phoneLibrary(this)) {
                if (row.browseId != null && !row.browseId.equals(likedPage)) { // that one is the tile above
                    rows.add(folder(page(row.browseId, row.params), row.title, row.subtitle, artOrTile(row.art), row.group));
                }
            }

            if (account) {
                addPage(rows, parentId, CarMusic.LIBRARY, null);
            }
        } else if (RECENTS.equals(parentId)) {
            for (CarMusic.Row row : CarMusic.recents(this)) {
                if (row.videoId != null && rows.size() < 100) {
                    rows.add(song(parentId, row.videoId, row.title, row.subtitle, row.art, null, null));
                }
            }
        } else if (MIXES.equals(parentId)) {
            if (CarMusic.signedIn(this)) {
                try {
                    addPage(rows, parentId, CarMusic.MIXED_FOR_YOU, null); // YouTube Music's own
                } catch (Exception e) {
                    NetPath.log("car mixed for you failed " + e);
                }
            }
            // A mix per artist played lately (nothing played yet: per artist on Home), each a song's radio.
            List<CarMusic.Row> seeds = CarMusic.recents(this);
            String group = getString(R.string.meetune_car_your_mixes);
            if (seeds.isEmpty()) {
                seeds = CarMusic.page(this, CarMusic.HOME, null);
                group = null;
            }
            Set<String> artists = new HashSet<>();
            List<MediaItem> mixes = new ArrayList<>();
            for (CarMusic.Row row : seeds) {
                String artist = row.subtitle != null ? row.subtitle.split(",| & | x | feat")[0].trim() : "";
                if (row.videoId == null || artist.isEmpty() || !artists.add(artist.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                mSeeds.put(row.videoId, row);
                MediaItem mix = folder(RADIO + row.videoId, getString(R.string.meetune_car_artist_mix, artist),
                        getString(R.string.meetune_car_mix_based, row.title), art(row.art), group);
                rows.add(mix);
                mixes.add(mix);
                if (mixes.size() >= MIXES_MAX) {
                    break;
                }
            }
            mSongs.put(MIXES + "/folders", mixes);
            if (rows.isEmpty()) {
                addPage(rows, parentId, CarMusic.HOME, null); // no songs to seed from: Home's playlists
            }
        } else if (parentId.startsWith(RADIO)) { // a mix: its song, then the song's radio
            String seed = parentId.substring(RADIO.length());
            CarMusic.Row first = mSeeds.get(seed);
            if (first != null) {
                rows.add(song(parentId, seed, first.title, first.subtitle, first.art, null, null));
            }
            for (CarMusic.Row row : CarMusic.radio(this, seed)) {
                if (row.videoId != null) {
                    rows.add(song(parentId, row.videoId, row.title, row.subtitle, row.art, null, null));
                }
            }
        } else if (DOWNLOADS.equals(parentId)) {
            for (DownloadItem item : DownloadRegistry.instance(this).itemsNewestFirst()) {
                if (item.song && item.isDone() && item.outputUri != null) {
                    rows.add(song(parentId, item.videoId, item.title, item.author, item.thumbUrl, item.outputUri, null));
                }
            }
        } else if (parentId.startsWith("b|")) {
            String[] parts = parentId.split("\\|", 3);
            addPage(rows, parentId, parts[1], parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null);
        }
        List<MediaItem> songs = new ArrayList<>();
        for (MediaItem row : rows) {
            mItems.put(row.mediaId, row);
            if (Boolean.TRUE.equals(row.mediaMetadata.isPlayable)) {
                songs.add(row);
            }
        }
        mSongs.put(parentId, songs);
        if (songs.size() > 1) { // a list of songs opens with "Shuffle play", as YouTube Music's does
            MediaItem shuffle = new MediaItem.Builder()
                    .setMediaId(parentId + SHUFFLE)
                    .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle(getString(R.string.meetune_car_shuffle_play))
                            .setSubtitle(getString(R.string.meetune_car_songs, songs.size()))
                            .setArtworkUri(icon("ic_meetune_shuffle"))
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build())
                    .build();
            mItems.put(shuffle.mediaId, shuffle);
            rows.add(0, shuffle);
        }
        return rows;
    }

    // ---- the car's player: buttons, lyrics, radio (all on the main thread) ----

    private boolean lyricsOn() {
        return CarMusic.lyrics(this);
    }

    @Nullable
    private String currentVideoId() {
        MediaItem item = mPlayer != null ? mPlayer.getCurrentMediaItem() : null;
        return item != null ? CarMediaSource.videoId(item.mediaId) : null;
    }

    private void onSongChanged() {
        ExoPlayer player = mPlayer;
        String videoId = currentVideoId();
        mLiked = null;
        updateButtons();
        loadLyrics();
        showLyricLine();
        if (player == null || videoId == null) {
            return;
        }
        MediaItem item = player.getCurrentMediaItem();
        if (item != null) { // into MeeTune's History, so Recents (and the phone's History) has it
            CharSequence title = item.mediaMetadata.title;
            CharSequence artist = item.mediaMetadata.artist;
            CarMusic.remember(this, videoId, title != null ? title.toString() : null,
                    artist != null ? artist.toString() : null, webArt(item.mediaMetadata.artworkUri));
            if (mSession != null) {
                mSession.notifyChildrenChanged(RECENTS, Integer.MAX_VALUE, null);
            }
        }
        if (mSelector != null) { // Streaming quality for the network the phone is on now
            mSelector.setParameters(mSelector.buildUponParameters().setMaxAudioBitrate(CarMusic.maxAudioBitrate(this)));
        }
        mWork.execute(() -> { // signed in YouTube Music's like, signed out the phone's Liked music
            try {
                boolean liked = CarMusic.liked(this, videoId);
                mMain.post(() -> {
                    if (videoId.equals(currentVideoId())) {
                        mLiked = liked;
                        updateButtons();
                    }
                });
            } catch (Exception e) {
                NetPath.log("car liked failed " + e);
            }
        });
        // Like Autoplay: near the queue's end the song's radio follows on.
        if (CarMusic.autoplay(this) && player.getRepeatMode() == Player.REPEAT_MODE_OFF
                && player.getMediaItemCount() - 1 - player.getCurrentMediaItemIndex() < RADIO_AHEAD) {
            addRadio(videoId, false);
        }
    }

    /**
     * Adds YouTube Music's radio after {@code videoId}: at the queue's end, or ({@code now}, the
     * "Start radio" button) in place of what was up next. Songs already queued are left out.
     */
    private void addRadio(String videoId, boolean now) {
        if (!now && videoId.equals(mRadioFor)) {
            return;
        }
        mRadioFor = videoId;
        mWork.execute(() -> {
            List<MediaItem> radio = new ArrayList<>();
            try {
                for (CarMusic.Row row : CarMusic.radio(this, videoId)) {
                    MediaItem song = song(RADIO + videoId, row.videoId, row.title, row.subtitle, row.art, null, null);
                    mItems.put(song.mediaId, song);
                    radio.add(song);
                }
            } catch (Exception e) {
                NetPath.log("car radio failed " + e);
                return;
            }
            mMain.post(() -> {
                ExoPlayer player = mPlayer;
                if (player == null || !videoId.equals(currentVideoId())) {
                    return;
                }
                if (now) {
                    player.removeMediaItems(player.getCurrentMediaItemIndex() + 1, player.getMediaItemCount());
                }
                Set<String> queued = new HashSet<>();
                for (int i = 0; i < player.getMediaItemCount(); i++) {
                    queued.add(CarMediaSource.videoId(player.getMediaItemAt(i).mediaId));
                }
                List<MediaItem> add = new ArrayList<>();
                for (MediaItem song : radio) {
                    if (queued.add(CarMediaSource.videoId(song.mediaId))) {
                        add.add(song);
                    }
                }
                player.addMediaItems(add);
                NetPath.log("car radio after=" + videoId + " added=" + add.size() + (now ? " now" : ""));
            });
        });
    }

    /** Whether a car is connected: the phone's cast sheet then offers it for MeeTune's song. */
    public static boolean carConnected() {
        return sConnected != null;
    }

    /**
     * Main thread, the user's pick in the cast sheet: the car carries on with what MeeTune plays on
     * the phone, the same song from the same moment, then its Up next, and the phone's player pauses.
     * False when no car is connected or the phone plays no song.
     */
    public static boolean playOnCar(@Nullable com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video,
                                    @Nullable ExoPlayer phonePlayer) {
        CarMediaService car = sConnected;
        ExoPlayer player = car != null ? car.mPlayer : null;
        if (player == null) {
            NetPath.log("car from phone: no car");
            return false;
        }
        CarMusic.Phone phone = CarMusic.takeFromPhone(video, phonePlayer);
        if (phone == null || phone.songs.isEmpty()) {
            NetPath.log("car from phone: nothing video=" + (video != null ? video.videoId : null) + " player=" + (phonePlayer != null));
            return false;
        }
        List<MediaItem> songs = new ArrayList<>();
        for (int i = 0; i < phone.songs.size(); i++) {
            CarMusic.Row row = phone.songs.get(i);
            MediaItem song = car.song(PHONE, row.videoId, row.title, row.subtitle, row.art, phone.files.get(i), null);
            car.mItems.put(song.mediaId, song);
            songs.add(song);
        }
        player.setMediaItems(songs, 0, phone.position);
        player.prepare();
        player.play();
        NetPath.log("car from phone video=" + phone.songs.get(0).videoId + " songs=" + songs.size()
                + " at=" + phone.position);
        return true;
    }

    private void toggleLike() {

        String videoId = currentVideoId();
        MediaItem item = mPlayer != null ? mPlayer.getCurrentMediaItem() : null;
        if (videoId == null || item == null || mLiked == null) {
            return; // still finding out
        }
        boolean like = !mLiked;
        mLiked = like;
        updateButtons();
        String title = item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : null;
        String artist = item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : null;
        String art = webArt(item.mediaMetadata.artworkUri);
        boolean phone = !CarMusic.signedIn(this);
        mWork.execute(() -> {
            try {
                CarMusic.like(this, videoId, title, artist, art, like);
                if (phone && mSession != null) { // the Library's Liked music tile, made by the first like
                    mMain.post(() -> mSession.notifyChildrenChanged(LIBRARY, Integer.MAX_VALUE, null));
                }
            } catch (Exception e) {
                NetPath.log("car like failed " + e);
                mMain.post(() -> {
                    if (videoId.equals(currentVideoId())) {
                        mLiked = !like;
                        updateButtons();
                    }
                });
            }
        });
    }

    private void loadLyrics() {
        String videoId = currentVideoId();
        MediaItem item = mPlayer != null ? mPlayer.getCurrentMediaItem() : null;
        if (!lyricsOn() || videoId == null || item == null || videoId.equals(mLyricsFor)) {
            return;
        }
        mLyricsFor = videoId;
        mLyrics = null;
        CharSequence title = item.mediaMetadata.title;
        CharSequence artist = item.mediaMetadata.artist;
        CarLyrics.fetch(videoId, title != null ? title.toString() : null, artist != null ? artist.toString() : null,
                timed -> {
                    NetPath.log("car lyrics video=" + videoId + " timed=" + (timed != null));
                    if (videoId.equals(mLyricsFor)) {
                        mLyrics = timed;
                        showLyricLine();
                    }
                });
    }

    /** Puts the line being sung in the title's place, while lyrics are on and the song plays. */
    private void showLyricLine() {
        mMain.removeCallbacks(mLyricTick);
        ExoPlayer player = mPlayer;
        CarPlayer shown = mShown;
        if (player == null || shown == null) {
            return;
        }
        CarLyrics.Timed lyrics = mLyrics;
        if (!lyricsOn() || lyrics == null || mLyricsFor == null || !mLyricsFor.equals(currentVideoId())) {
            shown.setLine(null);
            return;
        }
        shown.setLine(lyrics.lineAt(player.getCurrentPosition()));
        if (player.isPlaying()) {
            mMain.postDelayed(mLyricTick, LYRIC_TICK_MS);
        }
    }

    /** The car player's own buttons: like, shuffle, repeat, lyrics and radio. */
    private List<CommandButton> buttons() {
        List<CommandButton> list = new ArrayList<>();
        ExoPlayer player = mPlayer;
        if (player == null) {
            return list;
        }
        // A button's action names its state too ("/1"): the car redraws a button only when its action
        // is new, so a heart that kept its action stayed empty after a like until Now Playing reopened.
        boolean liked = Boolean.TRUE.equals(mLiked); // always there, signed in or not (an empty heart until known)
        list.add(button(liked ? CommandButton.ICON_HEART_FILLED : CommandButton.ICON_HEART_UNFILLED,
                liked ? R.string.meetune_car_unlike : R.string.meetune_like, liked ? LIKE + "/1" : LIKE));
        boolean shuffled = player.getShuffleModeEnabled();
        list.add(button(shuffled ? CommandButton.ICON_SHUFFLE_ON : CommandButton.ICON_SHUFFLE_OFF,
                shuffled ? R.string.meetune_car_shuffle_off : R.string.meetune_shuffle,
                shuffled ? SHUFFLE_MODE + "/1" : SHUFFLE_MODE));
        int repeat = player.getRepeatMode();
        list.add(button(repeat == Player.REPEAT_MODE_ALL ? CommandButton.ICON_REPEAT_ALL
                        : repeat == Player.REPEAT_MODE_ONE ? CommandButton.ICON_REPEAT_ONE : CommandButton.ICON_REPEAT_OFF,
                repeat == Player.REPEAT_MODE_ONE ? R.string.meetune_car_repeat_off : R.string.meetune_car_repeat,
                repeat == Player.REPEAT_MODE_OFF ? REPEAT : REPEAT + "/" + repeat));
        boolean lyrics = lyricsOn();
        list.add(button(lyrics ? CommandButton.ICON_SUBTITLES : CommandButton.ICON_SUBTITLES_OFF,
                lyrics ? R.string.meetune_car_lyrics_hide : R.string.meetune_car_lyrics_show, lyrics ? LYRICS + "/1" : LYRICS));

        list.add(button(CommandButton.ICON_RADIO, R.string.meetune_start_radio, START_RADIO));
        list.add(button(CommandButton.ICON_STOP, R.string.meetune_car_close, CLOSE));
        return list;
    }

    private CommandButton button(int icon, int name, String action) {
        return new CommandButton.Builder(icon).setDisplayName(getString(name))
                .setSessionCommand(new SessionCommand(action, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW).build();
    }

    private void updateButtons() {
        if (mSession != null) {
            mSession.setMediaButtonPreferences(buttons());
        }
    }

    /** Blocking. The songs a query finds, kept like a page's so a tap queues them from there. */
    private List<MediaItem> search(String query) throws Exception {
        String parentId = SEARCH + query;
        List<MediaItem> songs = new ArrayList<>();
        for (CarMusic.Row row : CarMusic.searchSongs(this, query)) {
            if (row.videoId != null) {
                MediaItem song = song(parentId, row.videoId, row.title, row.subtitle, row.art, null, null);
                mItems.put(song.mediaId, song);
                songs.add(song);
            }
        }
        mSongs.put(parentId, songs);
        return songs;
    }

    private void addPage(List<MediaItem> rows, String parentId, String browseId, @Nullable String params) throws Exception {
        for (CarMusic.Row row : CarMusic.page(this, browseId, params)) {
            rows.add(row.videoId != null
                    ? song(parentId, row.videoId, row.title, row.subtitle, row.art, null, row.group)
                    : folder(page(row.browseId, row.params), row.title, row.subtitle, artOrTile(row.art), row.group));
        }
    }

    /** The car reads art only from a content URI, never a web link or a file of this app. */
    @Nullable
    private Uri art(@Nullable String url) {
        return url != null && url.startsWith("https://") ? CarArtProvider.uri(this, url) : null;
    }

    /** The web link a row's art was made from (see {@link #art}), for MeeTune's History. */
    @Nullable
    private static String webArt(@Nullable Uri art) {
        return art != null && "content".equals(art.getScheme()) && art.getPathSegments().size() == 1
                ? art.getLastPathSegment() : null;
    }

    /** A folder's cover, or (a playlist with no songs yet) a tile. */
    private Uri artOrTile(@Nullable String url) {
        Uri art = art(url);
        return art != null ? art : CarArtProvider.tile(this, "ic_meetune_library", 0xFF5A5F6E, 0xFF23262F);
    }

    /** One of this app's white icons, by name: a car host reads android.resource://<package>/<type>/<name>. */
    private Uri icon(String name) {
        return Uri.parse("android.resource://" + getPackageName() + "/drawable/" + name);
    }

    /** Rows with the same heading are drawn as one section under it. */
    @Nullable
    private static Bundle group(@Nullable String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        Bundle extras = new Bundle();
        extras.putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", title);
        return extras;
    }

    private static String page(String browseId, @Nullable String params) {
        return "b|" + browseId + "|" + (params != null ? params : "");
    }

    private MediaItem folder(String id, String title) {
        return folder(id, title, null, null, null);
    }

    private MediaItem folder(String id, String title, @Nullable String subtitle, @Nullable Uri art,
                             @Nullable String group) {
        return new MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setArtworkUri(art)
                        .setExtras(group(group))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build())
                .build();
    }

    private MediaItem song(String parentId, String videoId, @Nullable String title, @Nullable String artist,
                           @Nullable String art, @Nullable String fileUri, @Nullable String group) {
        MediaItem.Builder builder = new MediaItem.Builder()
                .setMediaId(parentId + "#" + videoId)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(art(art))
                        .setExtras(group(group))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build());
        String local = fileUri != null ? fileUri : CarMusic.cachedUri(this, videoId);
        if (local != null) {
            builder.setUri(local); // a download, or a song whole in the cache: played from the phone
        }
        return builder.build();
    }
}
