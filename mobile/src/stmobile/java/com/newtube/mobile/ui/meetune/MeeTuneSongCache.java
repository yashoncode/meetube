package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.newtube.mobile.downloads.DownloadsBridge;
import com.newtube.mobile.player.Media3PlayerCache;
import com.newtube.mobile.player.Media3SourceFactory;
import com.newtube.mobile.player.MediaHttpClient;
import com.newtube.mobile.player.SabrSourcePreference;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The next song, whole, into the player's own disk cache a few seconds into the current one (how
 * BitChord reads ahead): a skip at any moment then plays from the phone at once. The cache keys a
 * stream by video id and itag, so the copy is found whatever signed link the player opens it with,
 * and it stays until the 512MB cache needs the room (the player's sample preload expires in 90s).
 *
 * <p>One song ahead, in the audio format the current song plays in (the player picks the same one
 * again). Ranged 2MB reads, because googlevideo paces one long read to playback speed. Through the
 * player's media client (the API client's interceptors get a 403), and a refused link ends the
 * fetch: it is never retried, the player resolves its own when the song opens.
 */
@UnstableApi
public final class MeeTuneSongCache {
    private static final String TAG = "MeeTuneSongCache";
    private static final long CHUNK_BYTES = 2L * 1024 * 1024;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** The song being fetched; a newer target stops the older fetch at its next chunk. */
    private static volatile String sTarget;

    private MeeTuneSongCache() {
    }

    /** Fetch {@code videoId} in {@code formatId}'s format ("251", "251-drc"; null: the best Opus). */
    public static void fetch(Context context, String videoId, @Nullable String formatId) {
        if (videoId == null || videoId.equals(sTarget)) return;
        Context app = context.getApplicationContext();
        if (SabrSourcePreference.isPreferred(app)) return; // SABR reads no DASH links: nothing to match
        if (DownloadsBridge.instance(app).localSongUri(videoId) != null) return; // plays from its file
        sTarget = videoId;
        WORKER.execute(() -> {
            try {
                cache(app, videoId, formatId);
            } catch (Exception e) {
                Log.d(TAG, "next song " + videoId + " not cached: " + e);
            }
        });
    }

    private static void cache(Context context, String videoId, @Nullable String formatId) throws Exception {
        Cache cache = Media3PlayerCache.get(context);
        if (cache == null || !videoId.equals(sTarget)) return;
        MediaItemFormatInfo info = YouTubeMediaItemService.instance().getFormatInfoObserve(videoId).blockingFirst();
        MediaFormat audio = pick(info, formatId);
        if (audio == null) return;
        long length = audio.getClen() != null ? Long.parseLong(audio.getClen()) : 0;
        if (length <= 0) return;
        record(context, cache, videoId, audio.getUrl(), length);

        CacheDataSource source = new CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(Media3PlayerCache.getCacheKeyFactory())
                .setUpstreamDataSourceFactory(new OkHttpDataSource.Factory(
                        MediaHttpClient.create(OkHttpManager.instance().getClient()))
                        .setUserAgent(Media3SourceFactory.MEDIA_USER_AGENT))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSourceForDownloading();
        Uri uri = Uri.parse(audio.getUrl());
        for (long at = 0; at < length; at += CHUNK_BYTES) {
            if (!videoId.equals(sTarget)) return; // the queue moved on
            DataSpec chunk = new DataSpec.Builder().setUri(uri).setPosition(at)
                    .setLength(Math.min(CHUNK_BYTES, length - at)).build();
            new CacheWriter(source, chunk, null, null).cache(); // cached bytes are skipped, not fetched again
        }
        Log.d(TAG, "next song " + videoId + " cached, itag " + audio.getITag() + ", " + length / 1024 + " KB");
    }

    // ------------------------------------------------------------------------------ Cached songs

    /**
     * Library's Cached songs: each song MeeTune put into the player's cache, with its stream's link and
     * length, so a song whole in the cache plays from it with no network (the cache finds it by the
     * link's id and itag, however old the link). Listed while its bytes are all there; the cache's
     * own eviction or Clear cache drops it.
     */
    private static final String PREFS = "meetune_cached";
    private static volatile String sRemembered;

    /** The playing song: noted as cached (the player fills the cache as it plays). */
    public static void remember(Context context, String videoId, @Nullable String formatId) {
        if (videoId == null || videoId.equals(sRemembered)) return;
        Context app = context.getApplicationContext();
        if (SabrSourcePreference.isPreferred(app)) return;
        if (DownloadsBridge.instance(app).localSongUri(videoId) != null) return; // Downloads has it
        sRemembered = videoId;
        WORKER.execute(() -> {
            try {
                Cache cache = Media3PlayerCache.get(app);
                if (cache == null) return;
                MediaFormat audio = pick(YouTubeMediaItemService.instance().getFormatInfoObserve(videoId).blockingFirst(), formatId);
                long length = audio != null && audio.getClen() != null ? Long.parseLong(audio.getClen()) : 0;
                if (length > 0) record(app, cache, videoId, audio.getUrl(), length);
            } catch (Exception e) {
                Log.d(TAG, "song " + videoId + " not noted: " + e);
            }
        });
    }

