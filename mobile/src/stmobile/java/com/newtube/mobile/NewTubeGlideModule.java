package com.newtube.mobile;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/** Enables annotation-discovered Glide library modules, including the animated-WebP decoder. */
@GlideModule
public final class NewTubeGlideModule extends AppGlideModule {
    /**
     * Image fetching must never crowd out the video stream. Glide sizes its source executor from the
     * CPU count (4 on any current phone), so a feed fling or a freshly opened watch page can hold
     * four concurrent HTTP fetches against googleusercontent while /player and the first media
     * chunks are still in flight - on a narrow link the thumbnails simply win. It was two threads,
     * but MeeTune's Home (a dozen shelves of covers) filled in too slowly with two, so it is four
     * again, the CPU count.
     */
    private static final int SOURCE_THREADS = 4;

    /**
     * Glide's old built-in 2500ms connect+read budget is a LAN default. On a high-RTT mobile link the
     * handshake alone can eat it, so thumbnails fail, the next bind re-requests them, and the result
     * is a request storm whose only visible outcome is permanently grey cards. Fail slowly instead.
     */
    private static final int HTTP_TIMEOUT_MS = 15_000;

    /**
     * MEETUNE(covers): images over their own OkHttp client, not HttpURLConnection. That one is HTTP/1.1,
     * so every image host got up to four TLS connections and covers queued four at a time behind
     * Glide's threads: a cold MeeTune Home showed its first ten covers together after ~0.9 s although
     * one fetch takes ~0.2 s. OkHttp speaks HTTP/2 (one connection per host, every cover on it at
     * once) and fetches asynchronously, so the source threads only decode. Its own client: the shared
     * InnerTube one carries interceptors that images must not get.
     */
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequestsPerHost(MAX_PER_HOST);
        OkHttpClient client = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                // One HTTP/2 connection carries every cover, so a NAT-dropped idle one timed them all out
                // together and left blank cards. The ping keeps it alive and drops a dead one in 10 s.
                .pingInterval(10, TimeUnit.SECONDS)
                .build();
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(client));
    }

    /** Covers in flight per image host; on HTTP/2 they share one connection. */
    private static final int MAX_PER_HOST = 16;

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setSourceExecutor(GlideExecutor.newSourceBuilder()
                .setThreadCount(SOURCE_THREADS)
                .setName("newtube-glide-source")
                .build());

        // NEWTUBE(scroll): room for one more screen of decoded thumbnails. Glide sizes its memory
        // cache to 2 screens of ARGB pixels (~21 MB on a 1080x2424 phone). A full-width feed card
        // decodes to ~1.3 MB even in RGB_565, so that is ~16 cards - fewer than one feed's bound
        // + view-cached + recycled holders plus the FeedThumbnailPreloader's look-ahead, and a tab
        // switch (the whole grid rebinds) evicted the tab being left: coming back re-decoded its
        // cards from disk and faded them in again. 3 screens keeps both tabs' first screens and
        // the look-ahead hot. Still capped by Glide at 40% of the app's memory class, and the cache
        // is dropped on onTrimMemory like before.
        builder.setMemorySizeCalculator(new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(MEMORY_CACHE_SCREENS));
    }

    private static final float MEMORY_CACHE_SCREENS = 3;
}
