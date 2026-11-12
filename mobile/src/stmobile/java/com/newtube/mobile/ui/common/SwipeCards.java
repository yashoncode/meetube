package com.newtube.mobile.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * MEETUBE(hype): cards stacked in one place that a sideways swipe pages through, like the YouTube
 * watch page's comments / "Hype this video" carousel. Its pages are the children that are not GONE,
 * in order, so a card that hides itself simply drops out. With two or more, dots sit in the top
 * right, left of the comments card's arrow; the lit one is a pill that slides with the finger.
 */
public final class SwipeCards extends FrameLayout {
    private final float mDp;
    private final int mSlop;
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mPill = new RectF();
    /** The page shown, as a float while dragged or settling (0 = first). */
    private float mPosition;
    private int mPage;
    private float mDownX;
    private float mDownY;
    private float mDownPosition;
    private boolean mDragging;
    @Nullable private VelocityTracker mVelocity;
    @Nullable private ValueAnimator mSettle;

    public SwipeCards(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setWillNotDraw(false);
    }

    private List<View> pages() {
        List<View> pages = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() != GONE) {
                pages.add(getChildAt(i));
            }
        }
        return pages;
    }

    /** Back to the first card, at once (a new video). */
    public void reset() {
        if (mSettle != null) {
            mSettle.cancel();
        }
        mPage = 0;
        mPosition = 0;
        place();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int last = Math.max(0, pages().size() - 1);
        if (mPage > last) { // the card being shown went away
            mPage = last;
            mPosition = last;
        }
        place();
    }

    /**
     * Depth, not a flat slide: the card leaving sinks back (a little smaller, fading) while it
     * travels at half the finger's pace, and the one coming in rises to full size over it.
     */
    private void place() {
        float width = getWidth();
        List<View> pages = pages();
        for (int i = 0; i < pages.size(); i++) {
            View page = pages.get(i);
            float d = i - mPosition; // < 0: gone left, > 0: waiting right
            float away = Math.min(1f, Math.abs(d));
            page.setPivotX(d < 0 ? width : 0);
            page.setPivotY(page.getHeight() / 2f);
            page.setTranslationX(d * (d < 0 ? width * 0.5f : width + 12 * mDp));
            page.setScaleX(1f - 0.08f * away);
            page.setScaleY(1f - 0.08f * away);
            // Gone left fades out whole (it sits under the shown card); later cards draw over it.
            page.setAlpha(d < 0 ? 1f - away : 1f - 0.75f * away * away);
        }
        invalidate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (pages().size() < 2) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = e.getX();
                mDownY = e.getY();
                mDragging = false;
                track(e, true);
                break;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                float dx = e.getX() - mDownX;
                if (Math.abs(dx) > mSlop && Math.abs(dx) > Math.abs(e.getY() - mDownY)) {
                    startDrag(e);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                recycle();
                break;
        }
        return false;
    }

    private void startDrag(MotionEvent e) {
        mDragging = true;
        mDownX = e.getX();
        if (mSettle != null) {
            mSettle.cancel();
        }
        mDownPosition = mPosition;
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (pages().size() < 2) {
            return super.onTouchEvent(e);
        }
        track(e, false);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = e.getX();
                mDownY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) {
                    if (Math.abs(e.getX() - mDownX) <= mSlop) {
                        return true;
                    }
                    startDrag(e);
                }
                int last = pages().size() - 1;
                float position = mDownPosition - (e.getX() - mDownX) / Math.max(1, getWidth());
                // Past either end it gives a little, then stops.
                mPosition = position < 0 ? position / 3 : position > last ? last + (position - last) / 3 : position;
                place();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    float velocity = 0;
                    if (mVelocity != null) {
                        mVelocity.computeCurrentVelocity(1000);
                        velocity = mVelocity.getXVelocity();
                    }
                    int target = Math.round(mPosition);
                    if (Math.abs(velocity) > 400 * mDp) {
                        target = velocity < 0 ? (int) Math.floor(mDownPosition) + 1 : (int) Math.ceil(mDownPosition) - 1;
                    }
                    settle(Math.max(0, Math.min(pages().size() - 1, target)));
                }
                mDragging = false;
                recycle();
                return true;
        }
        return true;
    }

    private void settle(int page) {
        mPage = page;
        mSettle = ValueAnimator.ofFloat(mPosition, page);
        mSettle.setDuration(340);
        mSettle.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)); // eases into place
        mSettle.addUpdateListener(a -> {
            mPosition = (float) a.getAnimatedValue();
            place();
        });
        mSettle.start();
    }

    private void track(MotionEvent e, boolean fresh) {
        if (fresh || mVelocity == null) {
            recycle();
            mVelocity = VelocityTracker.obtain();
        }
        mVelocity.addMovement(e);
    }

    private void recycle() {
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }

    /** The dots: each widens into the lit pill as the page nears it, and fades between. */
    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        int count = pages().size();
        if (count < 2) {
            return;
        }
        float dot = 5 * mDp;
        float gap = 4 * mDp;
        float pill = 14 * mDp;
        float cy = 21 * mDp; // the cards' title row
        float right = getWidth() - 44 * mDp; // left of the comments card's arrow
        float x = right - (count - 1) * (dot + gap) - pill;
        float lit = Math.max(0, Math.min(count - 1, mPosition));
        for (int i = 0; i < count; i++) {
            float near = Math.max(0f, 1f - Math.abs(i - lit)); // 1 = this page is shown
            float w = dot + (pill - dot) * near;
            mDot.setColor(Color.argb(Math.round(0x4D + (0xFF - 0x4D) * near), 255, 255, 255));
            mPill.set(x, cy - dot / 2, x + w, cy + dot / 2);
            canvas.drawRoundRect(mPill, dot / 2, dot / 2, mDot);
            x += w + gap;
        }
    }
}
