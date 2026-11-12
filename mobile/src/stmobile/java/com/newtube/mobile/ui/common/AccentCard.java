package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

/**
 * MEETUBE(you page, settings cards): the card a group of You tiles or Settings rows sits on, in the
 * style Settings > Customization > Card style picks (plain Minimal by default). Every style is faint glass
 * under a hairline; they differ in where the accent lights it.
 */
public final class AccentCard extends Drawable {
    /** "MeeTube minimal": a soft accent glow top right, the hairline warming to the accent bottom right. */
    public static final int MINIMAL = 0;
    /** Near black, one accent light pooled in the bottom right, the rim lit there only. */
    public static final int CORNER_EMBER = 1;
    /** Milky glass over a blurred accent light, a sheen along the top. */
    public static final int FROSTED_GLASS = 2;
    /** A faint accent wash from the left, a plain hairline. */
    public static final int ACCENT_MIST = 3;
    /** Almost no fill; the accent only along the top edge, a little light under it. */
    public static final int LIT_RIM = 4;
    /** An accent glow from the bottom left and a cool white highlight top right. */
    public static final int DUAL_LIGHT = 5;
    /** "Minimal" (the default): faint glass on the black under a glass hairline lit at the corners, no accent. */
    public static final int PLAIN = 6;
    /** The You page's own card, whatever Card style says: dark frosted glass faintly toned by the accent. */
    public static final int YOU_GLASS = 7;

    private static final String KEY = "card_style";
    private static int sStyle = -1;

    private final int mStyle;
    private final boolean mLightTheme;
    private final int mAccent;
    private final int mAccentEnd;
    private final float mDp;
    private final float mRadius;
    private final float mRim;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLight2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBox = new RectF();

    public static int style(Context context) {
        if (sStyle < 0) {
            sStyle = context.getSharedPreferences("meetube_app", Context.MODE_PRIVATE).getInt(KEY, PLAIN);
        }
        return sStyle;
    }

    public static void setStyle(Context context, int style) {
        sStyle = style;
        context.getSharedPreferences("meetube_app", Context.MODE_PRIVATE).edit().putInt(KEY, style).apply();
    }

    public AccentCard(Context context) {
        this(context, style(context));
    }

    public AccentCard(Context context, int style) {
        mStyle = style;
        mLightTheme = ThemeMode.isLight(context);
        int index = Accent.index(context);
        mAccent = Accent.START[index];
        mAccentEnd = Accent.END[index] != 0 ? Accent.END[index] : mAccent;
        mDp = context.getResources().getDisplayMetrics().density;
        mRadius = 26 * mDp;
        mRim = mStyle == MINIMAL ? 0.8f * mDp : mDp;
        mFill.setColor(new int[]{ink(0x0A), 0, ink(0x0F), 0, ink(0x05), ink(0x09), ink(0x0C), 0}[mStyle]);
        for (Paint edge : new Paint[]{mEdge, mEdge2}) {
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(mRim);
        }
        mEdge.setColor(new int[]{0, 0, ink(0x1F), ink(0x12), 0, ink(0x17), 0, 0}[mStyle]);
        mLine.setStrokeWidth(mRim);
        // A Paint starts opaque black: a layer a style leaves unset must draw nothing.
        mLight.setColor(0);
        mLight2.setColor(0);
        mEdge2.setColor(0);
    }

    /** The glass's neutral tint at {@code alpha}: white on the dark theme, black on the light one. */
    private int ink(int alpha) {
        return (alpha << 24) | (mLightTheme ? 0 : 0xFFFFFF);
    }

    /** The accent ({@code end}: its second colour) at {@code alpha}. */
    private int accent(int alpha, boolean end) {
        return ColorUtils.setAlphaComponent(end ? mAccentEnd : mAccent, alpha);
    }

