package com.newtube.mobile.ui.meetune;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Motion;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MEETUNE: the music side of MeeTube. Its own settings (prefs "meetune"), its worker, and the
 * music session the player reads: a song played from MeeTune carries a playlist id (the album's,
 * or YouTube Music's radio "RDAMVM" + id), and every video of that playlist - the radio's next
 * songs too - opens in the music player instead of the video one.
 */
public final class MeeTune {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(3);
    /** Songs MeeTune handed the player as its radio's next: music, even with no playlist in common. */
    private static final java.util.Set<String> RADIO_NEXT = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    static { // a song never lands in MeeTube's (YouTube's) History, only in MeeTune's own
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoStateController.sOwnHistory = MeeTune::isMusic;
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController.sMusicNext = (playing, usual) -> {
            if (!isMusic(playing)) return usual; // a MeeTube video: nothing changes
            Video next = usual;
            if (usual == null || !usual.fromQueue) { // the listener's own queue first, then the radio
                List<Video> radio = upNext(playing);
                if (!radio.isEmpty()) next = radio.get(0);
            }
            if (next != null && next.videoId != null) RADIO_NEXT.add(next.videoId); // it plays as a song too
            return next;
        };
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Square art of the songs MeeTune showed, by video id; the player's cover. */
    private static final Map<String, String> ART = new HashMap<>();
    /** The songs MeeTune started or queued, by video id: the music player's menu (album, artist). */
    private static final Map<String, MusicApi.Item> ITEMS = new HashMap<>();
    /** A page the music player's menu asked for, opened when MeeTune is back on screen. */
    @Nullable
    private static String sOpenOnReturn;
    @Nullable
    private static String sPlaylistId;
    /** Songs put in the queue from MeeTune's menu (Play next, Add to queue): they play as MeeTune's too. */
    private static final java.util.Set<String> QUEUED = new java.util.HashSet<>();
    @Nullable
    private static String sVideoId;

    interface Result<T> {
        void done(@Nullable T value, @Nullable Exception error);
    }

    interface Task<T> {
        T run() throws Exception;
    }

    private MeeTune() {
    }

    /** MeeTube to MeeTune: MeeTube blurs and dims away, MeeTune comes in out of the blur. */
    public static void open(Activity from) {
        sHome = null; // a new Home each time MeeTune opens, never the last one again
        closeHomeHint(from); // switched once: the "tap the icon" hint has done its job
        prefetch(from); // Home loads while MeeTube blurs away
        pausePlaying(false); // a MeeTube video doesn't play on into MeeTune
        blurAway(from, () -> {
            from.startActivity(new Intent(from, MeeTuneActivity.class));
            from.overridePendingTransition(R.anim.meetune_switch_enter, R.anim.mobile_stay);
        });
    }

    /** MeeTune back to MeeTube, the same way round: MeeTune blurs away, MeeTube comes back into focus. */
    static void leave(Activity meetune) {
        pausePlaying(true); // a MeeTune song doesn't play on into MeeTube
        closeMeeTubeHint(meetune); // switched back once: its hint has done its job
        blurAway(meetune, () -> {
            // Like issue #171's way back to Browse: the player MeeTune expanded sits between Browse and
            // MeeTune, and a bare finish() put the song's full player on screen over MeeTube.
            meetune.startActivity(new Intent(meetune, com.newtube.mobile.ui.browse.MobileBrowseActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            meetune.finish();
            meetune.overridePendingTransition(0, R.anim.mobile_fade_out);
        });
    }

    /** Pauses the minimized player when it plays the other side's: a MeeTune song ({@code music}) or a MeeTube video. Its card stays, to resume. */
    private static void pausePlaying(boolean music) {
        Video playing = com.newtube.mobile.ui.playback.MiniPlayerBridge.getVideo();
        androidx.media3.exoplayer.ExoPlayer player = com.newtube.mobile.ui.playback.MiniPlayerBridge.getPlayer();
        if (playing != null && player != null && isMusic(playing) == music) {
            player.pause();
        }
    }

    /** The screen being left blurs and dims to its ground, then {@code then}; when it shows again it frosts back in. */
    private static void blurAway(Activity from, Runnable then) {
        View content = from.findViewById(android.R.id.content);
        if (content.getTag(R.id.meetune_switching) != null) return; // a second tap mid-switch
        content.setTag(R.id.meetune_switching, true);
        float radius = 22 * content.getResources().getDisplayMetrics().density;
        ValueAnimator away = ValueAnimator.ofFloat(0f, 1f).setDuration(190);
        away.setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        away.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            content.setAlpha(1f - 0.75f * f);
            if (Build.VERSION.SDK_INT >= 31 && f > 0.01f) {
                content.setRenderEffect(RenderEffect.createBlurEffect(radius * f, radius * f, Shader.TileMode.CLAMP));
            }
        });
        away.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                then.run();
            }
        });
        // Coming back (MeeTube after MeeTune), the blurred screen comes into focus again.
        ((LifecycleOwner) from).getLifecycle().addObserver(new DefaultLifecycleObserver() {
            private boolean mLeft;

            @Override
            public void onStop(LifecycleOwner owner) {
                mLeft = true;
            }

            @Override
            public void onResume(LifecycleOwner owner) {
                if (!mLeft) return; // the catch-up call when added
                owner.getLifecycle().removeObserver(this);
                content.setTag(R.id.meetune_switching, null);
                Motion.frostIn(content);
            }
        });
        away.start();
    }

    // ----------------------------------------------------------------------------- Home, ready before it opens

    /** Home younger than this shows as it is; older shows at once too, a fresh one loading behind it. */
    private static final long HOME_FRESH_MS = 10 * 60_000L;
    @Nullable
    private static List<MeeTuneAdapter.Row> sHome;
    private static long sHomeAt;
    private static String sHomeAccount = "";
    private static boolean sHomeLoading;
    private static final List<Runnable> sHomeWaiting = new ArrayList<>();

    /** The account Home is for: the web sign-in's slot, or "" signed out. */
    static String account(Context context) {
        return com.newtube.mobile.ui.signin.WebSignInState.isSignedIn(context)
                ? String.valueOf(com.newtube.mobile.ui.signin.WebSignInState.selectedSlot(context)) : "";
    }

    /**
     * Starts loading Home unless it's fresh or already on its way: at the tap that opens MeeTune, so it
     * loads while MeeTube blurs away. Main thread.
     */
    public static void prefetch(Context context) {
        Context app = context.getApplicationContext();
        String account = account(app);
        boolean fresh = sHome != null && account.equals(sHomeAccount)
                && System.currentTimeMillis() - sHomeAt < HOME_FRESH_MS;
        if (sHomeLoading || fresh) return;
        sHomeLoading = true;
        WORKER.execute(() -> {
            List<MeeTuneAdapter.Row> rows = null;
            try {
                rows = MeeTuneAdapter.rows(home(app), false, true);
            } catch (Exception ignored) {
                // the page shows its retry
            }
            List<MeeTuneAdapter.Row> loaded = rows;
            MAIN.post(() -> {
                sHomeLoading = false;
                if (loaded != null) {
                    sHome = loaded;
                    sHomeAt = System.currentTimeMillis();
                    sHomeAccount = account;
                }
                List<Runnable> waiting = new ArrayList<>(sHomeWaiting);
                sHomeWaiting.clear();
                for (Runnable then : waiting) then.run();
            });
        });
    }

    /**
     * Home's rows for {@code then}: at once when MeeTube already has them for this account (an old
     * copy refreshes behind it, for next time), else when the load in flight lands (null: it failed).
     * {@code force}: a retry or an account switch, never the copy.
     */
    static void home(Activity owner, boolean force, java.util.function.Consumer<List<MeeTuneAdapter.Row>> then) {
        String account = account(owner);
        if (force) {
            sHome = null;
        } else if (sHome != null && account.equals(sHomeAccount)) {
            then.accept(sHome);
            prefetch(owner); // nothing while it's fresh
            return;
        }
        prefetch(owner);
        sHomeWaiting.add(() -> {
            if (owner.isFinishing() || owner.isDestroyed()) return;
            if (sHome != null && !account.equals(sHomeAccount)) {
                home(owner, true, then); // a load for the account before the switch landed
                return;
            }
            then.accept(sHome);
        });
    }

    /** YouTube Music's Home, then a "Find Your Mood" row from its moods page, both asked at once. */
    private static List<MusicApi.Shelf> home(Context context) throws Exception {
        java.util.concurrent.Future<List<MusicApi.Shelf>> moods =
                WORKER.submit(() -> MusicApi.browse(context, "FEmusic_moods_and_genres", null));
        List<MusicApi.Shelf> shelves = MusicApi.browse(context, "FEmusic_home", null);
        try {
            for (MusicApi.Shelf shelf : moods.get(8, java.util.concurrent.TimeUnit.SECONDS)) {
                if (shelf.allMoods()) {
                    shelf.title = context.getString(R.string.meetune_mood);
                    shelf.moreBrowseId = "FEmusic_moods_and_genres";
                    shelf.moreParams = null;
                    shelves.add(shelf);
                    break;
                }
            }
        } catch (Exception ignored) {
            // Home stands without its moods row.
        }
        // YouTube Music answers much the same Home each time: each row's picks come in a new order,
        // so every visit and every refresh looks new.
        for (MusicApi.Shelf shelf : shelves) {
            if (shelf.items != null && shelf.items.size() > 2) java.util.Collections.shuffle(shelf.items);
        }
        return shelves;
    }

    static <T> void run(Activity owner, Task<T> task, Result<T> result) {
        WORKER.execute(() -> {
            T value = null;
            Exception error = null;
            try {
                value = task.run();
            } catch (Exception e) {
                error = e;
            }
            T finalValue = value;
            Exception finalError = error;
            MAIN.post(() -> {
                if (!owner.isFinishing() && !owner.isDestroyed()) result.done(finalValue, finalError);
            });
        });
    }

    static void play(Context context, MusicApi.Item item) {
        if (item.videoId == null) return;
        ITEMS.put(item.videoId, item);
        if (item.art != null) ART.put(item.videoId, item.art);
        Video video = video(item);
        offline(context, video);
        sPlaylistId = video.playlistId;
        sVideoId = item.videoId;
        PlaybackPresenter.instance(context).openVideo(video);
    }

    /** The song's menu's Play next ({@code next}) or Add to queue: MeeTube's playback queue, in the music look. */
    static void queue(Context context, MusicApi.Item item, boolean next) {
        if (item.videoId == null) return;
        ITEMS.put(item.videoId, item);
        if (item.art != null) ART.put(item.videoId, item.art);
        Video video = video(item);
        offline(context, video);
        video.fromQueue = true;
        QUEUED.add(item.videoId);
        com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist playlist =
                com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist.instance();
        if (next) playlist.next(video); else playlist.add(video);
    }

    /** A song downloaded on this phone plays from its file: offline too, and with no data used. */
    private static void offline(Context context, Video video) {
        video.localUri = com.newtube.mobile.downloads.DownloadsBridge.instance(context).localSongUri(video.videoId);
        // Else a song whole in the cache (Library's Cached songs) plays from it: no network needed.
        if (video.localUri == null) video.localUri = MeeTuneSongCache.cachedUri(context, video.videoId);
    }

    /** What MeeTune knows of a song (title, artist, cover), or null. */
    @Nullable
    static MusicApi.Item known(String videoId) {
        return ITEMS.get(videoId);
    }

    /** Songs already met in a YouTube Music queue fetched for the player (their art and links are known). */
    private static final java.util.Set<String> RADIO = new java.util.HashSet<>();
    /** The last queue fetched, in order. */
    private static final List<String> sBatch = new java.util.ArrayList<>();
    /** Songs played lately (newest last): never picked as next again, so a radio can't bounce A, B, A, B. */
    private static final java.util.LinkedHashSet<String> PLAYED = new java.util.LinkedHashSet<>();

    private static void played(String id) {
        synchronized (PLAYED) {
            PLAYED.remove(id);
            PLAYED.add(id);
            if (PLAYED.size() > 200) PLAYED.remove(PLAYED.iterator().next());
        }
    }

    private static boolean wasPlayed(String id) {
        synchronized (PLAYED) {
            return PLAYED.contains(id);
        }
    }

    /**
     * The YouTube Music radio's songs after {@code current} (its Up next, and what plays next), the
     * video twin of a song left out; empty for a MeeTube video or before the radio is in.
     */
    public static List<Video> upNext(@Nullable Video current) {
        List<Video> out = new java.util.ArrayList<>();
        if (!isMusic(current)) return out;
        int at = sBatch.indexOf(current.videoId);
        if (at < 0) return out;
        MusicApi.Item playing = ITEMS.get(current.videoId);
        String last = playing != null ? playing.title : current.title;
        for (int i = at + 1; i < sBatch.size(); i++) {
            MusicApi.Item item = ITEMS.get(sBatch.get(i));
            if (item == null || item.videoId == null || (last != null && last.equals(item.title))
                    || wasPlayed(item.videoId)) continue;
            last = item.title;
            out.add(video(item));
        }
        return out;
    }

    /** Not in the last queue, or among its last three: time to ask for the songs after it. */
    private static boolean lastOfBatch(String id) {
        int at = sBatch.indexOf(id);
        return at < 0 || at >= sBatch.size() - 3;
    }

    /**
     * The playing song's album and artist (the player's menu's Open album and Open artist), and the
     * queue YouTube Music builds around it: each song's square art, title and links, so a radio's
     * next songs show their album covers, not their videos' stills, as soon as they play. Asked once
     * per batch: a song from the last queue fetched asks nothing more until the queue runs past it.
     */
    public static void learn(Activity activity, Video video) {
        if (video == null || video.videoId == null) return;
        String id = video.videoId;
        played(id);
        MusicApi.Item known = ITEMS.get(id);
        boolean knowsSong = known != null && known.albumId != null && known.artistId != null;
        // A new queue only when this one runs out: another fetched mid-queue (for a song's album) is
        // that song's own radio, which leads back to the song before it.
        boolean refill = lastOfBatch(id) || upNext(video).isEmpty(); // or all that is left has played
        if (knowsSong && RADIO.contains(id) && !refill) return;
        String playlistId = video.playlistId;
        run(activity, () -> MusicApi.radio(activity, id, playlistId), (queue, error) -> {
            if (queue == null || queue.isEmpty()) return;
            MusicApi.Item found = null;
            if (refill) sBatch.clear();
            for (MusicApi.Item song : queue) {
                RADIO.add(song.videoId);
                if (refill) sBatch.add(song.videoId);
                if (song.videoId.equals(id)) {
                    if (found == null) found = song;
                    continue;
                }
                if (!ITEMS.containsKey(song.videoId)) ITEMS.put(song.videoId, song);
                if (song.art != null && !ART.containsKey(song.videoId)) ART.put(song.videoId, song.art);
            }
            // The next songs' covers onto the phone now: a skip, however quick, finds its cover there.
            int ahead = 0;
            for (Video next : upNext(video)) {
                if (ahead++ == 6) break;
                com.bumptech.glide.Glide.with(activity.getApplicationContext()).downloadOnly().load(art(next)).preload();
            }
            if (found == null) return;
            MusicApi.Item item = ITEMS.get(id);
            if (item == null) {
                item = item(video);
                ITEMS.put(id, item);
            }
            if (item.albumId == null) {
                item.albumId = found.albumId;
                item.album = found.album;
            }
            if (item.artistId == null) item.artistId = found.artistId;
            if (item.artist == null) item.artist = found.artist;
        });
    }

    /** Convert to video: the song's video in MeeTube's player, picture on; it plays as a MeeTube video now. */
    static void watch(Context context, MusicApi.Item item) {
        if (item.videoId == null) return;
        QUEUED.remove(item.videoId);
        RADIO_NEXT.remove(item.videoId);
        if (item.videoId.equals(sVideoId)) sVideoId = null;
        Video video = Video.from(item.videoId);
        video.title = item.title;
        video.author = artist(item.subtitle);
        PlaybackPresenter.instance(context).openVideo(video);
        MeeTuneActivity.closeUnderPlayer(); // Back from the video goes to MeeTube, not MeeTune
    }

    /** Remove from cache: the song's streams the player kept on disk; how many it found (0: none cached). */
    static int uncache(Context context, String videoId) {
        com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo info =
                com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.instance().getFormatInfoObserve(videoId).blockingFirst();
        List<String> urls = new java.util.ArrayList<>();
        if (info != null && info.getAdaptiveFormats() != null) {
            for (com.liskovsoft.mediaserviceinterfaces.data.MediaFormat format : info.getAdaptiveFormats()) urls.add(format.getUrl());
        }
        return com.newtube.mobile.player.Media3PlayerCache.remove(context, urls);
    }

    /** A song as MeeTube's video: what plays it, and what its Add to playlist saves. */
    static Video video(MusicApi.Item item) {
        Video video = Video.from(item.videoId);
        video.title = item.title;
        video.author = artist(item.subtitle);
        video.cardImageUrl = item.art;
        video.playlistId = item.playlistId != null ? item.playlistId : "RDAMVM" + item.videoId;
        return video;
    }

    /** "Artist • Album • 3:21" -> "Artist". */
    static String artist(@Nullable String subtitle) {
        if (subtitle == null) return null;
        int dot = subtitle.indexOf(" • ");
        return dot > 0 ? subtitle.substring(0, dot) : subtitle;
    }

    /** The Library's Downloads card and page: not a YouTube Music page, read off this phone's downloads. */
    static final String DOWNLOADS = "meetune:downloads";
    /** The Library's Cached songs card and page: the songs whole in the player's cache (MeeTuneSongCache). */
    static final String CACHED = "meetune:cached";

    /** The song's MeeTune download (done, coming or failed), or null when it has none. */
    @androidx.annotation.Nullable
    static com.newtube.mobile.downloads.DownloadItem songDownload(Context context, String videoId) {
        for (com.newtube.mobile.downloads.DownloadItem download
                : com.newtube.mobile.downloads.DownloadRegistry.instance(context).items()) {
            if (download.song && download.videoId != null && download.videoId.equals(videoId)) return download;
        }
        return null;
    }

    /** The songs downloaded from MeeTune, newest first, one shelf; a song still coming says how far it is. */
    static List<MusicApi.Shelf> downloads(Context context) {
        MusicApi.Shelf shelf = new MusicApi.Shelf();
        for (com.newtube.mobile.downloads.DownloadItem download
                : com.newtube.mobile.downloads.DownloadRegistry.instance(context).itemsNewestFirst()) {
            if (!download.song) continue;
            MusicApi.Item item = new MusicApi.Item();
            item.kind = MusicApi.SONG;
            item.videoId = download.videoId;
            item.title = download.title;
            item.artist = download.author;
            String state = download.isFailed() ? context.getString(R.string.meetune_download_failed)
                    : download.isDone() ? null
                    : context.getString(R.string.meetune_downloading, download.progressPercent());
            item.subtitle = state == null ? download.author
                    : download.author != null ? download.author + " • " + state : state;
            item.art = download.thumbPath != null && new java.io.File(download.thumbPath).exists()
                    ? download.thumbPath : download.thumbUrl;
            if (download.durationMs > 0) {
                long s = download.durationMs / 1000;
                item.duration = s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
            }
            shelf.items.add(item);
        }
        List<MusicApi.Shelf> shelves = new java.util.ArrayList<>();
        if (!shelf.items.isEmpty()) shelves.add(shelf);
        return shelves;
    }

    /** The Library's History card and page: the songs played in MeeTune, on this phone only. */
    static final String HISTORY = "meetune:history";
    private static final int HISTORY_MAX = 200;

    /** The music player opened {@code video}: it goes to the top of MeeTune's History. */
    /** MeeTune's screen, told when a song lands in History (its History page reloads). */
    @Nullable
    static Runnable sHistoryChanged;
    @Nullable
    private static String sLastRemembered;

    public static void remember(Context context, Video video) {
        if (video == null || video.videoId == null || video.videoId.equals(sLastRemembered)) return;
        sLastRemembered = video.videoId;
        MusicApi.Item item = item(video);
        try {
            org.json.JSONArray old = new org.json.JSONArray(prefs(context).getString("history", "[]"));
            org.json.JSONArray now = new org.json.JSONArray();
            now.put(new org.json.JSONObject().put("v", video.videoId).put("t", item.title)
                    .put("a", item.artist != null ? item.artist : artist(item.subtitle)).put("art", art(video)));
            for (int i = 0; i < old.length() && now.length() < HISTORY_MAX; i++) {
                org.json.JSONObject o = old.optJSONObject(i);
                if (o != null && !video.videoId.equals(o.optString("v"))) now.put(o);
            }
            prefs(context).edit().putString("history", now.toString()).apply();
            Runnable changed = sHistoryChanged;
            if (changed != null) MAIN.post(changed);
        } catch (org.json.JSONException ignored) {
        }
    }

    static List<MusicApi.Shelf> history(Context context) {
        MusicApi.Shelf shelf = new MusicApi.Shelf();
        try {
            org.json.JSONArray saved = new org.json.JSONArray(prefs(context).getString("history", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                org.json.JSONObject o = saved.getJSONObject(i);
                MusicApi.Item item = new MusicApi.Item();
                item.kind = MusicApi.SONG;
                item.videoId = o.optString("v");
                item.title = o.optString("t", null);
                item.artist = o.optString("a", null);
                item.subtitle = item.artist;
                item.art = o.optString("art", null);
                shelf.items.add(item);
            }
        } catch (org.json.JSONException ignored) {
        }
        List<MusicApi.Shelf> shelves = new java.util.ArrayList<>();
        if (!shelf.items.isEmpty()) shelves.add(shelf);
        return shelves;
    }

    /** The playing song as MeeTune knows it; a radio's next song only by its video's title, artist and cover. */
    static MusicApi.Item item(Video video) {
        MusicApi.Item known = ITEMS.get(video.videoId);
        if (known != null) return known;
        MusicApi.Item item = new MusicApi.Item();
        item.videoId = video.videoId;
        item.title = video.getTitle();
        item.subtitle = video.getAuthor();
        item.art = art(video);
        item.playlistId = video.playlistId;
        return item;
    }

    /** Not a browse id: the music player's Equalizer row, MeeTune's Equalizer page on return. */
    static final String EQUALIZER = "meetune:equalizer";

    /** Songs liked from the music player this run (YouTube Music's like, not MeeTube's video like). */
    private static final java.util.Set<String> LIKED = new java.util.HashSet<>();

    /** The song just opened: whether this account already likes it on YouTube Music. */
    public static void checkLike(Activity a, @Nullable String videoId, Runnable changed) {
        if (videoId == null || !MusicApi.signedIn(a)) return;
        run(a, () -> MusicApi.liked(a, videoId), (liked, error) -> {
            if (liked == null) return;
            if (liked) LIKED.add(videoId); else LIKED.remove(videoId);
            changed.run();
        });
    }

    public static boolean liked(@Nullable String videoId) {
        return videoId != null && LIKED.contains(videoId);
    }

    /** The music player's heart: likes the song on YouTube Music, or takes the like back. */
    public static void toggleLike(Activity a, @Nullable String videoId, Runnable changed) {
        if (videoId == null) return;
        if (!MusicApi.signedIn(a)) {
            com.newtube.mobile.ui.common.MobileSnackbar.show(a, R.string.meetune_rate_sign_in);
            return;
        }
        boolean like = !LIKED.contains(videoId);
        if (like) LIKED.add(videoId); else LIKED.remove(videoId);
        changed.run();
        run(a, () -> MusicApi.rate(a, videoId, like ? "like" : "removelike"), (ok, error) -> {
            if (ok == null) { // back as it was
                if (like) LIKED.remove(videoId); else LIKED.add(videoId);
                changed.run();
            }
            com.newtube.mobile.ui.common.MobileSnackbar.show(a, ok == null ? R.string.meetune_rate_failed
                    : like ? R.string.meetune_liked_done : R.string.meetune_unliked);
        });
    }

    static void openOnReturn(String browseId) {
        sOpenOnReturn = browseId;
    }

    @Nullable
    static String takeOpenOnReturn() {
        String browseId = sOpenOnReturn;
        sOpenOnReturn = null;
        return browseId;
    }

    /** A song picked from the music player's Up next stays in the music look. */
    public static void keep(Video video) {
        if (video != null) sVideoId = video.videoId;
    }

    /** True while {@code video} is a song MeeTune started (or its radio's next one). */
    public static boolean isMusic(@Nullable Video video) {
        if (video == null) return false;
        return QUEUED.contains(video.videoId) || RADIO_NEXT.contains(video.videoId)
                || (sVideoId != null && sVideoId.equals(video.videoId))
                || (sPlaylistId != null && sPlaylistId.equals(video.playlistId));
    }

    /** The player's cover: MeeTune's square art when it has it, else the video's own still. */
    public static String art(Video video) {
        String art = ART.get(video.videoId);
        return art != null ? art : "https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg";
    }

    @Nullable
    private static java.lang.ref.WeakReference<MeeTuneMiniPill> sPill;
    private static final android.graphics.Rect sPillArt = new android.graphics.Rect();
    private static final android.graphics.Rect sPillText = new android.graphics.Rect();

    @Nullable
    private static MeeTuneMiniPill pill() {
        MeeTuneMiniPill pill = sPill != null ? sPill.get() : null;
        // only when the music player minimizes into MeeTune (not into a MeeTube screen over it)
        return pill != null && com.newtube.mobile.ui.playback.MiniPlayerBridge.getMiniHost() instanceof MeeTuneActivity
                ? pill : null;
    }

    /** Where MeeTune's pill shows its cover (screen pixels) when the player minimizes into it, else null. */
    @Nullable
    public static android.graphics.Rect pillArt() {
        return pill() != null && !sPillArt.isEmpty() ? sPillArt : null;
    }

    /** Where the pill's title and artist sit (screen pixels); valid with {@link #pillArt()}. */
    public static android.graphics.Rect pillText() {
        return sPillText;
    }

    static void setPill(@Nullable MeeTuneMiniPill pill, @Nullable android.graphics.Rect art, @Nullable android.graphics.Rect text) {
        sPill = pill != null ? new java.lang.ref.WeakReference<>(pill) : null;
        if (art != null) sPillArt.set(art); else sPillArt.setEmpty();
        if (text != null) sPillText.set(text); else sPillText.setEmpty();
    }

    /**
     * The music player minimizing at {@code f} (0: back up or handed off, 1: landed). MeeTune's pill
     * comes up after the finger; {@code carried}: the player flies its own cover and name onto it,
     * so the pill's own stay hidden until it lands.
     */
    /** How far the pill's own cover and name have taken over from the player's at flight fraction {@code f}. */
    public static float handoff(float f) {
        return Math.max(0f, Math.min(1f, (f - 0.8f) / 0.2f));
    }

    public static void flyToPill(float f, @Nullable Video video, boolean playing, boolean carried) {
        MeeTuneMiniPill pill = sPill != null ? sPill.get() : null;
        if (pill != null && (f <= 0f || pill() != null)) pill.fly(f, video, playing, carried);
    }

    // ----------------------------------------------------------------------------- settings

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("meetune", Context.MODE_PRIVATE);
    }

    /** Songs play as sound only (the video track off), as YouTube Music does. Always, since its switch went. */
    public static boolean audioOnly(Context context) {
        return true;
    }

    /** Songs open in MeeTune's music player. Always, since its switch went. */
    public static boolean musicPlayer(Context context) {
        return true;
    }

    /**
     * Home's "tap the icon to switch to MeeTune" hint, and MeeTune Home's twin back to MeeTube: each
     * shows until its X closes it or the user first switches that way ({@link #open} / {@link #leave}).
     */
    public static boolean homeHint(Context context) {
        return !prefs(context).getBoolean("home_hint_closed", false);
    }

    public static void closeHomeHint(Context context) {
        prefs(context).edit().putBoolean("home_hint_closed", true).apply();
    }

    static boolean meeTubeHint(Context context) {
        return !prefs(context).getBoolean("meetube_hint_closed", false);
    }

    static void closeMeeTubeHint(Context context) {
        prefs(context).edit().putBoolean("meetube_hint_closed", true).apply();
    }

    /**
     * Pops a switch hint in under its brand pill and keeps it riding there (the pill moves with its
     * bar). A tap on the hint switches ({@code onTap}); its X closes it for good ({@code onClose}).
     */
    public static void showSwitchHint(Activity activity, android.view.View hint, android.view.View brand,
                                      android.view.View blurSource, int closeId, Runnable onTap, Runnable onClose) {
        if (hint.getVisibility() == android.view.View.VISIBLE) return;
        hint.setOnClickListener(v -> {
            hint.setVisibility(android.view.View.GONE);
            onTap.run();
        });
        hint.findViewById(closeId).setOnClickListener(v -> {
            com.newtube.mobile.ui.common.Haptics.click(v);
            onClose.run();
            hint.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150)
                    .setInterpolator(com.newtube.mobile.ui.common.Motion.STANDARD)
                    .withEndAction(() -> hint.setVisibility(android.view.View.GONE)).start();
        });
        com.newtube.mobile.ui.common.Glass.blur(hint, blurSource);
        float gap = 8 * activity.getResources().getDisplayMetrics().density;
        int[] at = new int[2];
        int[] home = new int[2];
        hint.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (hint.getVisibility() != android.view.View.VISIBLE) {
                    hint.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
                brand.getLocationInWindow(at);
                ((android.view.View) hint.getParent()).getLocationInWindow(home);
                float x = at[0] - home[0] - hint.getLeft();
                float y = at[1] - home[1] + brand.getHeight() * brand.getScaleY() + gap - hint.getTop();
                if (hint.getTranslationX() != x || hint.getTranslationY() != y) {
                    hint.setTranslationX(x);
                    hint.setTranslationY(y);
                }
                return true;
            }
        });
        hint.setPivotX(0);
        hint.setPivotY(0);
        hint.setAlpha(0f);
        hint.setScaleX(0.8f);
        hint.setScaleY(0.8f);
        hint.setVisibility(android.view.View.VISIBLE);
        hint.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(400).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f)).start();
    }

    /**
     * The glowing "MeeTune →" / "MeeTube →" link at the foot of either side's settings: centred bold
     * text with a soft halo.
     */
    public static android.widget.TextView switchLink(Context context, CharSequence text,
                                                     View.OnClickListener onClick) {
        android.widget.TextView link = new android.widget.TextView(context);
        float dp = context.getResources().getDisplayMetrics().density;
        // White on the dark theme (black on the light one, where white would vanish), glowing its own colour.
        int color = androidx.core.content.ContextCompat.getColor(context, R.color.mobile_color_on_surface);
        link.setText(text);
        link.setTextSize(14);
        link.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        link.setLetterSpacing(0.04f);
        link.setTextColor(color);
        link.setShadowLayer(8 * dp, 0, 0, (color & 0x00FFFFFF) | 0xCC000000);
        link.setGravity(android.view.Gravity.CENTER);
        int pad = Math.round(14 * dp);
        link.setPadding(pad, pad, pad, pad);
        link.setOnClickListener(v -> {
            com.newtube.mobile.ui.common.Haptics.click(v);
            Motion.nudge(v);
            onClick.onClick(v);
        });
        return link;
    }

    /** "MeeTune" beside the heart on Home's brand pill; off, the heart alone. */
    static boolean brandName(Context context) {
        return prefs(context).getBoolean("brand_name", true);
    }

    static void setBrandName(Context context, boolean on) {
        prefs(context).edit().putBoolean("brand_name", on).apply();
    }

    /** Streaming quality, YouTube Music's three: High (the best sound), Normal, Low. */
    static final int STREAM_HIGH = 0;
    static final int STREAM_NORMAL = 1;
    static final int STREAM_LOW = 2;

    static int streamQuality(Context context) {
        return prefs(context).getInt("stream_quality", STREAM_HIGH);
    }

    static void setStreamQuality(Context context, int quality) {
        prefs(context).edit().putInt("stream_quality", quality).apply();
    }

    /** Crossfade between songs, 0 (off: gapless, each song to its end) to 12 seconds. */
    public static int crossfadeSeconds(Context context) {
        return Math.max(0, Math.min(12, prefs(context).getInt("crossfade_s", 0)));
    }

    static void setCrossfadeSeconds(Context context, int seconds) {
        prefs(context).edit().putInt("crossfade_s", Math.max(0, Math.min(12, seconds))).apply();
    }

    /** Home's "Listen again" as Speed dial, pages of 3x3 square tiles as YT Music has it (issue #264). Off: Home as it was. */
    static boolean speedDial(Context context) {
        return prefs(context).getBoolean("speed_dial", false);
    }

    static void setSpeedDial(Context context, boolean on) {
        prefs(context).edit().putBoolean("speed_dial", on).apply();
    }

    /** Streaming quality on mobile data; until picked, the same as on Wi-Fi (what one setting did). */
    static int mobileStreamQuality(Context context) {
        return prefs(context).getInt("stream_quality_mobile", streamQuality(context));
    }

    static void setMobileStreamQuality(Context context, int quality) {
        prefs(context).edit().putInt("stream_quality_mobile", quality).apply();
    }

    /** The phone is on mobile data (or any network it counts by the byte, such as a hotspot). */
    static boolean onMeteredNetwork(Context context) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.isActiveNetworkMetered();
    }

    /**
     * The most a song's sound may take, in bits a second, for the network it streams on now (read at
     * each song's start): Normal stops at AAC's 128k, Low at Opus' 70k.
     */
    public static int maxAudioBitrate(Context context) {
        int quality = onMeteredNetwork(context) ? mobileStreamQuality(context) : streamQuality(context);
        switch (quality) {
            case STREAM_NORMAL: return 135_000;
            case STREAM_LOW: return 72_000;
            default: return Integer.MAX_VALUE;
        }
    }

    /** What a song's Download takes, always sound only: High (AAC 128 kbps) or Low (AAC 48 kbps). */
    static int downloadQuality(Context context) {
        return prefs(context).getInt("download_quality", 0) == com.newtube.mobile.downloads.DownloadPicker.AUDIO_LOW
                ? com.newtube.mobile.downloads.DownloadPicker.AUDIO_LOW : com.newtube.mobile.downloads.AutoDownload.AUDIO;
    }

    static void setDownloadQuality(Context context, int quality) {
        prefs(context).edit().putInt("download_quality", quality).apply();
    }

    /** Whether a song's Download also saves its lyrics, so they show with no internet. */
    static boolean downloadLyrics(Context context) {
        return prefs(context).getBoolean("download_lyrics", true);
    }

    static void setDownloadLyrics(Context context, boolean on) {
        prefs(context).edit().putBoolean("download_lyrics", on).apply();
    }
}
