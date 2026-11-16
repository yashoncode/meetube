package com.newtube.mobile.player;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.File;
import java.util.List;

/**
 * Process-wide on-disk media cache for the Media3 touch player: one bounded LRU
 * {@link SimpleCache} with a database-backed index, living in its OWN directory - a
 * {@code SimpleCache} folder can only ever be opened by one cache implementation. (The legacy
 * vendored-ExoPlayer cache, {@code MobilePlayerCache}, used a separate directory and was deleted
 * with that engine.)
 *
 * <p>{@link #CACHE_KEY_FACTORY} replaces the legacy stable-key patch that lived in the vendored
 * {@code DashManifestParser2}: googlevideo media URLs are signed and expire, so the default
 * URL-as-key would orphan every cached range on the next InnerTube refresh. Keying on the stable
 * identifying params (video id + itag + last-modified + audio variant) lets cached ranges survive
 * signed-URL refreshes exactly like the legacy fix did. Range-request fragmentation needs no
 * equivalent here: media3 keys are range-free by design.</p>
 */
public final class Media3PlayerCache {

    private static final String TAG = Media3PlayerCache.class.getSimpleName();

    private static final String CACHE_DIR_NAME = "media3-media-cache";

    /**
     * Upper bound for the on-disk cache (LRU-evicted). 512MB (raised from the legacy 256MB): a
     * single 1080p VOD span set can approach 256MB, so a just-watched HD video could be LRU-evicted
     * before a replay - defeating the stable-key replay win the cache exists for.
     */
    private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024; // 512 MB

    private static SimpleCache sCache;

    /**
     * Stable cache keys for googlevideo media: {@code id} (video) + {@code itag} (format) +
     * {@code lmt} (upload revision) + {@code xtags} (audio language/DRC variant) uniquely identify
     * the immutable media bytes; everything else in the URL (host shard, {@code expire},
     * {@code sig}, client ip...) is volatile. Non-googlevideo URLs keep the default URI key.
     */
    private static final CacheKeyFactory CACHE_KEY_FACTORY = new StableCacheKeyFactory(64);

    /**
     * A VOD audio/video URI is reused across every byte range. Remember its derived key instead
     * of repeatedly scanning the long signed query for each chunk/cache lookup. The bound also
     * covers live segment URLs and source switches; no media bytes or expiry decisions are cached
     * here. LruCache's get/put operations are thread-safe for concurrent audio/video loaders.
     */
    static final class StableCacheKeyFactory implements CacheKeyFactory {
        private final LruCache<Uri, String> mKeys;

        StableCacheKeyFactory(int maxEntries) {
            mKeys = new LruCache<>(maxEntries);
        }

        @Override
        public String buildCacheKey(DataSpec dataSpec) {
            Uri uri = dataSpec.uri;
            String cached = mKeys.get(uri);
            if (cached != null) {
                return cached;
            }
            String stableKey = buildStableKey(uri);
            if (stableKey != null) {
                mKeys.put(uri, stableKey);
                return stableKey;
            }
            // Generic data sources may assign different explicit keys to the SAME URI. Do not
            // memoize this fallback by URI, or a previous DataSpec would override the new key.
            return dataSpec.key != null ? dataSpec.key : uri.toString();
        }
    }

    @Nullable
    private static String buildStableKey(Uri uri) {
        String id = uri.getQueryParameter("id");
        String itag = uri.getQueryParameter("itag");

        if (id == null || itag == null) {
            return null;
        }

        String lmt = uri.getQueryParameter("lmt");
        String xtags = uri.getQueryParameter("xtags");
        String key = "yt." + id + "." + itag + "." + (lmt != null ? lmt : "") + "." + (xtags != null ? xtags : "");

        // Segment addressing distinction: range-addressed VOD (SegmentBase) reuses ONE URL for the
        // whole stream - byte ranges tell the pieces apart, so the stable key alone is correct (and
        // must stay byte-identical: warm caches out there key on it). Live/OTF DASH instead gives
        // every segment its OWN URL via `sq` (query param `?sq=N` or path form `/sq/N/`), each
        // starting at position 0 - without an sq suffix they'd all collapse into one cache resource
        // and segment N's cached bytes would be served for N+1.
        String sq = uri.getQueryParameter("sq");
        if (sq == null) {
            List<String> pathSegments = uri.getPathSegments();
            int sqIndex = pathSegments.indexOf("sq");
            if (sqIndex >= 0 && sqIndex + 1 < pathSegments.size()
                    && TextUtils.isDigitsOnly(pathSegments.get(sqIndex + 1))) {
                sq = pathSegments.get(sqIndex + 1);
            }
        }

        return sq != null ? key + ".sq" + sq : key;
    }

