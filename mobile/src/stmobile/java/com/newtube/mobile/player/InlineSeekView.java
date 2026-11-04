package com.newtube.mobile.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/** Thin full-duration inline timeline with real buffered and played positions. */
final class InlineSeekView extends View {
    interface Listener {
        void onSeek(long positionMs);
    }

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBuffer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long mDurationMs;
    private long mPositionMs;
    private long mBufferedMs;
    @Nullable private Listener mListener;
    private boolean mDragging;

    InlineSeekView(Context context) {
        super(context);
        init();
    }

    InlineSeekView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        mTrack.setColor(0x99FFFFFF);
        mBuffer.setColor(0xCCFFFFFF);
        mProgress.setColor(0xFFFF0033);
        setContentDescription("Playback progress");
    }

    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    void setTimeline(long positionMs, long bufferedMs, long durationMs) {
        mDurationMs = Math.max(0, durationMs);
        if (!mDragging) {
            mPositionMs = Math.max(0, positionMs);
        }
        mBufferedMs = Math.max(0, bufferedMs);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        if (width <= 0) {
            return;
        }
        float center = getHeight() / 2f;
        float trackHeight = getResources().getDisplayMetrics().density * (mDragging ? 3f : 2f);
        drawSegment(canvas, width, center, trackHeight, mTrack, 1f);
        if (mDurationMs > 0) {
            drawSegment(canvas, width, center, trackHeight, mBuffer,
                    Math.min(1f, (float) mBufferedMs / mDurationMs));
            float played = Math.min(1f, (float) mPositionMs / mDurationMs);
            drawSegment(canvas, width, center, trackHeight, mProgress, played);
            float radius = getResources().getDisplayMetrics().density * (mDragging ? 5f : 2f);
            canvas.drawCircle(width * played, center, radius, mProgress);
        }
    }

    private static void drawSegment(Canvas canvas, float width, float center, float height, Paint paint, float fraction) {
        canvas.drawRect(0, center - height / 2f, Math.max(0, Math.min(width, width * fraction)),
                center + height / 2f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDurationMs <= 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                updateScrub(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateScrub(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                updateScrub(event.getX());
                mDragging = false;
                if (mListener != null) {
                    mListener.onSeek(mPositionMs);
                }
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void updateScrub(float x) {
        float fraction = getWidth() > 0 ? Math.max(0f, Math.min(1f, x / getWidth())) : 0f;
        mPositionMs = (long) (mDurationMs * fraction);
        invalidate();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