    private static synchronized void record(Context context, Cache cache, String videoId, String url, long length) {
        try {
            // The length, so playing from the cache stops at the song's end instead of asking the network for more.
            androidx.media3.datasource.cache.ContentMetadataMutations mutations =
                    new androidx.media3.datasource.cache.ContentMetadataMutations();
            androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(mutations, length);
            cache.applyContentMetadataMutations(key(url), mutations);
            MusicApi.Item item = MeeTune.known(videoId);
            org.json.JSONObject song = new org.json.JSONObject()
                    .put("u", url).put("l", length)
                    .put("t", item != null ? item.title : null)
                    .put("a", item != null ? (item.artist != null ? item.artist : MeeTune.artist(item.subtitle)) : null)
                    .put("i", item != null ? item.art : null)
                    .put("d", item != null ? item.duration : null);
            android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONObject old = song.optString("t").isEmpty() ? read(prefs).optJSONObject(videoId) : null;
            if (old != null) { // known from before: its names and cover kept
                song.put("t", old.opt("t")).put("a", old.opt("a")).put("i", old.opt("i")).put("d", old.opt("d"));
            }
            prefs.edit().putString(videoId, song.toString())
                    .putLong(videoId + "@", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            Log.d(TAG, "song " + videoId + " not noted: " + e);
        }
    }

    private static org.json.JSONObject read(android.content.SharedPreferences prefs) {
        org.json.JSONObject all = new org.json.JSONObject();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().endsWith("@") || !(entry.getValue() instanceof String)) continue;
            try {
                all.put(entry.getKey(), new org.json.JSONObject((String) entry.getValue()));
            } catch (org.json.JSONException ignored) {
                // a broken entry is skipped
            }
        }
        return all;
    }

    private static String key(String url) {
        return Media3PlayerCache.getCacheKeyFactory().buildCacheKey(new DataSpec(Uri.parse(url)));
    }

    /** The link a song whole in the cache plays from, or null (not noted, or some of it gone). */
    @Nullable
    static String cachedUri(Context context, String videoId) {
        if (videoId == null) return null;
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(videoId, null);
        Cache cache = json != null ? Media3PlayerCache.get(context) : null;
        if (cache == null) return null;
        try {
            org.json.JSONObject song = new org.json.JSONObject(json);
            String url = song.getString("u");
            return cache.isCached(key(url), 0, song.getLong("l")) ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The songs whole in the cache, newest first; the ones the cache let go are forgotten. Off the main thread. */
    static List<MusicApi.Shelf> songs(Context context) {
        MusicApi.Shelf shelf = new MusicApi.Shelf();
        Cache cache = Media3PlayerCache.get(context);
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        org.json.JSONObject all = read(prefs);
        List<String> ids = new java.util.ArrayList<>();
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) ids.add(it.next());
        ids.sort((x, y) -> Long.compare(prefs.getLong(y + "@", 0), prefs.getLong(x + "@", 0)));
        java.util.Set<String> keys = cache != null ? cache.getKeys() : java.util.Collections.emptySet();
        android.content.SharedPreferences.Editor forget = prefs.edit();
        for (String id : ids) {
            org.json.JSONObject song = all.optJSONObject(id);
            String url = song != null ? song.optString("u", null) : null;
            if (cache == null || url == null) continue;
            String key = key(url);
            if (!keys.contains(key)) { // cleared or evicted
                forget.remove(id).remove(id + "@");
                continue;
            }
            if (!cache.isCached(key, 0, song.optLong("l"))) continue; // part of it only, for now
            MusicApi.Item item = new MusicApi.Item();
            item.kind = MusicApi.SONG;
            item.videoId = id;
            item.title = song.isNull("t") ? id : song.optString("t");
            item.artist = song.isNull("a") ? null : song.optString("a");
            item.subtitle = item.artist;
            item.art = song.isNull("i") ? null : song.optString("i");
            item.duration = song.isNull("d") ? null : song.optString("d");
            shelf.items.add(item);
        }
        forget.apply();
        List<MusicApi.Shelf> shelves = new java.util.ArrayList<>();
        if (!shelf.items.isEmpty()) shelves.add(shelf);
        return shelves;
    }

    /** The adaptive audio in the format the player plays now, else the best Opus, else none. */
    @Nullable
    private static MediaFormat pick(@Nullable MediaItemFormatInfo info, @Nullable String formatId) {
        if (info == null || info.getAdaptiveFormats() == null) return null;
        MediaFormat opus = null;
        for (MediaFormat format : info.getAdaptiveFormats()) {
            if (format.getUrl() == null || format.getMimeType() == null || !format.getMimeType().startsWith("audio/")) continue;
            String id = format.isDrc() ? format.getITag() + "-drc" : format.getITag();
            if (id.equals(formatId)) return format;
            if (opus == null && "251".equals(format.getITag()) && !format.isDrc()) opus = format;
        }
        return formatId == null ? opus : null;
    }
}
