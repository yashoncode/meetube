package com.newtube.mobile.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(issue #272): a speaker beside a volume slider, its three sound waves lighting up with the
 * level (each fills in over a third of 0..100%). Past 100% (the boost) the waves warm into the
 * accent and thicken, and while the finger drags there, accent rings ripple out of the speaker,
 * faster the higher the boost. Every step of the slider thumps the speaker cone.
 */
public final class VolumeFace extends View {
    private static final int WAVES = 3;
    /** How far each frame closes on the target: settles in about a quarter second. */
    private static final float EASE = 0.3f;
    private static final int RIPPLES = 4;
    private static final float RIPPLE_MS = 700f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mSpeaker = new Path();
    private final android.graphics.CornerPathEffect mRound;
    private final RectF mRect = new RectF();
    private final int mInk;
    private final int mAccent;
    private final float mDp;
    private final boolean mMotion;
    /** Shown values, eased toward the level: the level (0..2) and the cone's thump (0..1). */
    private float mShown;
    private float mThump;
    private float mLevel;
    private boolean mDragging;
    private boolean mRunning;
    private boolean mPlaced;
    /** Birth times of the boost ripples (0: none), a ring buffer. */
    private final long[] mRipples = new long[RIPPLES];
    private int mNextRipple;
    private long mLastRipple;

    public VolumeFace(Context context) {
        super(context);
        mDp = context.getResources().getDisplayMetrics().density;
        mInk = ContextCompat.getColor(context, R.color.mobile_color_on_surface);
        TypedValue accent = new TypedValue();
        mAccent = context.getTheme().resolveAttribute(R.attr.meetubeAccent, accent, true) ? accent.data : mInk;
        mMotion = ValueAnimator.areAnimatorsEnabled() && !NavLens.lowEnd(context);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mRound = new android.graphics.CornerPathEffect(1.6f * mDp);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the slider speaks the level
    }

    public static VolumeFace create(Context context) {
        return new VolumeFace(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(46 * mDp), Math.round(36 * mDp));
    }

    /** The level now, in percent; {@code fromUser} animates to it, otherwise it is set at once. */
    public void set(int percent, boolean fromUser) {
        mLevel = Math.max(0f, Math.min(2f, percent / 100f));
        if (!fromUser || !mPlaced || !mMotion) {
            mPlaced = true;
            mShown = mLevel;
            invalidate();
            return;
        }
        mDragging = true;
        mThump = 1f;
        run();
    }

    /** The finger left the slider: a last ripple if boosting, then everything settles. */
    public void release() {
        mDragging = false;
        if (mLevel > 1f && mMotion) {
            ripple(SystemClock.uptimeMillis());
        }
        run();
    }

    private void ripple(long now) {
        mRipples[mNextRipple] = now;
        mNextRipple = (mNextRipple + 1) % RIPPLES;
        mLastRipple = now;
    }

    private void run() {
        if (!mRunning) {
            mRunning = true;
            postOnAnimation(mFrame);
        }
    }

    private final Runnable mFrame = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            mShown += (mLevel - mShown) * EASE;
            mThump *= 0.78f;
            float boost = Math.max(0f, mLevel - 1f);
            if (mDragging && boost > 0f && now - mLastRipple > 520 - 300 * boost) {
                ripple(now);
            }
            boolean moving = Math.abs(mLevel - mShown) > 0.002f || mThump > 0.01f;
            for (long born : mRipples) {
                moving |= born != 0 && now - born < RIPPLE_MS;
            }
            invalidate();
            if (moving && isAttachedToWindow()) {
                postOnAnimation(this);
            } else {
                mRunning = false;
            }
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mFrame);
        mRunning = false;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float cy = getHeight() / 2f;
        float boost = Math.max(0f, Math.min(1f, mShown - 1f));
        int warm = ColorUtils.blendARGB(mInk, mAccent, boost);

        // The speaker: a magnet and a cone, the cone punched out a little by each step.
        float s = 1f + 0.14f * mThump;
        float x0 = 4 * mDp;
        float mouth = x0 + 12 * mDp * s;
        mSpeaker.rewind();
        mSpeaker.moveTo(x0, cy - 3.5f * mDp);
        mSpeaker.lineTo(x0 + 5 * mDp, cy - 3.5f * mDp);
        mSpeaker.lineTo(mouth, cy - 8.5f * mDp * s);
        mSpeaker.lineTo(mouth, cy + 8.5f * mDp * s);
        mSpeaker.lineTo(x0 + 5 * mDp, cy + 3.5f * mDp);
        mSpeaker.lineTo(x0, cy + 3.5f * mDp);
        mSpeaker.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setPathEffect(mRound);
        mPaint.setColor(warm);
        canvas.drawPath(mSpeaker, mPaint);
        mPaint.setPathEffect(null);

        // The waves, each lighting over its third of 0..100%; thicker and accent in the boost.
        mPaint.setStyle(Paint.Style.STROKE);
        float cx = x0 + 6 * mDp;
        float push = 1.5f * mDp * mThump;
        for (int i = 0; i < WAVES; i++) {
            float lit = Math.max(0f, Math.min(1f, (mShown - i / 3f) * 3f));
            float r = (11 + 5.5f * i) * mDp + push;
            mRect.set(cx - r, cy - r, cx + r, cy + r);
            mPaint.setStrokeWidth((2f + 0.8f * boost) * mDp);
            mPaint.setColor(mInk);
            mPaint.setAlpha(36);
            canvas.drawArc(mRect, -40, 80, false, mPaint);
            if (lit > 0f) { // the lit part grows from the middle out
                mPaint.setColor(warm);
                canvas.drawArc(mRect, -40 * lit, 80 * lit, false, mPaint);
            }
        }

        // The boost ripples: accent rings leaving the speaker and fading.
        long now = SystemClock.uptimeMillis();
        for (long born : mRipples) {
            float t = born == 0 ? 1f : (now - born) / RIPPLE_MS;
            if (t >= 1f) continue;
            float r = (11 + 20 * t) * mDp;
            mRect.set(cx - r, cy - r, cx + r, cy + r);
            mPaint.setStrokeWidth(2.4f * mDp * (1f - t));
            mPaint.setColor(mAccent);
            mPaint.setAlpha(Math.round(200 * (1f - t) * (1f - t)));
            canvas.drawArc(mRect, -30, 60, false, mPaint);
        }
    }
}
