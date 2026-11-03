package com.liskovsoft.smartyoutubetv2.tv.ui.search;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * MEETUBE: the phone app's search glow - opening Search lights the screen's edges in Siri's colours, a glow that
 * turns once around the display and fades. Drawn over the whole window and removed when it is done. Thicker than
 * the phone's, for a screen seen from the sofa, and square-cornered like a TV panel.
 */
public final class SiriGlow extends View {
    private static final int[] SIRI = {0xFFFF6A88, 0xFFFFB86B, 0xFFB45CFF, 0xFF5E9BFF, 0xFF4FD8FF, 0xFFFF6A88};
    private static final long DURATION_MS = 1400;

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mRotation = new Matrix();
    private final RectF mBounds = new RectF();
    private SweepGradient mSweep;
    private float mTurn;

    /** Play once over {@code activity}'s window. */
    public static void play(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        decor.post(() -> {
            SiriGlow glow = new SiriGlow(activity);
            decor.addView(glow, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ValueAnimator run = ValueAnimator.ofFloat(0f, 1f);
            run.setDuration(DURATION_MS);
            run.setInterpolator(new LinearInterpolator());
            run.addUpdateListener(a -> {
                float t = a.getAnimatedFraction();
                glow.mTurn = t * 360f;
                // In fast, a held glow, out slowly.
                glow.setAlpha(t < 0.15f ? t / 0.15f : t > 0.55f ? (1f - t) / 0.45f : 1f);
                glow.invalidate();
            });
            run.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    decor.removeView(glow);
                }
            });
            run.start();
        });
    }

    private SiriGlow(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(4f * density);
        mHalo.setStyle(Paint.Style.STROKE);
        mHalo.setStrokeWidth(24f * density);
        mHalo.setAlpha(170);
        mHalo.setMaskFilter(new BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL));
        if (Build.VERSION.SDK_INT < 28) {
            setLayerType(LAYER_TYPE_SOFTWARE, null); // BlurMaskFilter needs it before Android 9
        }
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mSweep = new SweepGradient(w / 2f, h / 2f, SIRI, null);
        mLine.setShader(mSweep);
        mHalo.setShader(mSweep);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSweep == null) {
            return;
        }
        mRotation.setRotate(mTurn, getWidth() / 2f, getHeight() / 2f);
        mSweep.setLocalMatrix(mRotation);
        float inset = mLine.getStrokeWidth() / 2f;
        mBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawRect(mBounds, mHalo);
        canvas.drawRect(mBounds, mLine);
    }
}
