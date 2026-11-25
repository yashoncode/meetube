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
 * A cover's foreground: while its song is the one MeeTune plays, a dim veil and a glass pill with
 * four bouncing bars (still while paused). It asks the player itself, so a list never has to be
 * rebound when the song changes; for any other song it draws nothing and looks again in a second.
 */
final class MeeTunePlaying extends Drawable {
    private static final float[] SPEED = {7.1f, 9.3f, 6.2f, 8.4f};
    private static final float[] PHASE = {0f, 1.7f, 0.9f, 2.6f};

    private final Context mContext;
    private final String mVideoId;
    private final Paint mVeil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Runnable mTick = this::invalidateSelf;

    private MeeTunePlaying(Context context, String videoId) {
        mContext = context.getApplicationContext();
        mVideoId = videoId;
        mVeil.setColor(0x59000000);
        mGlass.setColor(0x2EFFFFFF);
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setColor(0x66FFFFFF);
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
        unscheduleSelf(mTick);
        scheduleSelf(mTick, SystemClock.uptimeMillis() + (playing ? 33 : current ? 300 : 1000));
        if (!current) return;

        Rect b = getBounds();
        canvas.drawRect(b, mVeil);
        float side = Math.min(b.width(), b.height());
        float bar = side * 0.065f, gap = bar * 0.8f, tall = side * 0.3f;
        float width = 4 * bar + 3 * gap;
        float pad = bar * 1.6f;
        mRect.set(b.exactCenterX() - width / 2 - pad, b.exactCenterY() - tall / 2 - pad,
                b.exactCenterX() + width / 2 + pad, b.exactCenterY() + tall / 2 + pad);
        float round = pad + bar;
        mEdge.setStrokeWidth(Math.max(1f, side * 0.008f));
        canvas.drawRoundRect(mRect, round, round, mGlass);
        canvas.drawRoundRect(mRect, round, round, mEdge);

        float t = SystemClock.uptimeMillis() / 1000f;
        float bottom = b.exactCenterY() + tall / 2;
        for (int i = 0; i < 4; i++) {
            float level = playing ? 0.25f + 0.75f * Math.abs((float) Math.sin(t * SPEED[i] / 2 + PHASE[i])) : 0.3f;
            float left = b.exactCenterX() - width / 2 + i * (bar + gap);
            mRect.set(left, bottom - tall * level, left + bar, bottom);
            canvas.drawRoundRect(mRect, bar / 2, bar / 2, mBar);
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { }
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
