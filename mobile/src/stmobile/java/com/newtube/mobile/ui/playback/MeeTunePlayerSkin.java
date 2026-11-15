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
        // A square art at the top.
        mArt.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            ViewGroup.LayoutParams params = mArt.getLayoutParams();
            if (params.height != r - l) {
                params.height = r - l;
                mArt.setLayoutParams(params);
            }
        });
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
        bounce(mRoot.findViewById(R.id.meetune_player_previous), mControls::previous);
        bounce(mRoot.findViewById(R.id.meetune_player_next), mControls::next);
        bounce(mLike, () -> {
            mControls.like();
            mLike.postDelayed(this::update, 400);
        });
        bounce(mRoot.findViewById(R.id.meetune_player_more), mControls::more);
        bounce(mLyricsButton, () -> setMode(mMode == NORMAL ? LYRICS : NORMAL));
        bounce(mHeadLike, () -> {
            mControls.like();
            mLike.postDelayed(this::update, 400);
        });
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
        String device = Settings.Global.getString(context.getContentResolver(), "device_name");
        ((TextView) mRoot.findViewById(R.id.meetune_player_device)).setText(
                device != null && !device.isEmpty() ? device : context.getString(R.string.meetune_this_phone));
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

    void bind(@Nullable Video video) {
        if (video == null) return;
        mVideo = video;
        mTitle.setText(video.getTitle());
        mTitle.setSelected(true); // marquee
        mArtist.setText(video.getAuthor());
        ((TextView) mRoot.findViewById(R.id.meetune_player_head_title)).setText(video.getTitle());
        ((TextView) mRoot.findViewById(R.id.meetune_player_head_artist)).setText(video.getAuthor());
        if (video.videoId != null && !video.videoId.equals(mVideoId)) {
            mVideoId = video.videoId;
            Glide.with(mArt).load(MeeTune.art(video)).into((ImageView) mRoot.findViewById(R.id.meetune_player_head_art));
            fetchLyrics(video);
            if (mRoot.getContext() instanceof android.app.Activity) {
                MeeTune.learn((android.app.Activity) mRoot.getContext(), video); // the menu's Open album, Open artist
            }
            mFormat.setText(null);
            String fallback = "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";
            Glide.with(mArt).load(MeeTune.art(video)).error(Glide.with(mArt).load(fallback)).into(mArt);
            Glide.with(mBg).load(MeeTune.art(video)).error(Glide.with(mBg).load(fallback)).into(mBg);
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
            source.setTypeface(android.graphics.Typeface.DEFAULT);
            source.setPadding(0, Math.round(12 * density), 0, 0);
            mLyricLines.addView(source);
        }
        mLineRow.setVisibility(mMode == NORMAL && timed() ? View.VISIBLE : View.GONE);
        followLyrics(mControls.position());
    }

    private TextView lyricLine(CharSequence text, int sp, int color) {
        TextView line = new TextView(mRoot.getContext());
        line.setText(text);
        line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        line.setTextColor(color);
        line.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
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
            mFormat.setVisibility(lyrics ? View.GONE : View.VISIBLE);
            mLyricsButton.setBackground(lyrics
                    ? androidx.core.content.ContextCompat.getDrawable(mRoot.getContext(), R.drawable.meetune_lyrics_on)
                    : mLyricsButtonBg);
            if (lyrics) com.newtube.mobile.ui.common.Motion.frostIn(mLyricsScroll);
        }
        mLineRow.setVisibility(mode == NORMAL && timed() ? View.VISIBLE : View.GONE);
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
    private static void bounce(View button, Runnable action) {
        button.setOnClickListener(v -> {
            Haptics.click(v);
            com.newtube.mobile.ui.common.Motion.pop(v.getParent() instanceof GlassView ? (View) v.getParent() : v);
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
    }

    void hide() {
        mRoot.removeCallbacks(mTick);
        mRoot.setVisibility(View.GONE);
    }

    /** Back from the pill: the page that slid away comes back out of blur (any other resume: nothing). */
    void restore() {
        if (!isShown() || mRoot.getTranslationY() == 0f) return;
        mRoot.setTranslationY(0f);
        com.newtube.mobile.ui.common.Motion.frostIn(mRoot);
    }

    private void update() {
        long duration = mControls.duration();
        long position = mControls.position();
        if (!mSeeking) {
            mSeek.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
            showTimes(position, duration);
        }
        mPlay.setImageResource(mControls.playing() ? R.drawable.ic_meetune_pause : R.drawable.ic_meetune_play);
        int star = mControls.liked() ? R.drawable.ic_meetune_star_filled : R.drawable.ic_meetune_star;
        mLike.setImageResource(star);
        mHeadLike.setImageResource(star);
        followLyrics(position);
        if (mAudio != null && !mVolume.isPressed()) {
            mVolume.setProgress(mAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        if (mFormat.length() == 0) mFormat.setText(format(mControls.audioFormat()));
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

        @Override
        protected void onDraw(Canvas canvas) {
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.onDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mMask);
            canvas.restoreToCount(layer);
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

    /** "Opus · 48.0 kHz · 151 kbps · Stereo", from the playing audio track. */
    @Nullable
    static String format(@Nullable Format format) {
        if (format == null) return null;
        StringBuilder text = new StringBuilder();
        String mime = format.sampleMimeType != null ? format.sampleMimeType : format.codecs;
        if (MimeTypes.AUDIO_OPUS.equals(mime)) {
            text.append("Opus");
        } else if (MimeTypes.AUDIO_AAC.equals(mime) || (mime != null && mime.startsWith("mp4a"))) {
            text.append("AAC");
        } else if (mime != null) {
            text.append(mime.replace("audio/", "").toUpperCase(Locale.US));
        }
        if (format.sampleRate > 0) {
            text.append(" · ").append(String.format(Locale.US, "%.1f kHz", format.sampleRate / 1000f));
        }
        if (format.bitrate > 0) text.append(" · ").append(format.bitrate / 1000).append(" kbps");
        if (format.channelCount == 2) {
            text.append(" · Stereo");
        } else if (format.channelCount > 2) {
            text.append(" · ").append(format.channelCount).append(" ch");
        }
        return text.length() > 0 ? text.toString() : null;
    }
}