    /** A blurred oval light: half-axes {@code rx} by {@code ry} (the blur included), centred at (cx, cy). */
    private static RadialGradient light(float cx, float cy, float rx, float ry, int heart, int middle, float at) {
        float r = Math.max(1, rx);
        RadialGradient light = new RadialGradient(0, 0, r, new int[]{heart, middle, 0},
                new float[]{0f, at, 1f}, Shader.TileMode.CLAMP);
        Matrix oval = new Matrix();
        oval.setScale(1f, Math.max(1, ry) / r);
        oval.postTranslate(cx, cy);
        light.setLocalMatrix(oval);
        return light;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        mBox.set(b.left + mRim / 2, b.top + mRim / 2, b.right - mRim / 2, b.bottom - mRim / 2);
        float w = b.width();
        float dp = mDp;
        switch (mStyle) {
            case PLAIN: // a glass hairline, brightest where light catches the corners
                mEdge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        new int[]{ink(0x2E), ink(0x0A), ink(0x0A), ink(0x1F)},
                        new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                break;
            case YOU_GLASS: // black glass toned by the accent: a frost fading down, a hairline lit at the top
                mFill.setShader(new LinearGradient(0, b.top, 0, b.bottom, accent(0x0C, false), accent(0x03, true), Shader.TileMode.CLAMP));
                mEdge.setShader(new LinearGradient(0, b.top, 0, b.bottom, accent(0x30, false), accent(0x0D, true), Shader.TileMode.CLAMP));
                break;
            case MINIMAL:
                mLight.setShader(light(b.right, b.top, w * 0.75f, w * 0.75f, accent(0x22, false), accent(0x0A, false), 0.5f));
                mEdge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        new int[]{ink(0x1A), ink(0x0F), accent(0x26, false), accent(0x73, false)},
                        new float[]{0f, 0.6f, 0.88f, 1f}, Shader.TileMode.CLAMP));
                break;
            case CORNER_EMBER:
                mFill.setShader(new LinearGradient(0, b.top, 0, b.bottom, ink(0x09), ink(0x04), Shader.TileMode.CLAMP));
                mLight.setShader(light(b.right - 70 * dp, b.bottom - 30 * dp, 190 * dp, 160 * dp,
                        accent(0x38, false), accent(0x14, false), 0.4f));
                mEdge.setShader(new LinearGradient(0, b.top, 0, b.bottom, ink(0x17), ink(0x0D), Shader.TileMode.CLAMP));
                mEdge2.setShader(light(b.right, b.bottom, w * 0.54f, b.height() * 0.4f,
                        accent(0xD9, false), accent(0x40, false), 0.5f));
                break;
            case FROSTED_GLASS:
                mLight.setShader(light(b.left + w * 0.65f, b.top + 50 * dp, w * 0.35f + 48 * dp, 138 * dp,
                        accent(0x47, false), accent(0x24, true), 0.45f));
                mLight2.setShader(new LinearGradient(0, b.top, 0, b.top + Math.max(1, Math.min(b.height() * 0.38f, 90 * dp)),
                        ink(0x1A), 0, Shader.TileMode.CLAMP));
                break;
            case ACCENT_MIST:
                mFill.setShader(new LinearGradient(b.left, b.top, b.right, b.top + w * 0.36f,
                        new int[]{accent(0x1C, false), accent(0x08, false), ink(0x06)},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                break;
            case LIT_RIM:
                mLight.setShader(light(b.exactCenterX(), b.top, w * 0.35f + 30 * dp, 60 * dp,
                        accent(0x2E, false), accent(0x10, false), 0.4f));
                mEdge.setShader(new LinearGradient(0, b.top, 0, b.bottom, ink(0x14), ink(0x0A), Shader.TileMode.CLAMP));
                // the line: clear at 8% and 92% of the width, the accent at 90% in the middle
                mLine.setShader(new LinearGradient(b.left, 0, b.right, 0, new int[]{0, 0, accent(0xE6, false), 0, 0},
                        new float[]{0f, 0.08f, 0.5f, 0.92f, 1f}, Shader.TileMode.CLAMP));
                break;
            default: // DUAL_LIGHT
                mLight.setShader(light(b.left + 80 * dp, b.bottom - 20 * dp, 185 * dp, 155 * dp,
                        accent(0x38, true), accent(0x1C, false), 0.45f));
                mLight2.setShader(light(b.right - 60 * dp, b.top + 10 * dp, 140 * dp, 110 * dp, ink(0x0F), ink(0x07), 0.45f));
                break;
        }
        // A paint's alpha dims its shader too: a layer drawn by a gradient must be opaque.
        for (Paint paint : new Paint[]{mFill, mLight, mLight2, mEdge, mEdge2}) {
            if (paint.getShader() != null) {
                paint.setAlpha(0xFF);
            }
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        drawIfPainted(canvas, mFill);
        drawIfPainted(canvas, mLight);
        drawIfPainted(canvas, mLight2);
        drawIfPainted(canvas, mEdge);
        drawIfPainted(canvas, mEdge2);
        if (mStyle == LIT_RIM) {
            canvas.drawLine(mBox.left + mRadius, mBox.top, mBox.right - mRadius, mBox.top, mLine);
        }
    }

    private void drawIfPainted(Canvas canvas, Paint paint) {
        if (paint.getShader() != null || paint.getAlpha() != 0) {
            canvas.drawRoundRect(mBox, mRadius, mRadius, paint);
        }
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
