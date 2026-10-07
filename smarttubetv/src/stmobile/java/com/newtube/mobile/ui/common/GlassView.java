package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Random;

/**
 * METUBE(glass): iOS-style frosted / liquid glass over a {@link GlassSource}, the same recipe as
 * BitChord's floating bar (Haze + Kyant's backdrop lens), done with plain RenderNodes:
 *
 * <ol>
 *   <li>API 31+: the source's live RenderNode, saturated 1.5x and blurred (RenderEffect);</li>
 *   <li>API 33+: an AGSL lens that bends the rim inward with slight chromatic aberration;</li>
 *   <li>a dark tint (40%), fine grain, and a specular rim: bright at the top, faint at the bottom.</li>
 * </ol>
 *
 * Below API 31 there is nothing to sample, so the glass falls back to a near-opaque tint.
 * Children draw on top, unblurred. Wire it with {@link #setSource}.
 */
public class GlassView extends FrameLayout {
    private static final float BLUR_DP = 22f;
    private static final float SATURATION = 1.5f;
    private static final float LENS_BAND_DP = 20f;
    private static final float LENS_SHIFT_DP = 10f;
    private static final int NOISE_ALPHA = 14; // ~5.5%, Haze's default grain
    private static final int FALLBACK_ALPHA = 0xEB;

    // Signed distance to the rounded rect; within `band` of the rim the sample point is pulled
    // inward along the normal (glass thickening at its edge), red and blue a little apart.
    private static final String LENS_AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float shift;\n"
            + "float sdf(float2 p) {\n"
            + "  float2 c = size * 0.5;\n"
            + "  float2 q = abs(p - c) - c + radius;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  float t = clamp(1.0 + sdf(p) / band, 0.0, 1.0);\n"
            + "  t = t * t;\n"
            + "  float2 e = float2(1.0, 0.0);\n"
            + "  float2 n = float2(sdf(p + e.xy) - sdf(p - e.xy), sdf(p + e.yx) - sdf(p - e.yx));\n"
            + "  n = n / max(length(n), 0.0001);\n"
            + "  float2 off = -n * t * shift;\n"
            + "  half4 g = content.eval(p + off);\n"
            + "  half r = content.eval(p + off * 1.15).r;\n"
            + "  half b = content.eval(p + off * 0.85).b;\n"
            + "  return half4(r, g.g, b, g.a);\n"
            + "}\n";

    private static Bitmap sNoise;

    private final float mCorner;
    private final int mTint;
    private final float mDensity;
    private final Paint mTintPaint = new Paint();
    private final Paint mNoisePaint = new Paint();
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRimRect = new RectF();
    private final int[] mLoc = new int[2];
    private final int[] mSourceLoc = new int[2];
    private int mRecordedX = Integer.MIN_VALUE;
    private int mRecordedY = Integer.MIN_VALUE;
    private int mEffectW;
    private int mEffectH;

    @Nullable private GlassSource mSource;
    @Nullable private RenderNode mNode;

    private final ViewTreeObserver.OnPreDrawListener mTrackPosition = () -> {
        if (mSource != null && hasMoved()) {
            invalidate(); // re-record at the new offset; content changes need no help
        }
        return true;
    };

    public GlassView(@NonNull Context context) {
        this(context, null);
    }

    public GlassView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassView);
        mCorner = a.getDimension(R.styleable.GlassView_glassCornerRadius, 24 * mDensity);
        mTint = a.getColor(R.styleable.GlassView_glassTint, 0x66121212);
        a.recycle();

        setWillNotDraw(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCorner);
            }
        });
        setClipToOutline(true);

        mNoisePaint.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoisePaint.setAlpha(NOISE_ALPHA);
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(mDensity);
    }

    /** The page this glass frosts; it must not contain this view. */
    public void setSource(@Nullable GlassSource source) {
        mSource = source;
        if (source != null && source.node != null && mNode == null) {
            mNode = new RenderNode("MeTube glass");
        }
        mRecordedX = Integer.MIN_VALUE;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(mTrackPosition);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mTrackPosition);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = mRimPaint.getStrokeWidth() / 2f;
        mRimRect.set(inset, inset, w - inset, h - inset);
        // Specular rim: light from above, a faint bounce at the bottom (BitChord's Highlight.Default).
        mRimPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0x66FFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x2EFFFFFF},
                new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        boolean live = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mNode != null && mSource != null && mSource.node != null
                && canvas.isHardwareAccelerated() && getWidth() > 0 && getHeight() > 0;
        if (live) {
            drawBackdrop(canvas);
            mTintPaint.setColor(mTint);
        } else {
            mTintPaint.setColor((mTint & 0x00FFFFFF) | (FALLBACK_ALPHA << 24));
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), mTintPaint);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mNoisePaint);
        canvas.drawRoundRect(mRimRect, mCorner, mCorner, mRimPaint);
        super.onDraw(canvas);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void drawBackdrop(Canvas canvas) {
        RenderNode node = mNode;
        hasMoved();
        mRecordedX = mLoc[0] - mSourceLoc[0];
        mRecordedY = mLoc[1] - mSourceLoc[1];
        int w = getWidth();
        int h = getHeight();
        node.setPosition(0, 0, w, h);
        if (w != mEffectW || h != mEffectH) {
            mEffectW = w;
            mEffectH = h;
            node.setRenderEffect(effect(w, h));
        }
        Canvas recording = node.beginRecording();
        recording.translate(-mRecordedX, -mRecordedY);
        recording.drawRenderNode(mSource.node);
        node.endRecording();
        canvas.drawRenderNode(node);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private RenderEffect effect(int w, int h) {
        float blur = BLUR_DP * mDensity;
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(SATURATION);
        RenderEffect frost = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturate),
                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return frost;
        }
        return RenderEffect.createChainEffect(lens(w, h), frost);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private RenderEffect lens(int w, int h) {
        RuntimeShader shader = new RuntimeShader(LENS_AGSL);
        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("radius", Math.min(mCorner, Math.min(w, h) / 2f));
        shader.setFloatUniform("band", LENS_BAND_DP * mDensity);
        shader.setFloatUniform("shift", LENS_SHIFT_DP * mDensity);
        return RenderEffect.createRuntimeShaderEffect(shader, "content");
    }

    /** Refreshes the on-screen offsets; true when they differ from the last recording. */
    private boolean hasMoved() {
        if (mSource == null) {
            return false;
        }
        getLocationInWindow(mLoc);
        mSource.getLocationInWindow(mSourceLoc);
        return mLoc[0] - mSourceLoc[0] != mRecordedX || mLoc[1] - mSourceLoc[1] != mRecordedY;
    }

    private static Bitmap noise() {
        if (sNoise == null) {
            int size = 96;
            int[] px = new int[size * size];
            Random random = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = random.nextInt(256);
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            sNoise = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        }
        return sNoise;
    }
}
