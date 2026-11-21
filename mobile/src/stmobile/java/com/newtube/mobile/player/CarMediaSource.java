package com.newtube.mobile.player;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TimelineWithUpdatedMediaItem;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MEETUBE(android auto, issue #253): what the car's own player plays. A media id ending in a video
 * id resolves that video's /player answer only when the player prepares it, then plays the same
 * DASH source the Home preview builds (Media3SourceFactory, its transports and media cache); a
 * {@link MediaItem} with a local URI (a download) plays the file. The phone's player never sees it.
 */
public final class CarMediaSource extends CompositeMediaSource<Void> {
    private static final ExecutorService RESOLVE = Executors.newSingleThreadExecutor();

    /** The video id behind a car media id ({@code <parent>#<videoId>}), or null for none. */
    @Nullable
    public static String videoId(@Nullable String mediaId) {
        int hash = mediaId != null ? mediaId.lastIndexOf('#') : -1;
        return hash >= 0 && hash < mediaId.length() - 1 ? mediaId.substring(hash + 1) : null;
    }

    public static final class Factory implements MediaSource.Factory {
        private final Context mContext;
        private final DefaultMediaSourceFactory mFiles;
        @Nullable private Media3SourceFactory mDash;
        @Nullable private DefaultMediaSourceFactory mCached;

        public Factory(@NonNull Context context) {
            mContext = context.getApplicationContext();
            mFiles = new DefaultMediaSourceFactory(mContext);
        }

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider provider) {
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy policy) {
            return this;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return new int[] {C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_OTHER};
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem item) {
            if (item.localConfiguration != null) {
                String scheme = item.localConfiguration.uri.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) { // a song whole in the player's cache
                    if (mCached == null) {
                        Cache cache = Media3PlayerCache.get(mContext);
                        mCached = cache == null ? mFiles : new DefaultMediaSourceFactory(new CacheDataSource.Factory()
                                .setCache(cache)
                                .setCacheKeyFactory(Media3PlayerCache.getCacheKeyFactory())
                                .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(mContext)));
                    }
                    return mCached.createMediaSource(item);
                }
                return mFiles.createMediaSource(item); // a download on the phone
            }
            if (mDash == null) {
                mDash = new Media3SourceFactory(mContext);
            }
            return new CarMediaSource(item, mDash);
        }
    }

    private final MediaItem mItem;
    private final Media3SourceFactory mDash;
    @Nullable private MediaSource mChild;
    @Nullable private IOException mError;
    private int mGeneration;

    private CarMediaSource(MediaItem item, Media3SourceFactory dash) {
        mItem = item;
        mDash = dash;
    }

    @NonNull
    @Override
    public MediaItem getMediaItem() {
        return mItem;
    }

    @Override
    protected void prepareSourceInternal(@Nullable TransferListener transferListener) {
        super.prepareSourceInternal(transferListener);
        Handler playback = Util.createHandlerForCurrentLooper();
        int generation = ++mGeneration;
        String videoId = videoId(mItem.mediaId);
        RESOLVE.execute(() -> {
            MediaSource source = null;
            Throwable failure = null;
            try {
                MediaItemFormatInfo info = videoId == null ? null
                        : YouTubeMediaItemService.instance().getFormatInfoObserve(videoId).blockingFirst();
                if (info != null && !info.isUnplayable() && !info.isLive() && !info.isLiveContent()) {
                    source = mDash.fromDashFormatInfo(info);
                }
            } catch (Throwable e) { // never kill the resolve thread
                failure = e;
            }
            final MediaSource result = source;
            final Throwable error = failure;
            playback.post(() -> {
                if (generation != mGeneration) {
                    return; // released meanwhile
                }
                if (result == null) {
                    NetPath.log("car skip video=" + videoId + (error != null ? " " + error : ""));
                    mError = new IOException("car: no playable source for " + videoId, error);
                    return;
                }
                NetPath.log("car play video=" + videoId);
                mChild = result;
                prepareChildSource(null, result);
            });
        });
    }

    @Override
    public void maybeThrowSourceInfoRefreshError() throws IOException {
        if (mError != null) {
            throw mError;
        }
        super.maybeThrowSourceInfoRefreshError();
    }

    @Override
    protected void onChildSourceInfoRefreshed(Void id, MediaSource source, Timeline timeline) {
        // The DASH source carries its own MediaItem: the car shows ours (title, artist, art).
        refreshSourceInfo(TimelineWithUpdatedMediaItem.create(timeline, mItem));
    }

    @NonNull
    @Override
    public MediaPeriod createPeriod(@NonNull MediaPeriodId id, @NonNull Allocator allocator, long startPositionUs) {
        return mChild.createPeriod(id, allocator, startPositionUs);
    }

    @Override
    public void releasePeriod(@NonNull MediaPeriod period) {
        if (mChild != null) {
            mChild.releasePeriod(period);
        }
    }

    @Override
    protected void releaseSourceInternal() {
        mGeneration++;
        mChild = null;
        mError = null;
        super.releaseSourceInternal();
    }
}
