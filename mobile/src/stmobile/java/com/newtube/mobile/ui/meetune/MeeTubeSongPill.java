package com.newtube.mobile.ui.meetune;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.GlassDrawable;
import com.newtube.mobile.ui.common.GlassSource;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.ThemeMode;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;

import java.util.Objects;

/**
 * MEETUNE: a song that is playing when the user switches to MeeTube plays on there, as a small
 * frosted pill (play/pause inside the song's progress ring, the round cover) floating over every
 * MeeTube screen, dragged anywhere. Dragged toward an edge, a close mark lights up there; let go
 * past it, or fling the pill off the screen, and the song pauses and waits in MeeTune as before.
 * A paused song never comes over. A MeeTube video opened takes the player (the pill goes and the song
 * stays parked, paused, in MeeTune), and a feed preview turned up pauses it ({@link #quiet}).
 * Shown from {@link MiniPlayerBridge#claim}, which every MeeTube screen calls on resume.
 */
public final class MeeTubeSongPill {
    /** The pill is up: it stays over the next MeeTube screen even after its pause. */
    private static boolean sOn;
    /** Where the user left it, kept across screens; NaN until first placed. */
    private static float sX = Float.NaN;
    private static float sY;
    @Nullable
    private static Overlay sOverlay;

    private MeeTubeSongPill() {
    }

    /** A MeeTube screen came to the front with a song docked: true when the pill shows it (hide the card). */
    public static boolean show() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        Video video = MiniPlayerBridge.getVideo();
        Object host = MiniPlayerBridge.getMiniHost();
        if (player == null || video == null || !MeeTune.isMusic(video) || !(host instanceof Activity)
                || host instanceof MeeTuneActivity || !(sOn || player.getPlayWhenReady())) {
            hide();
            return false;
        }
        sOn = true;
        Activity activity = (Activity) host;
        if (sOverlay == null || sOverlay.mActivity != activity || sOverlay.getParent() == null) {
            if (sOverlay != null) sOverlay.detach();
            sOverlay = new Overlay(activity, player);
            ((ViewGroup) activity.findViewById(android.R.id.content)).addView(sOverlay,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        sOverlay.refresh();
        return true;
    }

    public static void hide() {
        sOn = false;
        if (sOverlay != null) sOverlay.detach();
        sOverlay = null;
    }

    /** MeeTube starts a sound of its own (a feed preview turned up): the song pauses, the pill stays to resume it. */
    public static void quiet() {
        Video video = MiniPlayerBridge.getVideo();
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (sOn && player != null && video != null && MeeTune.isMusic(video) && player.getPlayWhenReady()) {
            player.pause();
        }
    }

    private static final class Overlay extends FrameLayout {
        private static final int LEFT = 0, TOP = 1, RIGHT = 2, BOTTOM = 3;
        final Activity mActivity;
        private final ExoPlayer mPlayer;
        private final float mDp;
        private final Pill mPill;
        private final ImageView mPlay;
        private final ImageView mArt;
        private final MeeTuneMiniPill.ProgressRing mRing;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Nullable
        private String mArtUrl;
        @Nullable
        private android.graphics.drawable.Drawable mRingArt;
        private Boolean mShownPlaying;
        /** The edge the pill is heading for while dragged, -1 none; how near (0..1); past it (let go closes). */
        private int mEdge = -1;
        private float mNear;
        private boolean mArmed;
        private final Player.Listener mListener = new Player.Listener() {
            @Override
            public void onEvents(Player player, Player.Events events) {
                refresh();
            }
        };
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                long duration = mPlayer.getDuration();
                mRing.setLevel(duration > 0 ? mPlayer.getCurrentPosition() / (float) duration : 0f, mPlayer.isPlaying());
                postDelayed(this, 250);
            }
        };

