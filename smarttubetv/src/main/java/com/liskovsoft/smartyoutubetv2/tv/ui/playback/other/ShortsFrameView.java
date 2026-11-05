package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * MEETUBE(shorts): the ambient light around a short. It sits ABOVE the video and paints everything except a rounded
 * 9:16 hole over it: the short's thumbnail, a few pixels wide and stretched (so a soft blur of its colours), dimmed.
 * The video surface shows through the hole, which gives it round corners (a SurfaceView can't be clipped). The hole
 * gets the glass rim and a progress line along its bottom edge.
 */
public final class ShortsFrameView extends View {
    private static final int DIM = 0x8C000000;
    private final RectF mCard = new RectF();
    private final Path mHole = new Path();
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint();
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final float mRadius;
    private final float mProgressHeight;
    private Bitmap mBackdrop;
    private float mProgress = -1;
    private boolean mRimEnabled = true;

    public ShortsFrameView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mRadius = 18 * density;
        mProgressHeight = 3 * density;
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(2 * density);
        mRimPaint.setColor(0x73FFFFFF);
        mProgressPaint.setColor(Color.WHITE);
    }

    public void setCard(RectF card) {
        if (mCard.equals(card)) {
            return;
        }
        mCard.set(card);
        mHole.reset();
        mHole.addRoundRect(mCard, mRadius, mRadius, Path.Direction.CW);
        invalidate();
    }

    public void setBackdrop(@Nullable Bitmap backdrop) {
        mBackdrop = backdrop;
        invalidate();
    }

    public void setRimEnabled(boolean enabled) {
        mRimEnabled = enabled;
        invalidate();
    }

    /** 0..1, or -1 for none. */
    public void setProgress(float progress) {
        if (Math.abs(progress - mProgress) > 0.0005f) {
            mProgress = progress;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mCard.isEmpty()) {
            return;
        }

        canvas.save();
        canvas.clipPath(mHole, Region.Op.DIFFERENCE);
        if (mBackdrop != null && !mBackdrop.isRecycled()) {
            // centre crop the tiny bitmap over the whole view; bilinear filtering turns it into a soft glow
            float scale = Math.max(getWidth() / (float) mBackdrop.getWidth(), getHeight() / (float) mBackdrop.getHeight());
            float w = mBackdrop.getWidth() * scale;
            float h = mBackdrop.getHeight() * scale;
            mSrc.set(0, 0, mBackdrop.getWidth(), mBackdrop.getHeight());
            mDst.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            canvas.drawBitmap(mBackdrop, mSrc, mDst, mBitmapPaint);
            canvas.drawColor(DIM);
        } else {
            canvas.drawColor(Color.BLACK);
        }
        canvas.restore();

        if (mProgress >= 0) {
            canvas.save();
            canvas.clipPath(mHole);
            canvas.drawRect(mCard.left, mCard.bottom - mProgressHeight,
                    mCard.left + mCard.width() * Math.min(mProgress, 1f), mCard.bottom, mProgressPaint);
            canvas.restore();
        }

        if (mRimEnabled) {
            canvas.drawRoundRect(mCard, mRadius, mRadius, mRimPaint);
        }
    }
}
