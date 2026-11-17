package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Glass;
import com.newtube.mobile.ui.common.GlassView;
import com.newtube.mobile.ui.common.GlowProgressBar;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.meetune.MeeTune;

import java.util.Locale;

/**
 * MEETUNE: the music look of {@link MobilePlaybackActivity}. The activity keeps owning the player
 * (so a song gets the video path's source planning and 403 recovery); this view only covers the
 * watch page while a MeeTune song plays and drives the player through {@link Controls}.
 */
public final class MeeTunePlayerSkin {
    interface Controls {
        long position();

        long duration();

        boolean playing();

        void playPause();

        void seek(long positionMs);

        void previous();

        void next();

        void minimize();

        void cast();

        void queue();

        void like();

        boolean liked();

        void more();

        @Nullable
        Format audioFormat();
    }

    private static final long TICK_MS = 250; // the sung line keeps up

    private final View mRoot;
    private final Controls mControls;
    private final ImageView mBg;
    private final ImageView mArt;
    private final TextView mFormat;
    private final TextView mTitle;
    private final TextView mArtist;
    private final ImageView mLike;
    private final SeekBar mSeek;
    private final TextView mElapsed;
    private final TextView mRemaining;
    private final ImageView mPlay;
    private final SeekBar mVolume;
    private final AudioManager mAudio;
    private boolean mSeeking;
    private static final int NORMAL = 0;
    private static final int LYRICS = 1; // the lyrics over the controls
    private static final int FULL = 2; // the lyrics, the whole page
    private static final long LEAD_MS = 300; // as LyricsSheet: a line lights as it starts
    private static final long HANDS_OFF_MS = 4_000;
    private int mMode = NORMAL;
    private final View mPage;
    private final View mControlsColumn;
    private final View mTitleRow;
    private final View mLineRow;
    private final TextView mLine;
    private final ScrollView mLyricsScroll;
    private final LinearLayout mLyricLines;
    private final ImageView mHeadLike;
    private final ImageView mHeadMore;
    private final ImageView mLyricsButton;
    private final android.graphics.drawable.Drawable mLyricsButtonBg;
    @Nullable
    private Lyrics mLyrics;
    /** The menu's Lyrics offset, this song's: a positive one lights each line sooner. */
    private long mLyricsOffset;
    private boolean mLyricsAsked;
    private int mSung = -2;
    private long mTouchedAt = -HANDS_OFF_MS;
    @Nullable
    private String mVideoId;
    private boolean mNextArtFetched;
    /** The play state the last update saw (null before the first): a change pops the title. */
    @Nullable private Boolean mWasPlaying;

