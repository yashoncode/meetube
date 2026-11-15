package com.newtube.mobile.ui.meetune;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * MEETUNE: the cover of something YouTube Music gives no art for: soft blurred light in three
 * colours on a deep ground under a glass sheen, picked from its name, so the same album always
 * gets the same cover and two albums rarely share one.
 */
final class MeeTuneCover extends Drawable {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mGround;
    private final int[] mColors = new int[3];
    private final float[] mSpots = new float[9]; // x, y, radius per light, as fractions of the side
    private final RadialGradient[] mLights = new RadialGradient[3];
    private LinearGradient mSheen;

    MeeTuneCover(@Nullable String name) {
        this(name, -1f);
    }

    /** A section card (New releases, a mood): its lights all near its colour. */
    MeeTuneCover(@Nullable String name, int color) {
        this(name, hue(color));
    }

    private static float hue(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    private MeeTuneCover(@Nullable String name, float baseHue) {
        String seed = name != null ? name.trim() : "";
        Random random = new Random(seed.hashCode());
        boolean free = baseHue < 0;
        float hue = free ? random.nextInt(360) : baseHue;
        mGround = Color.HSVToColor(new float[] {hue, 0.7f, free ? 0.22f : 0.3f});
        for (int i = 0; i < 3; i++) {
            float h = (hue + 360 + (free ? i * (40 + random.nextInt(60)) : (i - 1) * (14 + random.nextInt(22)))) % 360;
            mColors[i] = Color.HSVToColor(new float[] {h, 0.55f + 0.3f * random.nextFloat(), 0.8f + 0.2f * random.nextFloat()});
            mSpots[i * 3] = 0.1f + 0.8f * random.nextFloat();
            mSpots[i * 3 + 1] = 0.1f + 0.8f * random.nextFloat();
            mSpots[i * 3 + 2] = 0.55f + 0.35f * random.nextFloat();
        }
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        float side = Math.max(1, Math.max(bounds.width(), bounds.height()));
        for (int i = 0; i < 3; i++) {
            mLights[i] = new RadialGradient(bounds.left + mSpots[i * 3] * bounds.width(),
                    bounds.top + mSpots[i * 3 + 1] * bounds.height(), mSpots[i * 3 + 2] * side,
                    new int[] {mColors[i], mColors[i] & 0x80FFFFFF, mColors[i] & 0x00FFFFFF}, new float[] {0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP);
        }
        // the glass's light from the top left
        mSheen = new LinearGradient(bounds.left, bounds.top, bounds.left + bounds.width() * 0.6f, bounds.bottom,
                0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        mPaint.setShader(null);
        mPaint.setColor(mGround);
        canvas.drawRect(b, mPaint);
        for (RadialGradient light : mLights) {
            if (light == null) continue;
            mPaint.setShader(light);
            canvas.drawRect(b, mPaint);
        }
        if (mSheen != null) {
            mPaint.setShader(mSheen);
            canvas.drawRect(b, mPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        mPaint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
