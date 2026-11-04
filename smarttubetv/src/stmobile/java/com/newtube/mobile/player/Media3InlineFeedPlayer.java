package com.newtube.mobile.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** One reusable Media3 player for the active Home or Search feed item. */
public final class Media3InlineFeedPlayer {
    private static final ExecutorService SOURCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "InlineFeedSource");
        thread.setDaemon(true);
        return thread;
    });

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mProgressTick = new Runnable() {
        @Override
        public void run() {
            if (mPlayer != null && mSeek != null) {
                mSeek.setTimeline(mPlayer.getCurrentPosition(), mPlayer.getBufferedPosition(), mPlayer.getDuration());
                mMain.postDelayed(this, 250);
            }
        }
    };
    @Nullable private Media3SourceFactory mSourceFactory;
    @Nullable private DefaultTrackSelector mTrackSelector;
    @Nullable private ExoPlayer mPlayer;
    @Nullable private VideoCardAdapter.VideoViewHolder mHolder;
    @Nullable private Video mVideo;
    @Nullable private FrameLayout mOverlay;
    @Nullable private PlayerView mPlayerView;
    @Nullable private ProgressBar mBuffering;
    @Nullable private ImageButton mMuteButton;
    @Nullable private ImageButton mCaptionsButton;
    @Nullable private InlineSeekView mSeek;
    @Nullable private Disposable mFormatRequest;
    private long mGeneration;
    private boolean mMuted = true;
    private boolean mCaptionsEnabled = true;
    private boolean mReleased;

    private final Player.Listener mListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            if (mHolder != null && mPlayerView != null) {
                mHolder.setInlinePlaybackVisible(true);
                mPlayerView.animate().alpha(1f).setDuration(120).start();
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (mBuffering != null) {
                mBuffering.setVisibility(playbackState == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }
            if (playbackState == Player.STATE_ENDED && mPlayer != null) {
                mPlayer.setPlayWhenReady(false);
            }
        }

        @Override
        public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
            NetPath.log("inline-feed error video=" + (mVideo != null ? mVideo.videoId : "none")
                    + " code=" + error.errorCode);
            stop();
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            boolean hasText = false;
            boolean hasSelectedText = false;
            Tracks.Group fallbackGroup = null;
            int fallbackIndex = -1;
            Tracks.Group preferredGroup = null;
            int preferredIndex = -1;
            String preferredLanguage = Locale.getDefault().getLanguage();
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) {
                    continue;
                }
                hasText = true;
                for (int i = 0; i < group.length; i++) {
                    if (group.isTrackSelected(i)) {
                        hasSelectedText = true;
                    }
                    if (!group.isTrackSupported(i)) {
                        continue;
                    }
                    if (fallbackGroup == null) {
                        fallbackGroup = group;
                        fallbackIndex = i;
                    }
                    String language = group.getTrackFormat(i).language;
                    if (language != null && (language.equalsIgnoreCase(preferredLanguage)
                            || language.regionMatches(true, 0, preferredLanguage + "-", 0,
                                    preferredLanguage.length() + 1))) {
                        preferredGroup = group;
                        preferredIndex = i;
                    }
                }
            }
            if (mCaptionsButton != null) {
                mCaptionsButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
            }
            if (mCaptionsEnabled && !hasSelectedText && mTrackSelector != null) {
                Tracks.Group selectedGroup = preferredGroup != null ? preferredGroup : fallbackGroup;
                int selectedIndex = preferredGroup != null ? preferredIndex : fallbackIndex;
                if (selectedGroup != null && selectedIndex >= 0) {
                    mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setOverrideForType(new TrackSelectionOverride(
                                    selectedGroup.getMediaTrackGroup(), selectedIndex)));
                }
            }
            NetPath.log("inline-feed tracks text=" + (hasText ? "y" : "n")
                    + " selected=" + (hasSelectedText ? "y" : "n"));
        }
    };

    public Media3InlineFeedPlayer(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isPlaying(Video video, VideoCardAdapter.VideoViewHolder holder) {
        return sameVideo(mVideo, video) && mHolder == holder && mPlayer != null;
    }

    private static boolean sameVideo(@Nullable Video first, @Nullable Video second) {
        return first == second || first != null && second != null && first.videoId != null
                && first.videoId.equals(second.videoId);
    }

    public void play(Video video, VideoCardAdapter.VideoViewHolder holder) {
        if (mReleased || video == null || holder == null || video.videoId == null) {
            return;
        }
        stop();
        ensurePlayer();
        mVideo = video;
        mHolder = holder;
        mMuted = true;
        mCaptionsEnabled = true;
        mPlayer.setVolume(0f);
        mPlayer.setPlayWhenReady(false);
        createOverlay(holder);
        applyCaptionSelection(true);
        long generation = ++mGeneration;

        mFormatRequest = YouTubeMediaItemService.instance()
                .getFormatInfoObserve(video.videoId)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.from(SOURCE_EXECUTOR))
                .map(this::buildSource)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(source -> {
                    if (mReleased || generation != mGeneration || mPlayer == null || source == null) {
                        return;
                    }
                    mPlayer.setMediaSource(source);
                    mPlayer.prepare();
                    mPlayer.setPlayWhenReady(true);
                    NetPath.log("inline-feed prepare video=" + video.videoId);
                }, error -> {
                    if (generation == mGeneration) {
                        NetPath.log("inline-feed resolve-error video=" + video.videoId
                                + " type=" + error.getClass().getSimpleName());
                        stop();
                    }
                });
    }

    public void handoff(Video video) {
        if (mPlayer != null && mVideo != null && video != null
                && mVideo.videoId != null && mVideo.videoId.equals(video.videoId)
                && mPlayer.getCurrentPosition() > 0) {
            video.pendingPosMs = mPlayer.getCurrentPosition();
        }
        stop();
    }

    public void stop() {
        mGeneration++;
        if (mFormatRequest != null) {
            mFormatRequest.dispose();
            mFormatRequest = null;
        }
        mMain.removeCallbacks(mProgressTick);
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
            mPlayer.stop();
            mPlayer.clearMediaItems();
        }
        detachOverlay();
        if (mHolder != null) {
            mHolder.setInlinePlaybackVisible(false);
        }
        mHolder = null;
        mVideo = null;
    }

    public void release() {
        if (mReleased) {
            return;
        }
        stop();
        mReleased = true;
        if (mPlayer != null) {
            mPlayer.removeListener(mListener);
            mPlayer.release();
            mPlayer = null;
        }
    }

    private void ensurePlayer() {
        if (mPlayer != null) {
            return;
        }
        Media3PlayerInitializer initializer = new Media3PlayerInitializer(mContext);
        mTrackSelector = initializer.createTrackSelector();
        mSourceFactory = new Media3SourceFactory(mContext);
        mPlayer = initializer.createPlayer(mTrackSelector, mSourceFactory.getBandwidthMeter(), false);
        mPlayer.addListener(mListener);
    }

    @Nullable
    private MediaSource buildSource(MediaItemFormatInfo info) {
        if (info == null || !info.containsMedia() || info.isLive()) {
            return null;
        }
        MediaSource source = null;
        if (info.containsDashFormats()) {
            source = mSourceFactory.fromDashFormatInfo(info);
        }
        if (source == null && info.containsHlsUrl()) {
            source = mSourceFactory.fromHlsPlaylist(info.getHlsManifestUrl());
        }
        if (source == null && info.containsUrlFormats()) {
            source = mSourceFactory.fromUrlList(info.createUrlList());
        }
        if (source == null && info.containsSabrFormats() && info.isSabrVodEligible()) {
            source = mSourceFactory.fromSabrFormatInfo(info);
        }
        return source;
    }

    private void createOverlay(VideoCardAdapter.VideoViewHolder holder) {
        ViewGroup container = holder.inlineContainer();
        FrameLayout overlay = new FrameLayout(container.getContext());
        overlay.setBackgroundColor(Color.TRANSPARENT);

        PlayerView playerView = (PlayerView) LayoutInflater.from(container.getContext())
                .inflate(R.layout.view_inline_feed_player, overlay, false);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setAlpha(0f);
        playerView.setPlayer(mPlayer);
        playerView.setOnClickListener(v -> holder.itemView.performClick());
        overlay.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar buffering = new ProgressBar(container.getContext());
        FrameLayout.LayoutParams bufferingParams = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER);
        overlay.addView(buffering, bufferingParams);
        buffering.setVisibility(View.GONE);

        ImageButton mute = button(container.getContext(), R.drawable.ic_feed_muted);
        FrameLayout.LayoutParams muteParams = new FrameLayout.LayoutParams(dp(36), dp(36),
                Gravity.TOP | Gravity.END);
        muteParams.setMargins(0, dp(7), dp(7), 0);
        overlay.addView(mute, muteParams);
        mute.setOnClickListener(v -> {
            mMuted = !mMuted;
            mPlayer.setVolume(mMuted ? 0f : 1f);
            mute.setImageResource(mMuted ? R.drawable.ic_feed_muted : R.drawable.ic_feed_sound);
        });

        ImageButton captions = button(container.getContext(), R.drawable.ic_player_cc);
        FrameLayout.LayoutParams captionsParams = new FrameLayout.LayoutParams(dp(36), dp(36),
                Gravity.TOP | Gravity.END);
        captionsParams.setMargins(0, dp(7), dp(48), 0);
        overlay.addView(captions, captionsParams);
        captions.setVisibility(View.GONE);
        captions.setOnClickListener(v -> {
            mCaptionsEnabled = !mCaptionsEnabled;
            applyCaptionSelection(mCaptionsEnabled);
            captions.setImageResource(mCaptionsEnabled ? R.drawable.ic_player_cc : R.drawable.ic_player_cc_off);
        });

        InlineSeekView seek = new InlineSeekView(container.getContext());
        FrameLayout.LayoutParams seekParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24), Gravity.BOTTOM);
        seekParams.setMargins(dp(5), 0, dp(5), 0);
        overlay.addView(seek, seekParams);
        seek.setListener(position -> {
            if (mPlayer != null) {
                mPlayer.seekTo(position);
                mPlayer.setPlayWhenReady(true);
            }
        });

        container.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        holder.setInlinePlaybackVisible(false);
        mOverlay = overlay;
        mPlayerView = playerView;
        mBuffering = buffering;
        mMuteButton = mute;
        mCaptionsButton = captions;
        mSeek = seek;
        mMain.post(mProgressTick);
    }

    private ImageButton button(Context context, int drawable) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(circleBackground());
        button.setContentDescription(drawable == R.drawable.ic_player_cc ? "Captions" : "Sound");
        return button;
    }

    private GradientDrawable circleBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x99000000);
        return background;
    }

    private void applyCaptionSelection(boolean enabled) {
        if (mTrackSelector == null) {
            return;
        }
        String language = Locale.getDefault().getLanguage();
        DefaultTrackSelector.Parameters.Builder parameters = mTrackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (enabled) {
            parameters.setPreferredTextLanguage(language).setSelectUndeterminedTextLanguage(true);
        }
        mTrackSelector.setParameters(parameters);
    }

    private void detachOverlay() {
        if (mPlayerView != null) {
            mPlayerView.setPlayer(null);
        }
        if (mOverlay != null && mOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) mOverlay.getParent()).removeView(mOverlay);
        }
        mOverlay = null;
        mPlayerView = null;
        mBuffering = null;
        mMuteButton = null;
        mCaptionsButton = null;
        mSeek = null;
    }

    private int dp(float value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }
}