    /**
     * Play: the title swells a little and springs back with a soft bounce. Pause: it dips a little and
     * settles the same way. Scaled from its left edge, where the text starts, so it never shifts.
     */
    private void popTitle(boolean playing) {
        mTitle.setPivotX(0);
        mTitle.setPivotY(mTitle.getHeight() / 2f);
        float to = playing ? 1.08f : 0.94f;
        mTitle.animate().cancel();
        mTitle.animate().scaleX(to).scaleY(to).setDuration(140)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> mTitle.animate().scaleX(1f).scaleY(1f).setDuration(420)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.6f)).start())
                .start();
    }
    /** Songs whose maxresdefault the next-song fetch found missing (a failed load isn't cached). */
    private static final java.util.Set<String> NO_MAXRES = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mRoot.postDelayed(this, TICK_MS);
        }
    };

    MeeTunePlayerSkin(ViewGroup parent, Controls controls) {
        mControls = controls;
        Context context = parent.getContext();
        mRoot = LayoutInflater.from(context).inflate(R.layout.meetune_player, parent, false);
        parent.addView(mRoot);
        mRoot.setElevation(100f); // over the watch page and its controls
        mBg = mRoot.findViewById(R.id.meetune_player_bg);
        mArt = mRoot.findViewById(R.id.meetune_player_art);
        mFormat = mRoot.findViewById(R.id.meetune_player_format);
        mTitle = mRoot.findViewById(R.id.meetune_player_title);
        mArtist = mRoot.findViewById(R.id.meetune_player_artist);
        mLike = mRoot.findViewById(R.id.meetune_player_like);
        mSeek = mRoot.findViewById(R.id.meetune_player_seek);
        mElapsed = mRoot.findViewById(R.id.meetune_player_elapsed);
        mRemaining = mRoot.findViewById(R.id.meetune_player_remaining);
        mPlay = mRoot.findViewById(R.id.meetune_player_play);
        mVolume = mRoot.findViewById(R.id.meetune_player_volume);
        mAudio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mPage = mRoot.findViewById(R.id.meetune_player_lyrics_page);
        mControlsColumn = mRoot.findViewById(R.id.meetune_player_controls);
        mTitleRow = mRoot.findViewById(R.id.meetune_player_title_row);
        mLineRow = mRoot.findViewById(R.id.meetune_player_line_row);
        mLine = mRoot.findViewById(R.id.meetune_player_line);
        mLyricsScroll = mRoot.findViewById(R.id.meetune_player_lyrics_scroll);
        mLyricLines = mRoot.findViewById(R.id.meetune_player_lyrics_lines);
        mHeadLike = mRoot.findViewById(R.id.meetune_player_head_like);
        mHeadMore = mRoot.findViewById(R.id.meetune_player_head_more);
        mLyricsButton = mRoot.findViewById(R.id.meetune_player_lyrics);
        mLyricsButtonBg = mLyricsButton.getBackground();

        if (Build.VERSION.SDK_INT >= 31) {
            mBg.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP));
            mBg.setScaleX(1.3f);
            mBg.setScaleY(1.3f);
        } else {
            mBg.setAlpha(0.35f);
        }
        mArt.setOutlineProvider(new android.view.ViewOutlineProvider() { // round as it lands on the pill
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mArtCorner);
            }
        });
        mArt.setClipToOutline(true);
        ((FadeImage) mArt).setFeather(true);
        // The art at the top, full width, as tall as its own shape: a square cover square, a wide
        // video still whole (never cropped to its middle).
        mArt.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitArt());
        mRoot.findViewById(R.id.meetune_player_controls).addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> { if (t != ot) mArt.post(this::fitArt); }); // a wide cover's middle
        frostButtons(mRoot.findViewById(R.id.meetune_player_controls), mRoot.findViewById(R.id.meetune_player_source));
        frostButtons(mRoot.findViewById(R.id.meetune_player_head), mRoot.findViewById(R.id.meetune_player_source));
        View handle = mRoot.findViewById(R.id.meetune_player_handle);
        View controlsColumn = mRoot.findViewById(R.id.meetune_player_controls);
        int pad = controlsColumn.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            controlsColumn.setPadding(controlsColumn.getPaddingLeft(), controlsColumn.getPaddingTop(),
                    controlsColumn.getPaddingRight(), pad + bars.bottom);
            return insets;
        });
        // The handle sits just under the status bar: the page may already start below it (it did,
        // and the handle came a whole status bar lower on the art).
        mRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            android.view.WindowInsets window = mRoot.getRootWindowInsets();
            int top = window == null ? 0 : WindowInsetsCompat.toWindowInsetsCompat(window)
                    .getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int[] at = new int[2];
            mRoot.getLocationOnScreen(at);
            int below = Math.max(0, top - (at[1] - Math.round(mRoot.getTranslationY())));
            handle.setTranslationY(below);
            int pageTop = below + Math.round(36 * context.getResources().getDisplayMetrics().density);
            if (mPage.getPaddingTop() != pageTop) {
                mPage.setPadding(0, pageTop, 0, mPage.getPaddingBottom());
            }
        });
        mControlsColumn.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> padPage());

        handle.setOnClickListener(v -> mControls.minimize());
        mArt.setOnClickListener(v -> mControls.minimize());
        bounce(mPlay, () -> {
            mControls.playPause();
            mPlay.postDelayed(this::update, 100);
        });
        bounce(mRoot.findViewById(R.id.meetune_player_previous), () -> {
            mDir = -1; // the songs move back (Previous only restarting the song: forward again soon)
            mRoot.postDelayed(() -> mDir = 1, 3000);
            mControls.previous();
        });
        bounce(mRoot.findViewById(R.id.meetune_player_next), mControls::next);
        for (View transport : new View[] {mPlay, mRoot.findViewById(R.id.meetune_player_previous),
                mRoot.findViewById(R.id.meetune_player_next)}) {
            glassPress(transport);
        }
        bounce(mLike, this::like);
        bounce(mRoot.findViewById(R.id.meetune_player_more), mControls::more);
        bounce(mLyricsButton, () -> setMode(mMode == NORMAL ? LYRICS : NORMAL));
        bounce(mHeadLike, this::like);
        bounce(mHeadMore, () -> {
            if (mMode == FULL) {
                share();
            } else {
                mControls.more();
            }
        });
        mLineRow.setOnClickListener(v -> setMode(LYRICS));
        mLyricLines.setOnClickListener(v -> setMode(mMode == FULL ? LYRICS : FULL)); // a tap: the whole page, and back
        mLyricsScroll.setOnTouchListener((v, event) -> {
            mTouchedAt = android.os.SystemClock.uptimeMillis(); // reading ahead: the sung line waits
            return false;
        });
        bounce(mRoot.findViewById(R.id.meetune_player_cast), mControls::cast);
        bounce(mRoot.findViewById(R.id.meetune_player_queue), mControls::queue);

        mSeek.setMax(1000);
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) showTimes(progress * mControls.duration() / 1000, mControls.duration());
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                mSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mSeeking = false;
                long duration = mControls.duration();
                if (duration > 0) mControls.seek(bar.getProgress() * duration / 1000);
            }
        });
        if (mAudio != null) {
            mVolume.setMax(mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            mVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (fromUser) mAudio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    if (mShownVolume >= 0 && progress != mShownVolume) showVolumeLevel(bar, progress);
                    mShownVolume = progress;
                }

                @Override public void onStartTrackingTouch(SeekBar bar) { }

                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }
        initCanvas();
        showOutput();
        mRoot.findViewById(R.id.meetune_player_info).setOnClickListener(v ->
                showDevice(mRoot.findViewById(R.id.meetune_player_device).getVisibility() != View.VISIBLE));
        if (mAudio != null) { // a speaker or headset connecting or leaving renames the line at once
            android.media.AudioDeviceCallback devices = new android.media.AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
                    showOutput();
                }

                @Override
                public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
                    showOutput();
                }
            };
            mRoot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mAudio.registerAudioDeviceCallback(devices, new android.os.Handler(android.os.Looper.getMainLooper()));
                    showOutput();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mAudio.unregisterAudioDeviceCallback(devices);
                }
            });
            if (mRoot.isAttachedToWindow()) {
                mAudio.registerAudioDeviceCallback(devices, new android.os.Handler(android.os.Looper.getMainLooper()));
            }
        }
    }

    // ----------------------------------------------------------------------------- moving cover

    private View mCanvas;
    private androidx.media3.ui.PlayerView mCanvasVideo;
    @Nullable private androidx.media3.exoplayer.ExoPlayer mCanvasPlayer;
    /** The song's clip, kept while the player is hidden so it plays again when shown. */
    @Nullable private String mCanvasUrl;
    /** 0: hidden, 1: shown (times the still cover's own alpha, which it follows). */
    private float mCanvasLevel;

    private void initCanvas() {
        mCanvas = mRoot.findViewById(R.id.meetune_player_canvas);
        mCanvasVideo = mRoot.findViewById(R.id.meetune_player_canvas_video);
        mCanvas.setOutlineProvider(mArt.getOutlineProvider());
        mCanvas.setClipToOutline(true);
        // The clip rides on the still cover: its place, size, swing and fade, every frame.
        mArt.getViewTreeObserver().addOnPreDrawListener(() -> {
            followArt();
            return true;
        });
        mCanvas.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (mCanvasPlayer != null) {
                    mCanvasVideo.setPlayer(null);
                    mCanvasPlayer.release();
                    mCanvasPlayer = null;
                }
            }
        });
    }

    private void followArt() {
        if (mCanvas.getVisibility() != View.VISIBLE) return;
        View source = (View) mArt.getParent();
        ViewGroup.LayoutParams params = mCanvas.getLayoutParams();
        if (params.width != mArt.getWidth() || params.height != mArt.getHeight()) {
            params.width = mArt.getWidth();
            params.height = mArt.getHeight();
            mCanvas.setLayoutParams(params);
        }
        mCanvas.setTranslationX(source.getLeft() + source.getTranslationX() + mArt.getLeft() + mArt.getTranslationX());
        mCanvas.setTranslationY(source.getTop() + source.getTranslationY() + mArt.getTop() + mArt.getTranslationY());
        mCanvas.setPivotX(mArt.getPivotX());
        mCanvas.setPivotY(mArt.getPivotY());
        mCanvas.setScaleX(mArt.getScaleX());
        mCanvas.setScaleY(mArt.getScaleY());
        mCanvas.setRotation(mArt.getRotation());
        mCanvas.setRotationY(mArt.getRotationY());
        mCanvas.setCameraDistance(mArt.getCameraDistance());
        // Landing on the pill (its corners rounding) the still cover goes alone, as a tile.
        boolean resting = mArtCorner == 0f && mArt.getVisibility() == View.VISIBLE;
        mCanvas.setAlpha(resting ? mArt.getAlpha() * mCanvasLevel : 0f);
    }

    /** Play {@code url} muted and looping over the cover; it fades in with its first frame. */
    private void startCanvas(String url) {
        mCanvasUrl = url;
        if (mCanvasPlayer == null) {
            Context context = mRoot.getContext().getApplicationContext();
            // A plain HTTP stack (not the InnerTube client), the clip kept in the media cache so a loop or replay is free.
            androidx.media3.datasource.DataSource.Factory http = new androidx.media3.datasource.DefaultHttpDataSource.Factory();
            androidx.media3.datasource.cache.Cache cache = com.newtube.mobile.player.Media3PlayerCache.get(context);
            androidx.media3.datasource.DataSource.Factory data = cache == null ? http
                    : new androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(http)
                    .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            mCanvasPlayer = new androidx.media3.exoplayer.ExoPlayer.Builder(context)
                    .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(data))
                    .build();
            mCanvasPlayer.setVolume(0f);
            mCanvasPlayer.setTrackSelectionParameters(mCanvasPlayer.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build());
            mCanvasPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
            mCanvasPlayer.addListener(new androidx.media3.common.Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    fadeCanvas(1f, 500);
                }

                @Override
                public void onPlayerError(@androidx.annotation.NonNull androidx.media3.common.PlaybackException error) {
                    stopCanvas(); // no clip: the still cover, as for most songs
                }
            });
            mCanvasVideo.setPlayer(mCanvasPlayer);
        }
        mCanvasPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(url));
        mCanvasPlayer.prepare();
        mCanvasPlayer.setPlayWhenReady(mControls.playing());
        mCanvas.setVisibility(View.VISIBLE);
        followArt();
    }

    /** Back to the still cover (a new song, or a clip that would not play). */
    private void stopCanvas() {
        mCanvasUrl = null;
        if (mCanvasPlayer != null) mCanvasPlayer.stop();
        mCanvasLevel = 0f;
        mCanvas.setAlpha(0f);
        mCanvas.setVisibility(View.GONE);
    }

    private void fadeCanvas(float to, long ms) {
        android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofFloat(mCanvasLevel, to).setDuration(ms);
        fade.addUpdateListener(a -> {
            mCanvasLevel = (float) a.getAnimatedValue();
            followArt();
        });
        fade.start();
    }

    /** Where the song plays: the Bluetooth speaker's or headset's own name, else the phone's. */
    private void showOutput() {
        Context context = mRoot.getContext();
        android.media.AudioDeviceInfo out = output();
        CharSequence name = out != null ? out.getProductName() : null;
        if (name == null || name.length() == 0) {
            name = Settings.Global.getString(context.getContentResolver(), "device_name");
        }
        ((TextView) mRoot.findViewById(R.id.meetune_player_device)).setText(
                name != null && name.length() > 0 ? name : context.getString(R.string.meetune_this_phone));
        mRoot.findViewById(R.id.meetune_player_headset).setVisibility(out != null ? View.VISIBLE : View.GONE);
    }

    private final Runnable mShowFormat = () -> showDevice(false);

    /** The pill's tap: where the song plays, for 10 s; a second tap (or the 10 s) brings the format back. */
    private void showDevice(boolean device) {
        View format = mRoot.findViewById(R.id.meetune_player_format_block);
        View name = mRoot.findViewById(R.id.meetune_player_device);
        mRoot.removeCallbacks(mShowFormat);
        View in = device ? name : format, out = device ? format : name;
        out.animate().cancel();
        out.animate().alpha(0f).translationY(-6 * out.getResources().getDisplayMetrics().density).setDuration(160)
                .withEndAction(() -> out.setVisibility(View.INVISIBLE)).start();
        in.animate().cancel();
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationY(6 * in.getResources().getDisplayMetrics().density);
        in.animate().alpha(1f).translationY(0f).setDuration(220).setStartDelay(80).start();
        if (device) mRoot.postDelayed(mShowFormat, 10_000);
    }

    private boolean mFormatPending;
    @Nullable
    private Format mShownFormat;
    private boolean mFormatLeaving;

    /** A new song: the pill's content blurs and fades off, keeping its size until the new format lands. */
    private void swapFormat() {
        mFormatPending = true;
        if (mFormat.length() == 0) { // the first song: a blurred stand-in (its size) until the format lands
            mFormat.setText("AUDIO");
            ((TextView) mRoot.findViewById(R.id.meetune_player_format_sub)).setText("160 kbps  ·  48 kHz  ·  Stereo");
            mShownFormat = null;
            blurPill(true, 0);
            return;
        }
        mFormatLeaving = true;
        blurPill(true, 220);
    }

    /** Blur off (out) or on (in): the pill's content through a 0 to 14 px blur, fading and settling. */
    private void blurPill(boolean out) {
        blurPill(out, out ? 220 : 380);
    }

    private void blurPill(boolean out, long ms) {
        View content = ((ViewGroup) mRoot.findViewById(R.id.meetune_player_info)).getChildAt(0);
        float dp = content.getResources().getDisplayMetrics().density;
        android.animation.ValueAnimator blur = android.animation.ValueAnimator.ofFloat(out ? 0f : 1f, out ? 1f : 0f);
        blur.setDuration(ms);
        blur.setInterpolator(out ? new android.view.animation.AccelerateInterpolator()
                : new android.view.animation.DecelerateInterpolator(1.6f));
        blur.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            content.setAlpha(1f - 0.85f * v);
            content.setScaleX(1f - 0.06f * v);
            content.setScaleY(1f - 0.06f * v);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                float r = 14 * dp * v;
                content.setRenderEffect(r < 0.5f ? null : android.graphics.RenderEffect.createBlurEffect(r, r,
                        android.graphics.Shader.TileMode.DECAL));
            }
        });
        if (out) {
            blur.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    mFormatLeaving = false;
                }
            });
        }
        blur.start();
    }

    /** An "OPUS" badge, then "147 kbps · 48 kHz · Stereo" (and "On phone" for a downloaded song). */
    private void showFormat(@Nullable Format format) {
        TextView sub = mRoot.findViewById(R.id.meetune_player_format_sub);
        if (format == null) {
            mFormat.setText(null);
            sub.setText(null);
            return;
        }
        String mime = format.sampleMimeType != null ? format.sampleMimeType : format.codecs;
        String codec = MimeTypes.AUDIO_OPUS.equals(mime) ? "OPUS"
                : MimeTypes.AUDIO_AAC.equals(mime) || (mime != null && mime.startsWith("mp4a")) ? "AAC"
                : mime != null ? mime.replace("audio/", "").toUpperCase(Locale.US) : "AUDIO";
        mFormat.setText(codec);
        mShownFormat = format;
        java.util.List<String> specs = new java.util.ArrayList<>();
        if (format.bitrate > 0) specs.add(Math.round(format.bitrate / 1000f) + " kbps");
        if (format.sampleRate > 0) {
            float khz = format.sampleRate / 1000f;
            specs.add((khz == Math.round(khz) ? String.valueOf(Math.round(khz)) : String.format(Locale.US, "%.1f", khz)) + " kHz");
        }
        if (format.channelCount == 1) specs.add("Mono");
        else if (format.channelCount == 2) specs.add("Stereo");
        else if (format.channelCount > 2) specs.add(format.channelCount == 6 ? "5.1" : format.channelCount == 8 ? "7.1" : format.channelCount + " ch");
        if (mVideo != null && mVideo.localUri != null) specs.add("On phone");
        sub.setText(android.text.TextUtils.join("  ·  ", specs));
    }

    /**
     * The cover's own colour for the pill's bars and glass: the hue that covers the most of it (each
     * coloured pixel of a 24x24 copy votes for one of 12 hues, by saturation weighted by brightness),
     * averaged and lifted bright enough to glow on the dark glass. A small vivid detail on a grey
     * cover (red lettering on a black poster) no longer wins: a cover with little colour gets soft
     * white bars.
     */
    public static int accent(android.graphics.drawable.Drawable art) {
        android.graphics.Bitmap small = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(small);
        android.graphics.Rect bounds = art.copyBounds();
        art.setBounds(0, 0, 24, 24);
        art.draw(canvas);
        art.setBounds(bounds);
        int[] pixels = new int[24 * 24];
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24);
        small.recycle();
        return accent(pixels);
    }

    /** {@link #accent(android.graphics.drawable.Drawable)} on a cover's pixels. */
    static int accent(int[] pixels) {
        float[] weight = new float[12], sat = new float[12], val = new float[12];
        float[] x = new float[12], y = new float[12]; // hue as a vector, so 355 and 5 average to 0
        float[] hsv = new float[3];
        for (int pixel : pixels) {
            android.graphics.Color.colorToHSV(pixel, hsv);
            float score = hsv[1] * (0.3f + hsv[2]);
            if (score < 0.12f) continue; // grey, black or white: no vote
            int b = Math.min(11, (int) (hsv[0] / 30f));
            weight[b] += score;
            sat[b] += hsv[1] * score;
            val[b] += hsv[2] * score;
            x[b] += (float) Math.cos(Math.toRadians(hsv[0])) * score;
            y[b] += (float) Math.sin(Math.toRadians(hsv[0])) * score;
        }
        int best = 0;
        for (int b = 1; b < 12; b++) if (weight[b] > weight[best]) best = b;
        if (weight[best] < pixels.length * MIN_COLOUR_SHARE) return 0xFFF2F2F2;
        float hue = (float) Math.toDegrees(Math.atan2(y[best], x[best]));
        float[] out = {hue < 0 ? hue + 360 : hue,
                Math.min(sat[best] / weight[best], 0.75f), // vivid, never neon
                Math.max(val[best] / weight[best], 0.95f)}; // bright enough to glow
        return android.graphics.Color.HSVToColor(out);
    }

    /** How much of a cover one hue must colour (in votes per pixel) to be its accent. */
    static final float MIN_COLOUR_SHARE = 0.06f;

    /** Five bars glowing in the cover's colour, dancing while the song plays, resting low while it is paused. */
    public static final class EqBars extends View {
        private static final float[] SPEED = {6.3f, 8.9f, 7.4f, 9.7f, 5.8f};
        private static final float[] PHASE = {0.4f, 1.9f, 0f, 2.7f, 1.2f};
        private static final float[] REST = {0.3f, 0.55f, 0.8f, 0.55f, 0.3f};
        private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF mRect = new android.graphics.RectF();
        private boolean mPlaying;
        private float mMotion; // 0 resting .. 1 dancing, eased between

        public EqBars(Context context, AttributeSet attrs) {
            super(context, attrs);
            mBar.setColor(0xFF5BE38A);
            mBar.setShadowLayer(6 * getResources().getDisplayMetrics().density, 0, 0, 0xCC5BE38A); // the glow
            setLayerType(LAYER_TYPE_SOFTWARE, null); // a shadow on shapes needs it before Android 9
        }

        /** The bars and their glow take {@code color}, easing over from the old one. */
        void setColor(int color) {
            int from = mBar.getColor();
            if (from == color) return;
            android.animation.ValueAnimator shift = android.animation.ValueAnimator.ofArgb(from, color).setDuration(600);
            shift.addUpdateListener(a -> {
                int c = (int) a.getAnimatedValue();
                mBar.setColor(c);
                mBar.setShadowLayer(6 * getResources().getDisplayMetrics().density, 0, 0, (c & 0x00FFFFFF) | 0xCC000000);
                invalidate();
            });
            shift.start();
        }

        void setPlaying(boolean playing) {
            if (playing == mPlaying) return;
            mPlaying = playing;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            mMotion = Math.max(0f, Math.min(1f, mMotion + (mPlaying ? 0.08f : -0.06f)));
            float w = getWidth(), h = getHeight(), bar = w / 9f, gap = (w - 5 * bar) / 4f;
            float t = android.os.SystemClock.uptimeMillis() / 1000f;
            for (int i = 0; i < 5; i++) {
                float dance = 0.2f + 0.8f * Math.abs((float) Math.sin(t * SPEED[i] / 2 + PHASE[i]));
                float level = REST[i] + (dance - REST[i]) * mMotion;
                float tall = Math.max(bar, h * 0.8f * level);
                float left = i * (bar + gap);
                mRect.set(left, (h - tall) / 2, left + bar, (h + tall) / 2);
                canvas.drawRoundRect(mRect, bar / 2, bar / 2, mBar);
            }
            if (mPlaying || mMotion > 0f) postInvalidateOnAnimation();
        }
    }

    /** The output media plays on, if it is not the phone's own speaker; null for the phone. */
    @Nullable
    private android.media.AudioDeviceInfo output() {
        if (mAudio == null) return null;
        java.util.List<android.media.AudioDeviceInfo> routed = null;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            routed = mAudio.getAudioDevicesForAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
        }
        if (routed == null || routed.isEmpty()) { // older Android: the likeliest route, as it picks
            routed = java.util.Arrays.asList(mAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
        }
        int[] order = {android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE};
        for (int type : order) {
            for (android.media.AudioDeviceInfo device : routed) {
                if (device.getType() == type) return device;
            }
        }
        return null;
    }

    /** The volume's last level; -1 until the bar first shows it, so opening the player shows no bubble. */
    private int mShownVolume = -1;
    private final Runnable mHideVolumeLevel = this::hideVolumeLevel;

    private void hideVolumeLevel() {
        mRoot.findViewById(R.id.meetune_player_volume_bubble).animate().alpha(0f).setDuration(260).start();
    }

    /** The glass pill over the volume bar's end, with the level in percent; it glides as the level moves and fades soon after. */
    private void showVolumeLevel(SeekBar bar, int level) {
        View bubble = mRoot.findViewById(R.id.meetune_player_volume_bubble);
        TextView text = mRoot.findViewById(R.id.meetune_player_volume_level);
        text.setText(String.format(Locale.getDefault(), "%d%%", Math.round(100f * level / Math.max(1, bar.getMax()))));
        bubble.removeCallbacks(mHideVolumeLevel);
        bubble.post(() -> { // its width follows the text
            int left = bar.getPaddingLeft();
            float x = left + (bar.getWidth() - left - bar.getPaddingRight()) * (float) level / Math.max(1, bar.getMax())
                    - bubble.getWidth() / 2f;
            x = Math.max(0f, Math.min(x, bar.getWidth() - bubble.getWidth()));
            if (bubble.getAlpha() == 0f) bubble.setTranslationX(x);
            bubble.animate().alpha(1f).translationX(x).setDuration(180)
                    .setInterpolator(com.newtube.mobile.ui.common.Motion.STANDARD).start();
            bubble.postDelayed(mHideVolumeLevel, 1100);
        });
    }

    /**
     * Every button is glass frosting the art behind it, with a soft white glow around it (a white
     * elevation shadow, API 28+). Nothing in the controls clips, so the glow reaches past its cell.
     */
    private static void frostButtons(View view, View source) {
        if (view instanceof GlassView) {
            Glass.blur(view, source); // frosted, no glow: only the seek and volume bars glow
            view.setElevation(0f);
            view.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getHeight() / 2f); // round, or a pill
                }
            });
            view.setClipToOutline(true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            group.setClipChildren(false);
            group.setClipToPadding(false);
            for (int i = 0; i < group.getChildCount(); i++) {
                frostButtons(group.getChildAt(i), source);
            }
        }
    }

    @Nullable
    private Video mVideo;

    /** Each swinging view's running animator and where it is (0 at rest, -1 / 1 off to a side). */
    private static final java.util.Map<Object, android.animation.ValueAnimator> SWINGS = new java.util.WeakHashMap<>();
    private static final java.util.Map<Object, Float> SWUNG = new java.util.WeakHashMap<>();
    /** Which way the songs move: 1 forward (next, or the next song by itself), -1 back (previous). */
    private int mDir = 1;
    @Nullable
    private android.graphics.drawable.Drawable mPendingArt;
    private boolean mArtLeaving;

    /** Puts {@code view} at {@code p} at once (see {@link #swing}). */
    private static void place(View view, float p, float dist, float turn) {
        SWUNG.put(view, p);
        float a = Math.min(1f, Math.abs(p));
        view.setTranslationX(p * dist);
        view.setRotationY(-p * turn);
        view.setScaleX(1f - 0.16f * a);
        view.setScaleY(1f - 0.16f * a);
        view.setAlpha(1f - a);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float blur = 32 * view.getResources().getDisplayMetrics().density * a;
            view.setRenderEffect(blur < 0.5f ? null : RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        }
    }

    /**
     * The song change as a card swipe: {@code view} slides {@code dist} to a side, turning
     * {@code turn} degrees away, shrinking, fading and blurring out ({@code to} -1 or 1), or comes
     * back to rest from where it is ({@code to} 0, on a spring that settles just past it).
     */
    private static void swing(View view, float to, long ms, float dist, float turn, @Nullable Runnable end) {
        android.animation.ValueAnimator running = SWINGS.remove(view);
        if (running != null) running.cancel();
        Float now = SWUNG.get(view);
        float from = now != null ? now : 0f;
        android.animation.ValueAnimator swing = android.animation.ValueAnimator.ofFloat(from, to);
        swing.setDuration(ms);
        swing.setInterpolator(to == 0f ? new android.view.animation.OvershootInterpolator(1.1f)
                : new android.view.animation.AccelerateInterpolator(1.4f));
        swing.addUpdateListener(a -> place(view, (float) a.getAnimatedValue(), dist, turn));
        swing.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (SWINGS.get(view) == animation) SWINGS.remove(view);
                if (!mCancelled && to == 0f) {
                    place(view, 0f, dist, turn);
                    SWUNG.remove(view);
                }
                if (!mCancelled && end != null) end.run();
            }
        });
        SWINGS.put(view, swing);
        swing.start();
    }

    private float artDist() {
        return mArt.getWidth() * 0.62f;
    }

    /** The view's picture drawn into a bitmap Glide doesn't own (at most 512px a side), or null. */
    @Nullable
    private static android.graphics.drawable.Drawable ownCopy(ImageView view) {
        android.graphics.drawable.Drawable d = view.getDrawable();
        int w = d == null ? 0 : d.getIntrinsicWidth(), h = d == null ? 0 : d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return d; // no bitmap behind it (a colour, a drawn cover): nothing to lose
        float s = Math.min(1f, 512f / Math.max(w, h));
        android.graphics.Bitmap copy = android.graphics.Bitmap.createBitmap(
                Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(copy);
        android.graphics.Rect bounds = d.copyBounds();
        d.setBounds(0, 0, copy.getWidth(), copy.getHeight());
        d.draw(canvas);
        d.setBounds(bounds);
        return new android.graphics.drawable.BitmapDrawable(view.getResources(), copy);
    }

    /** The showing art's height over its width (1: square). */
    private float mArtShape = 1f;

    private void fitArt() {
        int width = mArt.getWidth();
        if (width == 0) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mArt.getLayoutParams();
        int height = Math.round(width * mArtShape);
        // A wide cover (a video's 16:9 frame) no longer hugs the top under the status bar: it sits in
        // the middle of the space above the song's name. A square one still fills it from the top.
        View controls = mRoot.findViewById(R.id.meetune_player_controls);
        int top = height < width && controls.getTop() > height ? (controls.getTop() - height) / 2 : 0;
        if (params.height != height || params.topMargin != top) {
            params.height = height;
            params.topMargin = top;
            mArt.setLayoutParams(params);
        }
    }

    /**
     * The song's pill is glass in its cover's colour: the accent over the frost, light enough that the
     * white text stays clear, easing over from the last song's like the bars.
     */
    private void tintInfoPill(int accent) {
        com.newtube.mobile.ui.common.GlassView pill = mRoot.findViewById(R.id.meetune_player_info);
        int to = accent == 0xFFF2F2F2 ? 0x17FFFFFF : (accent & 0x00FFFFFF) | 0x26000000; // faint: the pill stays in the background
        float dp = mRoot.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable badge = new android.graphics.drawable.GradientDrawable();
        badge.setCornerRadius(8 * dp);
        badge.setColor((accent & 0x00FFFFFF) | 0x2E000000);
        badge.setStroke(Math.max(1, Math.round(0.75f * dp)), (accent & 0x00FFFFFF) | 0x80000000);
        mFormat.setBackground(badge);
        int from = pill.getTint();
        if (from == to) return;
        android.animation.ValueAnimator shift = android.animation.ValueAnimator.ofArgb(from, to).setDuration(600);
        shift.addUpdateListener(a -> pill.setTint((int) a.getAnimatedValue()));
        shift.start();
    }

    /** The new cover is here: in from the side the songs move from, unless it is the first one. */
    private void showArt(@Nullable android.graphics.drawable.Drawable art) {
        if (art == null) return;
        int w = art.getIntrinsicWidth(), h = art.getIntrinsicHeight();
        mArtShape = w > 0 && h > 0 ? Math.min(1f, h / (float) w) : 1f; // taller than square: a square, cropped a little
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setImageDrawable(art);
        fitArt();
        int accent = accent(art);
        ((EqBars) mRoot.findViewById(R.id.meetune_player_eq)).setColor(accent);
        tintInfoPill(accent);
        Float at = SWUNG.get(mArt);
        if (at != null && Math.abs(at) >= 0.99f) { // it left: come in from the other side
            place(mArt, mDir, artDist(), 24f);
            swing(mArt, 0f, 440, artDist(), 24f, null);
        }
    }

    /**
     * The app's image loader, not the player activity's: the activity holds its images while a song
     * opens until the first video frame, and a song shows none, so the cover waited for the hold's
     * 6 s timeout on every switch.
     */
    private com.bumptech.glide.RequestManager images() {
        return Glide.with(mArt.getContext().getApplicationContext());
    }

    void bind(@Nullable Video video) {
        if (video == null) return;
        mVideo = video;
        boolean switching = mVideoId != null && video.videoId != null && !video.videoId.equals(mVideoId);
        Runnable names = () -> {
            mTitle.setText(video.getTitle());
            mTitle.setSelected(true); // marquee
            mArtist.setText(video.getAuthor());
            ((TextView) mRoot.findViewById(R.id.meetune_player_head_title)).setText(video.getTitle());
            ((TextView) mRoot.findViewById(R.id.meetune_player_head_artist)).setText(video.getAuthor());
        };
        View nameBlock = (View) mTitle.getParent();
        if (switching) { // the old song swipes away, the new one comes in behind it
            int dir = mDir;
            mDir = 1;
            float nameDist = mRoot.getWidth() * 0.3f;
            swing(nameBlock, -dir, 200, nameDist, 0f, () -> {
                names.run();
                place(nameBlock, dir, nameDist, 0f);
                swing(nameBlock, 0f, 560, nameDist, 0f, null);
            });
            mArt.setCameraDistance(9000 * mArt.getResources().getDisplayMetrics().density);
            mArtLeaving = true;
            mPendingArt = null;
            mDir = dir; // showArt reads it until the cover is in
            swing(mArt, -dir, 240, artDist(), 24f, () -> {
                mArtLeaving = false;
                android.graphics.drawable.Drawable art = mPendingArt;
                mPendingArt = null;
                showArt(art);
                mDir = 1;
            });
        } else if (!SWINGS.containsKey(nameBlock)) {
            names.run();
        }
        if (video.videoId != null && !video.videoId.equals(mVideoId)) {
            mVideoId = video.videoId;
            mNextArtFetched = false;
            images().load(MeeTune.art(video)).into((ImageView) mRoot.findViewById(R.id.meetune_player_head_art));
            fetchLyrics(video);
            stopCanvas();
            String canvasFor = video.videoId;
            com.newtube.mobile.ui.meetune.MeeTuneCanvas.find(video, url -> {
                if (url != null && canvasFor.equals(mVideoId)) startCanvas(url);
            });
            if (mRoot.getContext() instanceof android.app.Activity) {
                MeeTune.learn((android.app.Activity) mRoot.getContext(), video); // the menu's Open album, Open artist
                MeeTune.checkLike((android.app.Activity) mRoot.getContext(), video.videoId, this::update); // the heart
                MeeTune.remember(mRoot.getContext(), video); // MeeTune's own History
            }
            swapFormat(); // the old song's format blurs away; the new one blurs in once it plays
            // A new load hands the old song's bitmaps back to Glide, which may recycle them while the
            // old cover still swings out and the backdrop still fades from it: keep our own copies.
            mArt.setImageDrawable(ownCopy(mArt));
            mBg.setImageDrawable(ownCopy(mBg));
            String art = NO_MAXRES.contains(video.videoId) // known missing: straight to the fallback
                    ? "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg" : MeeTune.art(video);
            String fallback = "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";
            com.bumptech.glide.RequestManager images = images();
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> cover = images.load(art)
                    .error(images.load(fallback));
            if (art.startsWith("https://i.ytimg.com/")) { // a video's big thumbnail: its small one first, quick
                cover = cover.thumbnail(images.load("https://i.ytimg.com/vi/" + video.videoId + "/mqdefault.jpg"));
            }
            // The cover fills the square at the top; a wide thumbnail (a song with no album art) is cropped
            // to its middle, where a Topic song keeps its album art.
            cover.into(new com.bumptech.glide.request.target.CustomViewTarget<ImageView, android.graphics.drawable.Drawable>(mArt) {
                @Override
                protected void onResourceCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                }

                @Override
                public void onLoadFailed(@Nullable android.graphics.drawable.Drawable error) {
                    if (mArtLeaving) mPendingArt = mArt.getDrawable(); // nothing new: the old cover comes back
                    else showArt(mArt.getDrawable());
                }

                @Override
                public void onResourceReady(@androidx.annotation.NonNull android.graphics.drawable.Drawable art,
                                            @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> t) {
                    if (mArtLeaving) mPendingArt = art; // in once the old one is out
                    else showArt(art);
                }
            });
            // The ambient behind it melts from the old song's colours to the new one's.
            images.load(art).error(images.load(fallback)).placeholder(mBg.getDrawable())
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(700))
                    .into(mBg);
        }
    }

    // ----------------------------------------------------------------------------- lyrics

    private void fetchLyrics(Video video) {
        String id = video.videoId;
        mLyrics = null;
        mLyricsAsked = false;
        mLyricsOffset = 0;
        Lyrics kept = keptLyrics(mRoot.getContext(), video);
        if (kept != null) { // saved with the song's download: no internet needed
            mLyricsAsked = true;
            mLyrics = kept;
            showLyrics();
            return;
        }
        showLyrics();
        Lyrics.fetch(id, video.getTitle() != null ? video.getTitle() : "",
                video.getAuthor() != null ? video.getAuthor() : "", song -> {
                    if (!id.equals(mVideoId)) return; // an answer for the song before
                    mLyricsAsked = true;
                    mLyrics = song != null ? song.lyrics(song.shown) : null;
                    showLyrics();
                });
    }

    private static java.io.File lyricsFile(Context context, String videoId) {
        return new java.io.File(context.getFilesDir(), "meetune_lyrics/" + videoId + ".lrc");
    }

    /**
     * Saves a downloaded song's lyrics on the phone, for the player to show offline. The file's first
     * line is T (timed) or U, then the source; the rest is LRC or plain lines.
     */
    public static void keepLyrics(Context context, Video video) {
        if (video == null || video.videoId == null) return;
        java.io.File file = lyricsFile(context.getApplicationContext(), video.videoId);
        Lyrics.fetch(video.videoId, video.getTitle() != null ? video.getTitle() : "",
                video.getAuthor() != null ? video.getAuthor() : "", song -> {
                    Lyrics lyrics = song != null ? song.lyrics(song.shown) : null;
                    if (lyrics == null || lyrics.lines.isEmpty()) return;
                    StringBuilder out = new StringBuilder(lyrics.times != null ? "T" : "U")
                            .append(lyrics.source != null ? lyrics.source : "").append('\n');
                    for (int i = 0; i < lyrics.lines.size(); i++) {
                        if (lyrics.times != null) {
                            long t = lyrics.times[i];
                            out.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", t / 60_000, t / 1000 % 60, t % 1000));
                        }
                        out.append(lyrics.lines.get(i).replace('\n', ' ')).append('\n');
                    }
                    Lyrics.POOL.execute(() -> {
                        file.getParentFile().mkdirs();
                        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                            stream.write(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch (java.io.IOException ignored) {
                            // no space: the player asks online as before
                        }
                    });
                });
    }

    /** Drops a song's saved lyrics with its download. */
    public static void forgetLyrics(Context context, String videoId) {
        //noinspection ResultOfMethodCallIgnored
        lyricsFile(context.getApplicationContext(), videoId).delete();
    }

    @Nullable
    private static Lyrics keptLyrics(Context context, Video video) {
        java.io.File file = lyricsFile(context, video.videoId);
        if (!file.isFile()) return null;
        try (java.util.Scanner in = new java.util.Scanner(file, "UTF-8")) {
            String text = in.useDelimiter("\\A").hasNext() ? in.next() : "";
            int cut = text.indexOf('\n');
            if (cut < 1) return null;
            String source = text.substring(1, cut);
            String body = text.substring(cut + 1);
            String title = video.getTitle() != null ? video.getTitle() : "";
            String artist = video.getAuthor() != null ? video.getAuthor() : "";
            return text.charAt(0) == 'T' ? Lyrics.parseLrc(body, title, artist, source)
                    : new Lyrics(title, artist, java.util.Arrays.asList(body.split("\n")), null, source);
        } catch (Exception e) {
            return null;
        }
    }

    /** The lines into the lyrics page; the line over the bar shows only for timed lyrics. */
    private void showLyrics() {
        Context context = mRoot.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        mLyricLines.removeAllViews();
        mSung = -2;
        if (mLyrics == null || mLyrics.lines.isEmpty()) {
            mLyricLines.addView(lyricLine(context.getString(mLyricsAsked
                    ? R.string.meetune_lyrics_none : R.string.meetune_lyrics_finding), 22, 0x99FFFFFF));
        } else if (mLyrics.times == null) {
            TextView text = lyricLine(String.join("\n", mLyrics.lines), 22, 0xE6FFFFFF);
            text.setLineSpacing(0, 1.25f);
            mLyricLines.addView(text);
        } else {
            for (String words : mLyrics.lines) {
                TextView line = lyricLine(words, 30, 0x59FFFFFF);
                line.setPadding(0, 0, 0, Math.round(20 * density));
                mLyricLines.addView(line);
            }
        }
        if (mLyrics != null && mLyrics.source != null && !mLyrics.source.isEmpty()) {
            TextView source = lyricLine(context.getString(R.string.mobile_lyrics_source, mLyrics.source), 14, 0x99FFFFFF);
            source.setTypeface(com.newtube.mobile.ui.common.AppFont.regular(source.getContext()));
            source.setPadding(0, Math.round(12 * density), 0, 0);
            mLyricLines.addView(source);
        }
        mLineRow.setVisibility(lineRowVisibility(mMode));
        followLyrics(mControls.position());
    }

    /** The sung line's row keeps its room on the normal page, lyrics or not, so the title and bar never move. */
    private int lineRowVisibility(int mode) {
        return mode != NORMAL ? View.GONE : timed() ? View.VISIBLE : View.INVISIBLE;
    }

    private TextView lyricLine(CharSequence text, int sp, int color) {
        TextView line = new TextView(mRoot.getContext());
        line.setText(text);
        line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        line.setTextColor(color);
        line.setTypeface(com.newtube.mobile.ui.common.AppFont.bold(line.getContext()));
        return line;
    }

    public long lyricsOffset() {
        return mLyricsOffset;
    }

    public void setLyricsOffset(long offsetMs) {
        mLyricsOffset = offsetMs;
    }

    private boolean timed() {
        return mLyrics != null && mLyrics.times != null && !mLyrics.lines.isEmpty();
    }

    /** Lights the sung line, keeps it a third of the way down, and puts it over the bar. */
    private void followLyrics(long position) {
        if (!timed()) return;
        long[] times = mLyrics.times;
        long at = position + LEAD_MS + mLyricsOffset;
        int sung = -1;
        while (sung + 1 < times.length && times[sung + 1] <= at) sung++;
        if (sung == mSung) return;
        if (mSung >= 0 && mSung < mLyricLines.getChildCount()) {
            ((TextView) mLyricLines.getChildAt(mSung)).setTextColor(0x59FFFFFF);
        }
        mSung = sung;
        showLine(sung < 0 ? "" : mLyrics.lines.get(sung).replace("\u266A", "").trim());
        if (sung < 0 || sung >= mLyricLines.getChildCount()) return;
        View line = mLyricLines.getChildAt(sung);
        ((TextView) line).setTextColor(0xFFFFFFFF);
        if (mMode != NORMAL && android.os.SystemClock.uptimeMillis() - mTouchedAt > HANDS_OFF_MS) {
            mLyricsScroll.post(() -> mLyricsScroll.smoothScrollTo(0,
                    Math.max(0, mLyricLines.getTop() + line.getTop() - mLyricsScroll.getHeight() / 3)));
        }
    }

    /**
     * The line over the bar: shows only while a line is sung (not before the first, nor through
     * instrumental breaks); each next line slides up in as the last slides up out.
     */
    private void showLine(String words) {
        View chevron = ((ViewGroup) mLineRow).getChildAt(1);
        float shift = 10 * mRoot.getResources().getDisplayMetrics().density;
        boolean lit = mLine.getAlpha() > 0f && mLine.length() > 0;
        mLineRow.setClickable(!words.isEmpty());
        if (lit && words.contentEquals(mLine.getText())) return;
        mLine.animate().cancel();
        chevron.animate().alpha(words.isEmpty() ? 0f : 1f).setDuration(200).start();
        Runnable in = () -> {
            if (words.isEmpty()) {
                mLine.setText(null);
                return;
            }
            mLine.setText(words);
            mLine.setTranslationY(shift);
            mLine.setAlpha(0f);
            mLine.animate().alpha(1f).translationY(0f).setDuration(340)
                    .setInterpolator(com.newtube.mobile.ui.common.Motion.STANDARD).start();
        };
        if (lit) {
            mLine.animate().alpha(0f).translationY(-shift).setDuration(170)
                    .setInterpolator(com.newtube.mobile.ui.common.Motion.STANDARD).withEndAction(in).start();
        } else {
            in.run();
        }
    }

    /**
     * NORMAL: the big art and the song over the controls. LYRICS: the song moves up small with its
     * star and More, the lines between it and the controls. FULL: the lines take the whole page
     * (the controls go), and More becomes Share.
     */
    private void setMode(int mode) {
        if (mode == mMode) return;
        int from = mMode;
        mMode = mode;
        boolean lyrics = mode != NORMAL;
        if (lyrics != (from != NORMAL)) {
            fade(mArt, !lyrics);
            fade(mPage, lyrics);
            mTitleRow.setVisibility(lyrics ? View.GONE : View.VISIBLE);
            mRoot.findViewById(R.id.meetune_player_format_space).setVisibility(lyrics ? View.GONE : View.INVISIBLE); // the title row's spacer
            mLyricsButton.setBackground(lyrics
                    ? androidx.core.content.ContextCompat.getDrawable(mRoot.getContext(), R.drawable.meetune_lyrics_on)
                    : mLyricsButtonBg);
            if (lyrics) com.newtube.mobile.ui.common.Motion.frostIn(mLyricsScroll);
        }
        mLineRow.setVisibility(lineRowVisibility(mode));
        boolean full = mode == FULL;
        mHeadMore.setImageResource(full ? R.drawable.ic_watch_share : R.drawable.ic_meetune_more);
        mHeadMore.setContentDescription(mRoot.getContext().getString(full ? R.string.meetune_share : R.string.meetune_more));
        mControlsColumn.animate().cancel();
        if (full) {
            mControlsColumn.animate().alpha(0f).translationY(48 * mRoot.getResources().getDisplayMetrics().density)
                    .setDuration(220).setInterpolator(com.newtube.mobile.ui.common.Motion.STANDARD)
                    .withEndAction(() -> mControlsColumn.setVisibility(View.INVISIBLE)).start();
        } else {
            mControlsColumn.setVisibility(View.VISIBLE);
            mControlsColumn.animate().alpha(1f).translationY(0f).setDuration(260)
                    .setInterpolator(com.newtube.mobile.ui.common.Motion.STANDARD).start();
        }
        padPage();
        mSung = -2; // scroll to the sung line in the new room
        mTouchedAt = -HANDS_OFF_MS;
        mRoot.post(() -> followLyrics(mControls.position()));
    }

    /** The lines end above the controls, or at the page's foot when they fill it. */
    private void padPage() {
        int bottom = mMode == FULL ? mControlsColumn.getPaddingBottom()
                : Math.max(0, mRoot.getHeight() - mControlsColumn.getTop());
        if (mPage.getPaddingBottom() != bottom) {
            mPage.post(() -> mPage.setPadding(0, mPage.getPaddingTop(), 0, bottom));
        }
    }

    private static void fade(View view, boolean in) {
        view.animate().cancel();
        if (in) {
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(260).start();
        } else {
            view.animate().alpha(0f).setDuration(200).withEndAction(() -> view.setVisibility(View.INVISIBLE)).start();
        }
    }

    private void share() {
        if (mVideoId == null) return;
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=" + mVideoId);
        mRoot.getContext().startActivity(android.content.Intent.createChooser(send, null));
    }

    /** A button that bounces (pops with its glass) as it acts. */
    /**
     * Play, previous and next press like glass: a frosted bubble wells up under the finger as the
     * icon sinks in, and on release the icon springs back past its size while the bubble swells
     * and melts away.
     */
    private static void glassPress(View button) {
        android.graphics.drawable.GradientDrawable bubble = new android.graphics.drawable.GradientDrawable();
        bubble.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bubble.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        bubble.setColors(new int[] {0x40FFFFFF, 0x1AFFFFFF});
        bubble.setGradientRadius(button.getLayoutParams().width * 0.55f);
        bubble.setStroke(Math.max(1, Math.round(button.getResources().getDisplayMetrics().density)), 0x59FFFFFF);
        bubble.setAlpha(0);
        button.setBackground(bubble);
        android.animation.ValueAnimator[] fade = {null};
        java.util.function.BiConsumer<Integer, Long> fadeTo = (to, ms) -> {
            if (fade[0] != null) fade[0].cancel();
            fade[0] = android.animation.ValueAnimator.ofInt(bubble.getAlpha(), to);
            fade[0].setDuration(ms);
            fade[0].addUpdateListener(a -> bubble.setAlpha((int) a.getAnimatedValue()));
            fade[0].start();
        };
        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(160)
                            .setInterpolator(com.newtube.mobile.ui.common.Motion.STANDARD).start();
                    fadeTo.accept(255, 160L);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(460)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                    fadeTo.accept(0, 520L);
                    break;
                default:
                    break;
            }
            return false; // the click still happens
        });
    }

    private static void bounce(View button, Runnable action) {
        button.setOnClickListener(v -> {
            Haptics.click(v);
            if (!(v.getBackground() instanceof android.graphics.drawable.GradientDrawable)) { // glassPress animates its own
                com.newtube.mobile.ui.common.Motion.pop(v.getParent() instanceof GlassView ? (View) v.getParent() : v);
            }
            action.run();
        });
    }

    // ----------------------------------------------------------------------------- minimize

    private boolean mFlying;
    private final android.graphics.Rect mArtFrom = new android.graphics.Rect();
    private final android.graphics.Rect mTextFrom = new android.graphics.Rect();
    private float mArtCorner;
    private final android.graphics.Rect mTextClip = new android.graphics.Rect();

    /**
     * The minimize drag (or Back) at fraction {@code f}. Into MeeTune's pill, the cover and the
     * song's name shrink and fly onto the pill's own (MeeTune#pillArt says where it docks) while the
     * rest of the page fades off; the pill then shows in that very place. Anywhere else (the lyrics
     * up, no pill to land on) the page slides down after the finger.
     */
    void follow(float f) {
        android.graphics.Rect to = MeeTune.pillArt();
        boolean fly = f > 0f && mMode == NORMAL && to != null && mArt.getWidth() != 0;
        MeeTune.flyToPill(f, mVideo, mControls.playing(), fly);
        if (!fly) {
            land();
            mRoot.setTranslationY(f > 0f ? f * mRoot.getHeight() : 0f);
            return;
        }
        View text = (View) mTitle.getParent();
        if (!mFlying) {
            mFlying = true;
            mRoot.setTranslationY(0f);
            screenRect(mArt, mArtFrom);
            screenRect(text, mTextFrom);
            mTitle.setSelected(false); // the marquee back at the start, as the pill shows it
        }
        float density = mRoot.getResources().getDisplayMetrics().density;
        float clamped = Math.min(1f, f);
        // the cover onto the pill's
        float scale = 1f + ((float) to.width() / mArtFrom.width() - 1f) * f;
        mArt.setPivotX(0f);
        mArt.setPivotY(0f);
        mArt.setScaleX(scale);
        mArt.setScaleY(scale);
        mArt.setTranslationX((to.left - mArtFrom.left) * f);
        mArt.setTranslationY((to.top - mArtFrom.top) * f);
        mArtCorner = 8 * density * clamped / scale;
        mArt.invalidateOutline();
        ((FadeImage) mArt).setFade(1f - clamped); // its melting foot firms up into a tile
        float handoff = MeeTune.handoff(clamped); // the pill's own take over at the end
        mArt.setAlpha(1f - handoff);
        text.setAlpha(1f - handoff);
        // the name beside it, at the pill's text size
        android.graphics.Rect box = MeeTune.pillText();
        float small = 14f / 22f;
        float textScale = 1f + (small - 1f) * f;
        float textY = box.centerY() - mTextFrom.height() * small / 2f;
        text.setPivotX(0f);
        text.setPivotY(0f);
        text.setScaleX(textScale);
        text.setScaleY(textScale);
        text.setTranslationX((box.left - mTextFrom.left) * f);
        text.setTranslationY((textY - mTextFrom.top) * f);
        // cut at the pill's text box: no running on over its play and next
        float wide = mTextFrom.width() + (box.width() / small - mTextFrom.width()) * clamped;
        mTextClip.set(0, 0, Math.max(0, Math.round(wide)), mTextFrom.height());
        text.setClipBounds(mTextClip);
        // everything else goes
        fadeAllBut(f, density);
    }

    private void fadeAllBut(float f, float density) {
        float gone = Math.max(0f, 1f - 2.5f * f);
        View text = (View) mTitle.getParent();
        ViewGroup column = (ViewGroup) mControlsColumn;
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child == mTitleRow) continue;
            child.setAlpha(gone);
            child.setTranslationY(48 * density * f);
        }
        for (int i = 0; i < ((ViewGroup) mTitleRow).getChildCount(); i++) {
            View child = ((ViewGroup) mTitleRow).getChildAt(i);
            if (child != text) child.setAlpha(gone);
        }
        mRoot.findViewById(R.id.meetune_player_handle).setAlpha(gone);
        float ground = Math.max(0f, 1f - 1.5f * f);
        mRoot.getBackground().mutate().setAlpha(Math.round(255 * ground));
        ViewGroup source = mRoot.findViewById(R.id.meetune_player_source);
        for (int i = 0; i < source.getChildCount(); i++) {
            View child = source.getChildAt(i);
            if (child != mArt) child.setAlpha(base(child) * ground);
        }
    }

    /** A view's resting alpha (the blurred art is fainter where Android can't blur it). */
    private float base(View view) {
        return view == mBg && Build.VERSION.SDK_INT < 31 ? 0.35f : 1f;
    }

    /** Everything back where it rests. */
    private void land() {
        if (!mFlying) return;
        mFlying = false;
        mArt.setScaleX(1f);
        mArt.setScaleY(1f);
        mArt.setTranslationX(0f);
        mArt.setTranslationY(0f);
        mArtCorner = 0f;
        mArt.invalidateOutline();
        ((FadeImage) mArt).setFade(1f);
        mArt.setAlpha(1f);
        View text = (View) mTitle.getParent();
        text.setAlpha(1f);
        text.setScaleX(1f);
        text.setScaleY(1f);
        text.setTranslationX(0f);
        text.setTranslationY(0f);
        text.setClipBounds(null);
        mTitle.setSelected(true);
        fadeAllBut(0f, 0f);
    }

    /** Where a view rests on screen, whatever its own scale and translation are just now. */
    private static void screenRect(View view, android.graphics.Rect out) {
        int[] at = new int[2];
        ((View) view.getParent()).getLocationOnScreen(at);
        int x = at[0] + view.getLeft();
        int y = at[1] + view.getTop();
        out.set(x, y, x + view.getWidth(), y + view.getHeight());
    }

    boolean isShown() {
        return mRoot.getVisibility() == View.VISIBLE;
    }

    void show() {
        if (isShown()) return;
        mRoot.setTranslationY(0f);
        mRoot.setVisibility(View.VISIBLE);
        com.newtube.mobile.ui.common.Motion.frostIn(mRoot); // out of blur, as MeeTube's pages come in
        mRoot.removeCallbacks(mTick);
        mRoot.post(mTick);
        if (mCanvasUrl != null) startCanvas(mCanvasUrl);
    }

    void hide() {
        mRoot.removeCallbacks(mTick);
        // Unseen: the clip lets its video decoder go (phones have few; MeeTube's player may need one).
        if (mCanvasPlayer != null && mCanvas.getVisibility() == View.VISIBLE) {
            String clip = mCanvasUrl;
            stopCanvas();
            mCanvasUrl = clip; // back on screen: it plays again
        }
        mWasPlaying = null; // shown again: no title pop for the state it comes back in
        mRoot.setVisibility(View.GONE);
    }

    /** Back from the pill: the page that slid away comes back out of blur (any other resume: nothing). */
    void restore() {
        if (!isShown() || mRoot.getTranslationY() == 0f) return;
        mRoot.setTranslationY(0f);
        com.newtube.mobile.ui.common.Motion.frostIn(mRoot);
    }

    /** The heart is YouTube Music's like (MeeTube's video like never takes for a song). */
    private void like() {
        if (mVideo != null && mRoot.getContext() instanceof android.app.Activity) {
            com.newtube.mobile.ui.meetune.MeeTune.toggleLike((android.app.Activity) mRoot.getContext(), mVideo.videoId, this::update);
        }
    }

    private boolean liked() {
        return mVideo != null && com.newtube.mobile.ui.meetune.MeeTune.liked(mVideo.videoId);
    }

    private void update() {
        long duration = mControls.duration();
        long position = mControls.position();
        if (!mSeeking) {
            mSeek.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
            showTimes(position, duration);
        }
        boolean playing = mControls.playing();
        mPlay.setImageResource(playing ? R.drawable.ic_meetune_pause : R.drawable.ic_meetune_play);
        if (mWasPlaying != null && mWasPlaying != playing) popTitle(playing);
        mWasPlaying = playing;
        if (mCanvasPlayer != null && mCanvasPlayer.getPlayWhenReady() != playing) {
            mCanvasPlayer.setPlayWhenReady(playing); // the moving cover rests while the song does
        }
        int star = liked() ? R.drawable.ic_meetune_heart_filled : R.drawable.ic_meetune_heart;
        mLike.setImageResource(star);
        mHeadLike.setImageResource(star);
        followLyrics(position);
        if (mAudio != null && !mVolume.isPressed()) {
            mVolume.setProgress(mAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        if ((mFormat.length() == 0 || mFormatPending) && !mFormatLeaving) {
            Format format = mControls.audioFormat();
            if (format != null && !(mFormatPending && format == mShownFormat)) { // the same object: still the old song's track
                mFormatPending = false;
                showFormat(format);
                blurPill(false); // from blurred (pending) or empty: either way it blurs in
            }
        }
        ((EqBars) mRoot.findViewById(R.id.meetune_player_eq)).setPlaying(playing);
        if (!mNextArtFetched && position > 3000) { // the next song is known by now (queue or suggestions)
            mNextArtFetched = true;
            fetchNextArt();
        }
        if (position > 3000 && mControls.playing() && mVideo != null && mVideo.localUri == null) {
            androidx.media3.common.Format heard = mControls.audioFormat(); // Library's Cached songs lists it once whole
            com.newtube.mobile.ui.meetune.MeeTuneSongCache.remember(mRoot.getContext(), mVideoId, heard != null ? heard.id : null);
        }
        if (position > 8000 && mControls.playing()) { // the next song, whole, into the disk cache (once per song)
            com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController suggestions =
                    com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(mRoot.getContext())
                            .getController(com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController.class);
            Video next = suggestions != null ? suggestions.getNext() : null;
            androidx.media3.common.Format format = mControls.audioFormat();
            if (next != null && next.videoId != null && !next.videoId.equals(mVideoId)) {
                com.newtube.mobile.ui.meetune.MeeTuneSongCache.fetch(mRoot.getContext(), next.videoId, format != null ? format.id : null);
            }
        }
    }

    /** The next song's cover goes to Glide's disk cache now, so it shows at once when that song starts. */
    private void fetchNextArt() {
        android.content.Context context = mRoot.getContext().getApplicationContext();
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController suggestions =
                com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(context).getController(
                        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController.class);
        Video next = suggestions != null ? suggestions.getNext() : null;
        if (next == null || next.videoId == null) return;
        String art = MeeTune.art(next);
        String id = next.videoId;
        Glide.with(context).downloadOnly().load(art).listener(new com.bumptech.glide.request.RequestListener<java.io.File>() {
            @Override
            public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e, Object model,
                                        @androidx.annotation.NonNull com.bumptech.glide.request.target.Target<java.io.File> target, boolean first) {
                if (art.endsWith("/maxresdefault.jpg")) NO_MAXRES.add(id);
                return false;
            }

            @Override
            public boolean onResourceReady(@androidx.annotation.NonNull java.io.File file, @androidx.annotation.NonNull Object model,
                                           com.bumptech.glide.request.target.Target<java.io.File> target,
                                           @androidx.annotation.NonNull com.bumptech.glide.load.DataSource source, boolean first) {
                return false;
            }
        }).preload();
        // The fallback too: many songs have no maxresdefault, and a failed load isn't cached, so
        // without it every switch to such a song waited for the 404 and then for hqdefault.
        Glide.with(context).downloadOnly().load("https://i.ytimg.com/vi/" + next.videoId + "/hqdefault.jpg").preload();
        if (art.startsWith("https://i.ytimg.com/")) { // the quick small one the cover shows first
            Glide.with(context).downloadOnly().load("https://i.ytimg.com/vi/" + next.videoId + "/mqdefault.jpg").preload();
        }
    }

    private void showTimes(long position, long duration) {
        mElapsed.setText(time(Math.max(0, position)));
        mRemaining.setText(duration > 0 ? "-" + time(Math.max(0, duration - position)) : "");
    }

    private static String time(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The art with its lower 45% fading to transparent (an alpha mask, not a dark overlay). */
    /** The moving cover's frame: its foot fades out like {@link FadeImage}'s, into the blur under the controls. */
    public static final class FadeFrame extends android.widget.FrameLayout {
        private final Paint mMask = new Paint();

        public FadeFrame(Context context, AttributeSet attrs) {
            super(context, attrs);
            mMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (h == 0) return;
            mMask.setShader(new LinearGradient(0, h * 0.55f, 0, h, new int[] {0xFF000000, 0xB3000000, 0x40000000, 0},
                    new float[] {0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.dispatchDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mMask);
            canvas.restoreToCount(save);
        }
    }

    public static final class FadeImage extends ImageView {
        private final Paint mMask = new Paint();

        public FadeImage(Context context, AttributeSet attrs) {
            super(context, attrs);
            mMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        private float mFade = 1f;

        /** 1: the foot fades out; 0: none (the cover landing on the pill as a plain tile). */
        void setFade(float fade) {
            if (fade == mFade) return;
            mFade = fade;
            shade(getWidth(), getHeight());
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            shade(w, h);
        }

        private void shade(int w, int h) {
            if (h == 0) return;
            int[] alphas = {0xFF, 0xB3, 0x40, 0x00};
            int[] colors = new int[4];
            for (int i = 0; i < 4; i++) {
                colors[i] = Math.round(0xFF - (0xFF - alphas[i]) * mFade) << 24;
            }
            mMask.setShader(new LinearGradient(0, h * 0.55f, 0, h, colors,
                    new float[] {0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        }

        private boolean mFeather;
        private final Paint mEdge = new Paint();
        private final android.graphics.RectF mDrawn = new android.graphics.RectF();
        private final android.graphics.RectF mEdged = new android.graphics.RectF();
        private float mEdgedFade = -1f;
        private final Shader[] mEdges = new Shader[4];

        /** The music player's cover: every edge of the picture melts into the ambient, never a hard line. */
        void setFeather(boolean feather) {
            mFeather = feather;
            mEdge.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.onDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mMask);
            if (mFeather && getDrawable() != null && mFade > 0f) feather(canvas);
            canvas.restoreToCount(layer);
        }

        /** Fades the drawn picture (on screen, so a cropped cover's view edges) in from each side. */
        private void feather(Canvas canvas) {
            android.graphics.drawable.Drawable art = getDrawable();
            mDrawn.set(0, 0, art.getIntrinsicWidth(), art.getIntrinsicHeight());
            getImageMatrix().mapRect(mDrawn);
            mDrawn.offset(getPaddingLeft(), getPaddingTop());
            if (!mDrawn.intersect(0, 0, getWidth(), getHeight())) return;
            if (!mDrawn.equals(mEdged) || mEdgedFade != mFade) {
                mEdged.set(mDrawn);
                mEdgedFade = mFade;
                int clear = Math.round(0xFF * (1f - mFade)) << 24;
                int[] in = {clear, 0xFF000000};
                float v = mDrawn.height() * 0.22f;
                float h = mDrawn.width() * 0.14f;
                mEdges[0] = new LinearGradient(0, mDrawn.top, 0, mDrawn.top + v, in, null, Shader.TileMode.CLAMP);
                mEdges[1] = new LinearGradient(0, mDrawn.bottom, 0, mDrawn.bottom - v, in, null, Shader.TileMode.CLAMP);
                mEdges[2] = new LinearGradient(mDrawn.left, 0, mDrawn.left + h, 0, in, null, Shader.TileMode.CLAMP);
                mEdges[3] = new LinearGradient(mDrawn.right, 0, mDrawn.right - h, 0, in, null, Shader.TileMode.CLAMP);
            }
            for (Shader edge : mEdges) {
                mEdge.setShader(edge);
                canvas.drawRect(0, 0, getWidth(), getHeight(), mEdge);
            }
        }
    }

    /**
     * The song's bar, glowing: a soft light along the played part (API 28+, where the blur draws
     * on the GPU) and MeeTube's progress glow at its head. Settings > Customization > Progress glow
     * turns both off, as it does every other bar's.
     */
    public static final class GlowSeek extends SeekBar {
        private final GlowProgressBar.Head mHead = new GlowProgressBar.Head();
        private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mDensity;
        public GlowSeek(Context context, AttributeSet attrs) {
            super(context, attrs);
            mDensity = getResources().getDisplayMetrics().density;
            mFill.setColor(0xF2FFFFFF);
            mHalo.setColor(0x73FFFFFF);
            mHalo.setMaskFilter(new BlurMaskFilter(7 * mDensity, BlurMaskFilter.Blur.NORMAL));
        }

        @Override
        protected synchronized void onDraw(Canvas canvas) {
            int range = getMax();
            int progress = getProgress();
            if (range > 0 && progress > 0 && GlowProgressBar.enabled(getContext())) {
                int left = getPaddingLeft();
                float x = left + (getWidth() - left - getPaddingRight()) * (float) progress / range;
                float cy = getHeight() / 2f;
                float half = 4 * mDensity; // the 8dp line (meetune_seek)
                if (Build.VERSION.SDK_INT >= 28) {
                    canvas.drawRoundRect(left, cy - half, x, cy + half, half, half, mHalo);
                }
                if (progress < range) {
                    mHead.draw(getContext(), canvas, x, cy, 20 * mDensity, 0xFFFFFFFF);
                }
            }
            super.onDraw(canvas);
            if (range > 0 && progress > 0) {
                // The played part as a pill, round from the first second (meetune_seek leaves it clear).
                int left = getPaddingLeft();
                float half = 4 * mDensity;
                float x = left + (getWidth() - left - getPaddingRight()) * (float) progress / range;
                float cy = getHeight() / 2f;
                canvas.drawRoundRect(left, cy - half, Math.max(x, left + 2 * half), cy + half, half, half, mFill);
            }
        }
    }

    /** Back, play and next: a bare icon with the bar's head glow behind it, soft white (off with the bar's glow). */
    public static final class GlowIcon extends ImageView {
        private final GlowProgressBar.Head mHead = new GlowProgressBar.Head();

        public GlowIcon(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int save = canvas.save();
            canvas.scale(1f, 1f / 0.6f, cx, cy); // the head's glow is flattened for a line: round again
            mHead.draw(getContext(), canvas, cx, cy, Math.min(cx, cy), 0x66FFFFFF);
            canvas.restoreToCount(save);
            super.onDraw(canvas);
        }
    }
}
