package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

/**
 * MEETUBE(you page): the card a group of You tiles sits on: a faint glass fill, a soft accent glow
 * in the top right, and a faint hairline that warms to the accent only in the bottom right corner.
 */
public final class AccentCard extends Drawable {
    private final int mAccent;
    private final float mRadius;
    private final float mRim;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBox = new RectF();

    public AccentCard(int accent, float density) {
        mAccent = accent;
        mRadius = 26 * density;
        mRim = 0.8f * density;
        mFill.setColor(0x0AFFFFFF);
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setStrokeWidth(mRim);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        mBox.set(b.left + mRim / 2, b.top + mRim / 2, b.right - mRim / 2, b.bottom - mRim / 2);
        mGlow.setShader(new RadialGradient(b.right, b.top, Math.max(1, b.width() * 0.75f),
                new int[]{ColorUtils.setAlphaComponent(mAccent, 0x22), ColorUtils.setAlphaComponent(mAccent, 0x0A), 0},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        mEdge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0x1AFFFFFF, 0x0FFFFFFF, ColorUtils.setAlphaComponent(mAccent, 0x26), ColorUtils.setAlphaComponent(mAccent, 0x73)},
                new float[]{0f, 0.6f, 0.88f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawRoundRect(mBox, mRadius, mRadius, mFill);
        canvas.drawRoundRect(mBox, mRadius, mRadius, mGlow);
        canvas.drawRoundRect(mBox, mRadius, mRadius, mEdge);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