        Overlay(Activity activity, ExoPlayer player) {
            super(activity);
            mActivity = activity;
            mPlayer = player;
            mDp = activity.getResources().getDisplayMetrics().density;
            setWillNotDraw(false);
            setClipChildren(false);

            mPill = new Pill();
            mPill.setOrientation(LinearLayout.HORIZONTAL);
            mPill.setGravity(Gravity.CENTER_VERTICAL);
            mPill.setPadding(dp(4), dp(4), dp(6), dp(4));
            mPill.setElevation(dp(8));
            mPill.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                }
            });
            GlassDrawable.applyPage(mPill, findSource(activity.findViewById(android.R.id.content)), 0);
            mPill.setClickable(true);
            mPill.setOnClickListener(v -> { // back to MeeTune, where the song plays on
                Haptics.click(v);
                MeeTune.open(mActivity);
            });

            mRing = new MeeTuneMiniPill.ProgressRing((int) mDp, GlassDrawable.pageInk(activity));
            mPlay = new ImageView(activity);
            mPlay.setBackground(mRing);
            mPlay.setPadding(dp(13), dp(13), dp(13), dp(13));
            mPlay.setColorFilter(GlassDrawable.pageInk(activity));
            mPlay.setContentDescription(activity.getString(R.string.meetune_play_pause));
            mPlay.setOnClickListener(v -> {
                Haptics.click(v);
                mRing.pulse(!mPlayer.getPlayWhenReady());
                mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
            });
            mPill.addView(mPlay, new LinearLayout.LayoutParams(dp(48), dp(48)));

            mArt = new ImageView(activity);
            mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mArt.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            mArt.setClipToOutline(true);
            mArt.setBackgroundColor(ThemeMode.isLight(activity) ? 0x14000000 : 0x1FFFFFFF);
            LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(dp(44), dp(44));
            art.setMarginStart(dp(16));
            mPill.addView(mArt, art);

            addView(mPill, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            mPill.setVisibility(INVISIBLE); // until placed (onLayout)
            mPlayer.addListener(mListener);
            post(mTick);
        }

        void detach() {
            mPlayer.removeListener(mListener);
            removeCallbacks(mTick);
            if (getParent() != null) ((ViewGroup) getParent()).removeView(this);
        }

        @Override
        protected void onDetachedFromWindow() { // the screen went away with the pill on it
            mPlayer.removeListener(mListener);
            removeCallbacks(mTick);
            if (sOverlay == this) sOverlay = null;
            super.onDetachedFromWindow();
        }

        void refresh() {
            Video video = MiniPlayerBridge.getVideo();
            if (video == null || !MeeTune.isMusic(video)) { // a MeeTube video took the player
                if (sOverlay == this) hide();
                return;
            }
            boolean playing = mPlayer.getPlayWhenReady();
            if (mShownPlaying == null || mShownPlaying != playing) {
                mPlay.setImageResource(playing ? R.drawable.ic_meetune_pause : R.drawable.ic_meetune_play);
                mShownPlaying = playing;
            }
            mPill.setContentDescription(video.title);
            String url = MeeTune.art(video);
            if (!Objects.equals(url, mArtUrl)) {
                mArtUrl = url;
                Glide.with(mArt).load(url).into(mArt);
            }
            android.graphics.drawable.Drawable cover = mArt.getDrawable();
            if (cover != null && cover != mRingArt && cover.getIntrinsicWidth() > 0) { // the ring glows in the cover's colour
                mRingArt = cover;
                int accent = com.newtube.mobile.ui.playback.MeeTunePlayerSkin.accent(cover);
                mRing.setColor(accent == 0xFFF2F2F2 ? 0 : accent);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (mPill.getWidth() == 0 || getWidth() == 0 || mPill.isPressed() || mPill.mDragging) return;
            if (Float.isNaN(sX)) { // first time: bottom right, above MeeTube's bar and its mini card
                sX = getWidth() - mPill.getWidth() - dp(16);
                sY = getHeight() - mPill.getHeight() - insets().bottom - dp(140);
            }
            float[] at = clamp(sX, sY);
            mPill.setTranslationX(at[0]);
            mPill.setTranslationY(at[1]);
            if (mPill.getVisibility() != VISIBLE) {
                mPill.setVisibility(VISIBLE);
                mPill.setAlpha(0f);
                mPill.setScaleX(0.8f);
                mPill.setScaleY(0.8f);
                mPill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                        .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
            }
        }

        private androidx.core.graphics.Insets insets() {
            android.view.WindowInsets raw = getRootWindowInsets();
            return raw == null ? androidx.core.graphics.Insets.NONE
                    : WindowInsetsCompat.toWindowInsetsCompat(raw).getInsets(WindowInsetsCompat.Type.systemBars());
        }

        /** Inside the screen, off the system bars, 8dp from every edge. */
        private float[] clamp(float x, float y) {
            androidx.core.graphics.Insets in = insets();
            float m = dp(8);
            x = Math.max(in.left + m, Math.min(x, getWidth() - in.right - m - mPill.getWidth()));
            y = Math.max(in.top + m, Math.min(y, getHeight() - in.bottom - m - mPill.getHeight()));
            return new float[] {x, y};
        }

        /** How far the pill at (x, y) is inside each edge, negative past it, as {left, top, right, bottom}. */
        private float[] gaps(float x, float y) {
            return new float[] {x, y, getWidth() - x - mPill.getWidth(), getHeight() - y - mPill.getHeight()};
        }

        /** The edge whose close mark the pill at (x, y) lights, and whether letting go there closes. */
        private void aim(float x, float y) {
            float[] gaps = gaps(x, y);
            int edge = 0;
            for (int i = 1; i < 4; i++) if (gaps[i] < gaps[edge]) edge = i;
            float reach = dp(64);
            boolean armed = past(edge, gaps[edge]);
            if (armed != mArmed) Haptics.threshold(mPill, armed);
            mArmed = armed;
            mEdge = gaps[edge] < reach ? edge : -1;
            mNear = mEdge < 0 ? 0f : Math.min(1f, 1f - gaps[edge] / reach);
            mPill.setAlpha(armed ? 0.6f : 1f);
            invalidate();
        }

        /** A third of the pill past the edge. */
        private boolean past(int edge, float gap) {
            float size = edge == LEFT || edge == RIGHT ? mPill.getWidth() : mPill.getHeight();
            return gap < -size / 3f;
        }

        private void release(float vx, float vy) {
            float x = mPill.getTranslationX(), y = mPill.getTranslationY();
            float fast = 1800 * mDp;
            boolean flung = Math.hypot(vx, vy) > fast;
            float[] gaps = gaps(x + (flung ? vx * 0.2f : 0f), y + (flung ? vy * 0.2f : 0f));
            int edge = 0;
            for (int i = 1; i < 4; i++) if (gaps[i] < gaps[edge]) edge = i;
            boolean close = mArmed || past(edge, gaps[edge]);
            mEdge = -1;
            mNear = 0f;
            mArmed = false;
            invalidate();
            if (close) {
                Haptics.click(mPill);
                float w = mPill.getWidth(), h = mPill.getHeight();
                float tx = edge == LEFT ? -w * 1.3f : edge == RIGHT ? getWidth() + w * 0.3f : x;
                float ty = edge == TOP ? -h * 1.3f : edge == BOTTOM ? getHeight() + h * 0.3f : y;
                mPill.animate().translationX(tx).translationY(ty).alpha(0f).setDuration(Motion.EXIT_MS)
                        .setInterpolator(Motion.EMPHASIZED_ACCELERATE).withEndAction(() -> {
                            mPlayer.pause(); // the song waits in MeeTune, paused where it was
                            if (sOverlay == this) hide();
                            // the MeeTube video it replaced comes back to its card now, paused (claim)
                            MiniPlayerBridge.MiniHost host = MiniPlayerBridge.getMiniHost();
                            if (host == mActivity) host.syncMiniCard();
                        }).start();
                return;
            }
            float[] at = clamp(x, y);
            sX = at[0];
            sY = at[1];
            mPill.animate().translationX(at[0]).translationY(at[1]).alpha(1f).setDuration(Motion.ENTER_MS)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
        }

        @Override
        protected void onDraw(Canvas canvas) { // the close mark at the edge the pill heads for
            if (mEdge < 0 || mNear <= 0f) return;
            boolean light = ThemeMode.isLight(getContext());
            float band = dp(120);
            float w = getWidth(), h = getHeight();
            float cx = mPill.getTranslationX() + mPill.getWidth() / 2f;
            float cy = mPill.getTranslationY() + mPill.getHeight() / 2f;
            androidx.core.graphics.Insets in = insets();
            float inset = dp(44);
            float px, py, ex, ey; // the mark, and the band's far end from the edge
            switch (mEdge) {
                case LEFT: px = in.left + inset; py = cy; ex = band; ey = 0; break;
                case RIGHT: px = w - in.right - inset; py = cy; ex = w - band; ey = 0; break;
                case TOP: px = cx; py = in.top + inset; ex = 0; ey = band; break;
                default: px = cx; py = h - in.bottom - inset; ex = 0; ey = h - band; break;
            }
            px = Math.max(in.left + inset, Math.min(px, w - in.right - inset));
            py = Math.max(in.top + inset, Math.min(py, h - in.bottom - inset));
            int scrim = Math.round((mArmed ? 0x8C : 0x59) * mNear) << 24;
            boolean side = mEdge == LEFT || mEdge == RIGHT;
            float sx = mEdge == RIGHT ? w : 0f, sy = mEdge == BOTTOM ? h : 0f;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setShader(new android.graphics.LinearGradient(side ? sx : 0, side ? 0 : sy, side ? ex : 0, side ? 0 : ey,
                    scrim, 0, android.graphics.Shader.TileMode.CLAMP));
            if (side) canvas.drawRect(Math.min(sx, ex), 0, Math.max(sx, ex), h, mPaint);
            else canvas.drawRect(0, Math.min(sy, ey), w, Math.max(sy, ey), mPaint);
            mPaint.setShader(null);

            float r = dp(mArmed ? 30 : 24) * (0.6f + 0.4f * mNear);
            int alpha = Math.round(255 * mNear);
            mPaint.setColor(mArmed ? 0xFFFF3B30 : light ? 0xFFFFFFFF : 0xFF2C2C2E);
            mPaint.setAlpha(alpha);
            canvas.drawCircle(px, py, r, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(1));
            mPaint.setColor(light ? 0x26000000 : 0x33FFFFFF);
            mPaint.setAlpha(Math.round(mPaint.getAlpha() * mNear));
            if (!mArmed) canvas.drawCircle(px, py, r, mPaint);
            mPaint.setStrokeWidth(dp(2.5f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setColor(mArmed || !light ? 0xFFFFFFFF : 0xFF1C1C1E);
            mPaint.setAlpha(alpha);
            float x = r * 0.36f;
            canvas.drawLine(px - x, py - x, px + x, py + x, mPaint);
            canvas.drawLine(px - x, py + x, px + x, py - x, mPaint);
        }

        private int dp(float v) {
            return Math.round(v * mDp);
        }

        @Nullable
        private static GlassSource findSource(View view) {
            if (view instanceof GlassSource) return (GlassSource) view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    GlassSource found = findSource(group.getChildAt(i));
                    if (found != null) return found;
                }
            }
            return null;
        }

        /** The pill: a tap opens MeeTune, the play button plays/pauses, a drag from anywhere on it moves it. */
        private final class Pill extends LinearLayout {
            boolean mDragging;
            private float mDownX, mDownY, mStartX, mStartY;
            @Nullable
            private VelocityTracker mVelocity;
            private final int mSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

            Pill() {
                super(Overlay.this.getContext());
            }

            private void down(MotionEvent e) {
                mDownX = e.getRawX();
                mDownY = e.getRawY();
                mStartX = getTranslationX();
                mStartY = getTranslationY();
                mDragging = false;
                animate().cancel();
                setAlpha(1f);
                if (mVelocity != null) mVelocity.recycle();
                mVelocity = VelocityTracker.obtain();
            }

            /** Past the slop the finger drags the pill; true once it does. */
            private boolean move(MotionEvent e) {
                float dx = e.getRawX() - mDownX, dy = e.getRawY() - mDownY;
                if (!mDragging && Math.hypot(dx, dy) < mSlop) return false;
                if (!mDragging) {
                    mDragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setPressed(false);
                }
                setTranslationX(mStartX + dx);
                setTranslationY(mStartY + dy);
                aim(getTranslationX(), getTranslationY());
                return true;
            }

            private void track(MotionEvent e) {
                if (mVelocity == null) return;
                MotionEvent raw = MotionEvent.obtain(e); // raw: the pill itself moves under the finger
                raw.setLocation(e.getRawX(), e.getRawY());
                mVelocity.addMovement(raw);
                raw.recycle();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down(e);
                        track(e);
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        track(e);
                        return move(e); // the play button's press is cancelled
                    default:
                        return false;
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down(e);
                        track(e);
                        return super.onTouchEvent(e);
                    case MotionEvent.ACTION_MOVE:
                        track(e);
                        if (move(e)) return true;
                        return super.onTouchEvent(e);
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!mDragging) return super.onTouchEvent(e);
                        track(e);
                        float vx = 0f, vy = 0f;
                        if (mVelocity != null) {
                            mVelocity.computeCurrentVelocity(1000);
                            vx = mVelocity.getXVelocity();
                            vy = mVelocity.getYVelocity();
                            mVelocity.recycle();
                            mVelocity = null;
                        }
                        mDragging = false;
                        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) mArmed = false; // only a lift closes
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.onTouchEvent(cancel); // no click when the finger lets go
                        cancel.recycle();
                        release(e.getActionMasked() == MotionEvent.ACTION_UP ? vx : 0f,
                                e.getActionMasked() == MotionEvent.ACTION_UP ? vy : 0f);
                        return true;
                    default:
                        return super.onTouchEvent(e);
                }
            }
        }
    }
}