    private Media3PlayerCache() {
    }

    public static CacheKeyFactory getCacheKeyFactory() {
        return CACHE_KEY_FACTORY;
    }

    /**
     * Lazily creates (once) and returns the shared media cache, or {@code null} if it could not be
     * created (the player then simply runs without a disk cache).
     */
    @Nullable
    public static synchronized Cache get(Context context) {
        if (sCache == null) {
            try {
                File cacheDir = new File(context.getApplicationContext().getCacheDir(), CACHE_DIR_NAME);
                sCache = new SimpleCache(
                        cacheDir,
                        new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                        new StandaloneDatabaseProvider(context.getApplicationContext()));
            } catch (Throwable e) {
                Log.e(TAG, "Failed to create media cache: " + e.getMessage());
            }
        }

        return sCache;
    }

    /**
     * MEETUBE(storage): empty the media cache through the open {@link SimpleCache} (its folder can't
     * just be deleted while the cache owns it). Slow: call off the main thread.
     */
    public static void clear(Context context) {
        Cache cache = get(context);
        if (cache == null) {
            return;
        }
        for (String key : cache.getKeys()) {
            cache.removeResource(key);
        }
    }

    /**
     * MEETUNE: a song's Remove from cache. Its streams' cached bytes, found by the stable keys its
     * stream URLs make (the same id, itag and lmt the player keyed them by). Slow: off the main thread.
     */
    public static int remove(Context context, List<String> urls) {
        Cache cache = get(context);
        if (cache == null) {
            return 0;
        }
        int removed = 0;
        for (String url : urls) {
            String key = url != null ? buildStableKey(Uri.parse(url)) : null;
            if (key != null && !cache.getCachedSpans(key).isEmpty()) {
                cache.removeResource(key);
                removed++;
            }
        }
        return removed;
    }

    /** YouTube's sound-only itags (AAC, Opus, the DRC and spatial ones). */
    private static final java.util.Set<String> AUDIO_ITAGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "139", "140", "141", "171", "172", "249", "250", "251", "256", "258", "325", "327", "328", "338",
            "380", "599", "600", "773", "774"));

    /**
     * MEETUNE: songs in the cache, as {bytes, songs}: streams cached as sound alone, no picture beside
     * them (MeeTune plays songs sound only; a MeeTube video caches its picture too). A MeeTube video
     * watched in Audio only counts here as well. Slow: off the main thread.
     */
    public static long[] songs(Context context) {
        Cache cache = get(context);
        if (cache == null) {
            return new long[] {0, 0};
        }
        java.util.Map<String, Long> audio = new java.util.HashMap<>();
        java.util.Set<String> pictured = new java.util.HashSet<>();
        for (String key : cache.getKeys()) {
            String[] parts = key.split("\\.");
            if (parts.length < 3 || !"yt".equals(parts[0])) {
                continue;
            }
            if (!AUDIO_ITAGS.contains(parts[2])) {
                pictured.add(parts[1]);
                continue;
            }
            long bytes = 0;
            for (androidx.media3.datasource.cache.CacheSpan span : cache.getCachedSpans(key)) {
                bytes += span.length;
            }
            Long had = audio.get(parts[1]);
            audio.put(parts[1], (had != null ? had : 0) + bytes);
        }
        long bytes = 0;
        long songs = 0;
        for (java.util.Map.Entry<String, Long> entry : audio.entrySet()) {
            if (!pictured.contains(entry.getKey()) && entry.getValue() > 0) {
                bytes += entry.getValue();
                songs++;
            }
        }
        return new long[] {bytes, songs};
    }

    /** The cache's folder, for the Storage page to size. */
    public static File dir(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), CACHE_DIR_NAME);
    }
}
