package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.newtube.mobile.player.Media3InlineFeedPlayer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Hold-to-play coordinator shared by Home and Search result RecyclerViews. */
public final class InlineFeedPlayback {
    private final Activity mActivity;
    private final RecyclerView mRecyclerView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<RecyclerView, RecyclerView.OnScrollListener> mNestedListeners = new WeakHashMap<>();
    private final Map<RecyclerView, RecyclerView.OnItemTouchListener> mNestedTouchListeners = new WeakHashMap<>();
    private final Set<RecyclerView.Adapter<?>> mObservedAdapters = new HashSet<>();
    private final Media3InlineFeedPlayer mPlayer;
    private final RecyclerView.OnItemTouchListener mTouchListener = createTouchListener();
    private final RecyclerView.AdapterDataObserver mAdapterObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            dataChanged();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            dataChanged();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            dataChanged();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            dataChanged();
        }
    };
    private final RecyclerView.OnScrollListener mScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
            scheduleVisibilityCheck();
        }

        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            scheduleVisibilityCheck();
        }
    };
    private final ConnectivityManager.NetworkCallback mNetworkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLost(@NonNull Network network) {
            mHandler.post(() -> {
                if (!FeedPlaybackSettings.isAllowed(mActivity)) {
                    stopPlayback();
                }
            });
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            if (FeedPlaybackSettings.getMode(mActivity) == FeedPlaybackSettings.WIFI_ONLY
                    && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                mHandler.post(InlineFeedPlayback.this::stopPlayback);
            }
        }
    };
    private boolean mReleased;
    private boolean mEnabled = true;
    private boolean mForeground = true;
    @Nullable private Video mActiveVideo;
    @Nullable private VideoCardAdapter.VideoViewHolder mActiveHolder;
    private final Runnable mVisibilityCheck = this::reconcilePlaybackVisibility;

    public InlineFeedPlayback(Activity activity, RecyclerView recyclerView) {
        mActivity = activity;
        mRecyclerView = recyclerView;
        mPlayer = new Media3InlineFeedPlayer(activity);
        recyclerView.addOnScrollListener(mScrollListener);
        recyclerView.addOnItemTouchListener(mTouchListener);
        recyclerView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> attachNestedLists());
        watchAdapter(recyclerView.getAdapter());
        attachNestedLists();
        registerNetworkCallback();
    }

    public void onResume() {
        mForeground = true;
        stopPlayback();
    }

    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        if (!enabled) {
            stopPlayback();
        }
    }

    public void watchAdapter(@Nullable RecyclerView.Adapter<?> adapter) {
        if (adapter instanceof VideoCardAdapter) {
            ((VideoCardAdapter) adapter).setInlineHoldListener(mHoldListener);
        }
        if (adapter != null && mObservedAdapters.add(adapter)) {
            adapter.registerAdapterDataObserver(mAdapterObserver);
        }
    }

    private final VideoCardAdapter.OnInlineHoldListener mHoldListener =
            new VideoCardAdapter.OnInlineHoldListener() {
                @Override
                public boolean canPlayInline(Video video, VideoCardAdapter.VideoViewHolder holder) {
                    return canPlayInlineNow(video, holder);
                }

                @Override
                public void playInline(Video video, VideoCardAdapter.VideoViewHolder holder) {
                    if (canPlayInlineNow(video, holder)) {
                        if (mPlayer.isPlaying(video, holder)) {
                            return;
                        }
                        mPlayer.play(video, holder);
                        mActiveVideo = video;
                        mActiveHolder = holder;
                    }
                }

                @Override
                public void onHolderUnbound(VideoCardAdapter.VideoViewHolder holder) {
                    if (mActiveHolder == holder) {
                        stopPlayback();
                    }
                }
            };

    /** Save the active feed position before routing the same video to the watch page. */
    public void handoff(Video video) {
        clearActivePlayback();
        mPlayer.handoff(video);
    }

    public void onStop() {
        mForeground = false;
        stopPlayback();
    }

    public void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        mRecyclerView.removeCallbacks(mVisibilityCheck);
        mRecyclerView.removeOnScrollListener(mScrollListener);
        mRecyclerView.removeOnItemTouchListener(mTouchListener);
        for (RecyclerView.Adapter<?> adapter : mObservedAdapters) {
            adapter.unregisterAdapterDataObserver(mAdapterObserver);
            if (adapter instanceof VideoCardAdapter) {
                ((VideoCardAdapter) adapter).setInlineHoldListener(null);
            }
        }
        mObservedAdapters.clear();
        for (Map.Entry<RecyclerView, RecyclerView.OnScrollListener> entry : mNestedListeners.entrySet()) {
            entry.getKey().removeOnScrollListener(entry.getValue());
        }
        mNestedListeners.clear();
        for (Map.Entry<RecyclerView, RecyclerView.OnItemTouchListener> entry : mNestedTouchListeners.entrySet()) {
            entry.getKey().removeOnItemTouchListener(entry.getValue());
        }
        mNestedTouchListeners.clear();
        unregisterNetworkCallback();
        mPlayer.release();
    }

    private void dataChanged() {
        scheduleVisibilityCheck();
        attachNestedLists();
    }

    private boolean canPlayInlineNow(Video video, VideoCardAdapter.VideoViewHolder holder) {
        if (mReleased || !mForeground || !mEnabled || mActivity.isFinishing() || mActivity.isDestroyed()
                || !FeedPlaybackSettings.isAllowed(mActivity)
                || !sameVideo(holder.inlineVideo(), video) || !eligible(video)) {
            return false;
        }
        Candidate candidate = candidate(video, holder);
        return candidate != null && candidate.visibleArea > 0;
    }

    private void attachNestedLists() {
        if (mReleased) {
            return;
        }
        watchAdapter(mRecyclerView.getAdapter());
        attachNestedLists(mRecyclerView);
    }

    private void attachNestedLists(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof RecyclerView && child != mRecyclerView) {
                RecyclerView nested = (RecyclerView) child;
                attachNestedListener(nested);
                watchAdapter(nested.getAdapter());
                attachNestedLists(nested);
            } else if (child instanceof ViewGroup) {
                attachNestedLists((ViewGroup) child);
            }
        }
    }

    @Nullable
    private Candidate candidate(Video video, VideoCardAdapter.VideoViewHolder holder) {
        View card = holder.itemView;
        if (!eligible(video) || !sameVideo(holder.inlineVideo(), video) || !card.isShown()
                || card.getWidth() <= 0 || card.getHeight() <= 0) {
            return null;
        }
        View videoArea = holder.inlineContainer();
        Rect visible = new Rect();
        if (!videoArea.getGlobalVisibleRect(visible)) {
            return null;
        }
        long visibleArea = (long) visible.width() * visible.height();
        return new Candidate(video, holder, visibleArea);
    }

    private static boolean eligible(@Nullable Video video) {
        return video != null && video.hasVideo() && video.videoId != null && !video.videoId.isEmpty()
                && !video.isLive && !video.isUpcoming && !video.belongsToShorts() && !video.isLocal()
                && !video.isPendingDownload();
    }

    private void attachNestedListener(RecyclerView nested) {
        if (!mNestedListeners.containsKey(nested)) {
            RecyclerView.OnScrollListener listener = new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    scheduleVisibilityCheck();
                    watchAdapter(recyclerView.getAdapter());
                }

                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    scheduleVisibilityCheck();
                }
            };
            mNestedListeners.put(nested, listener);
            nested.addOnScrollListener(listener);
        }
        if (!mNestedTouchListeners.containsKey(nested)) {
            RecyclerView.OnItemTouchListener touchListener = createTouchListener();
            mNestedTouchListeners.put(nested, touchListener);
            nested.addOnItemTouchListener(touchListener);
        }
    }

    private void scheduleVisibilityCheck() {
        if (mReleased) {
            return;
        }
        mRecyclerView.removeCallbacks(mVisibilityCheck);
        mRecyclerView.postOnAnimation(mVisibilityCheck);
    }

    private void reconcilePlaybackVisibility() {
        Video video = mActiveVideo;
        VideoCardAdapter.VideoViewHolder holder = mActiveHolder;
        if (video == null || holder == null) {
            return;
        }
        Candidate current = candidate(video, holder);
        if (!mForeground || !mEnabled || !FeedPlaybackSettings.isAllowed(mActivity)
                || !mPlayer.isPlaying(video, holder) || current == null || current.visibleArea == 0) {
            stopPlayback();
        }
    }

    private void stopPlayback() {
        clearActivePlayback();
        mPlayer.stop();
    }

    private void clearActivePlayback() {
        mActiveVideo = null;
        mActiveHolder = null;
    }

    private RecyclerView.OnItemTouchListener createTouchListener() {
        return new RecyclerView.SimpleOnItemTouchListener() {
            @Nullable private VideoCardAdapter.VideoViewHolder mTouchedHolder;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView,
                    @NonNull android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        View child = recyclerView.findChildViewUnder(event.getX(), event.getY());
                        RecyclerView.ViewHolder holder = child != null
                                ? recyclerView.getChildViewHolder(child) : null;
                        mTouchedHolder = holder instanceof VideoCardAdapter.VideoViewHolder
                                ? (VideoCardAdapter.VideoViewHolder) holder : null;
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                        finishTouchedHold();
                        break;
                    default:
                        break;
                }
                return false;
            }

            private void finishTouchedHold() {
                if (mTouchedHolder != null) {
                    mTouchedHolder.finishPendingInlineHold();
                    mTouchedHolder = null;
                }
            }
        };
    }

    private static boolean sameVideo(@Nullable Video first, @Nullable Video second) {
        return first == second || first != null && second != null && first.videoId != null
                && first.videoId.equals(second.videoId);
    }

    private void registerNetworkCallback() {
        ConnectivityManager manager = (ConnectivityManager) mActivity.getSystemService(Activity.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(mNetworkCallback);
            } else {
                manager.registerNetworkCallback(new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), mNetworkCallback);
            }
        } catch (RuntimeException error) {
            android.util.Log.w("InlineFeedPlayback", "Could not observe network changes", error);
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager manager = (ConnectivityManager) mActivity.getSystemService(Activity.CONNECTIVITY_SERVICE);
        if (manager != null) {
            try {
                manager.unregisterNetworkCallback(mNetworkCallback);
            } catch (IllegalArgumentException ignored) {
                // Registration may have been unavailable on this device.
            }
        }
    }

    private static final class Candidate {
        final Video video;
        final VideoCardAdapter.VideoViewHolder holder;
        final long visibleArea;

        Candidate(Video video, VideoCardAdapter.VideoViewHolder holder, long visibleArea) {
            this.video = video;
            this.holder = holder;
            this.visibleArea = visibleArea;
        }
    }
}
