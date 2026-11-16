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
