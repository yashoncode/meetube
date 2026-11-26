package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;

/**
 * A cover's foreground: while its song is the one MeeTune plays, a light veil and a small round glass
 * with three thin bars breathing from the middle (eased to rest while paused). It asks the player
 * itself, so a list never has to be rebound when the song changes; for any other song it draws
 * nothing and looks again in a second.
 */
final class MeeTunePlaying extends Drawable {
    // Two slow waves a bar, never through zero: no bounce off the floor, which read as a stutter.
    private static final float[] SPEED = {2.3f, 3.1f, 2.7f};
    private static final float[] PHASE = {0f, 2.1f, 4.2f};
    private static final float REST = 0.35f;

    private final Context mContext;
    private final String mVideoId;
    private final Paint mVeil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float[] mLevel = {REST, REST, REST};
    private long mLast;
    private final Runnable mTick = this::invalidateSelf;

    private MeeTunePlaying(Context context, String videoId) {
        mContext = context.getApplicationContext();
        mVideoId = videoId;
        mVeil.setColor(0x33000000);
        mGlass.setColor(0x26FFFFFF);
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setColor(0x4DFFFFFF);
        mBar.setColor(0xF2FFFFFF);
    }

    /** The playing mark for a song's cover; null (no foreground) for an album, artist or playlist. */
    @Nullable
    static Drawable on(Context context, @Nullable String videoId) {
        return videoId == null ? null : new MeeTunePlaying(context, videoId);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        PlaybackPresenter player = PlaybackPresenter.instance(mContext);
        Video video = player.getVideo();
        // The last song stays the player's video after it closes: no player, no mark (the cover kept it).
        boolean current = video != null && player.getView() != null && mVideoId.equals(video.videoId) && MeeTune.isMusic(video);
        boolean playing = current && player.isPlaying();
        long now = SystemClock.uptimeMillis();
        boolean settling = false;
        float t = now / 1000f;
        float ease = Math.min(1f, (now - mLast) / 120f); // frame-rate free: about 120 ms to catch up
        mLast = now;
        for (int i = 0; i < 3; i++) {
            float target = playing ? 0.55f + 0.25f * (float) Math.sin(t * SPEED[i] + PHASE[i])
                    + 0.2f * (float) Math.sin(t * SPEED[i] * 1.7f + PHASE[i] * 0.6f) : REST;
            mLevel[i] += (target - mLevel[i]) * ease;
            settling |= Math.abs(target - mLevel[i]) > 0.005f;
        }
        unscheduleSelf(mTick);
        scheduleSelf(mTick, now + (playing || settling ? 16 : current ? 300 : 1000));
        if (!current) return;

        Rect b = getBounds();
        canvas.drawRect(b, mVeil);
        float side = Math.min(b.width(), b.height());
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float radius = side * 0.17f;
        mEdge.setStrokeWidth(Math.max(1f, side * 0.006f));
        canvas.drawCircle(cx, cy, radius, mGlass);
        canvas.drawCircle(cx, cy, radius, mEdge);

        float bar = side * 0.035f, gap = bar * 1.1f, tall = radius * 1.05f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * (bar + gap);
            float h = Math.max(bar, tall * mLevel[i]);
            mRect.set(x - bar / 2, cy - h / 2, x + bar / 2, cy + h / 2);
            canvas.drawRoundRect(mRect, bar / 2, bar / 2, mBar);
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { }
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
