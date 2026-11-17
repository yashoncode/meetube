package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * MEETUBE(glass segmented): liquidglass.theatom.lk's segmented control as a scrolling chip row (Home's
 * topic chips, the search result filters). One glass track holds the chips; the picked one sits on a
 * light pill that springs to a newly picked chip. Press the pill and it swells into a clear glass
 * lens you drag across the chips, stretching with its speed like jelly; let go on a chip to pick it.
 * A press anywhere else scrolls the row or taps a chip. A plain pill, no lens, on a weak phone.
 */
public class GlassChipTrack extends HorizontalScrollView {
    public interface Picked {
        void onPicked(int index);
    }

    private static final float PAD = 4f;
    private static final float CHIP_H = 36f;
    private static final float CHIP_PAD = 14f;
    private static final float SWELL_W = 6f;
    private static final float SWELL_H = 0.07f;
    /** {period s, damping}: the track's press and pull letting go, a soft wobble. */
    private static final float[] BOUNCE = {0.42f, 0.42f};
    private static final int PILL = 0xF2FFFFFF;
    /** {period s, damping}: the leading edge overshoots and wobbles; the trailing one lags behind. */
    private static final float[] LEAD = {0.11f, 0.55f};
    private static final float[] TRAIL = {0.22f, 0.68f};

    private final float mDp;
    /** MEETUBE(light mode): a light grey track with dark chips, the white pill standing off it. */
    private final boolean mLightTheme;
    private final boolean mSimple;
    /** MEETUBE(youtube mode): YouTube's text tabs - no track, no pill, an accent line under the pick. */
    private final boolean mTabs;
    private final int mUnderline;
    private final int mSlop;
    private final Row mRow;
    private final List<CharSequence> mLabels = new ArrayList<>();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private final FloatValueHolder mLeft = new FloatValueHolder(0f);
    private final FloatValueHolder mRight = new FloatValueHolder(0f);
    private final FloatValueHolder mLift = new FloatValueHolder(0f);
    private final FloatValueHolder mStretch = new FloatValueHolder(0f);
    /** Signed jelly, -1..1: the whole track leans out the way the pill runs. */
    private final FloatValueHolder mLean = new FloatValueHolder(0f);
    private final SpringAnimation mLeanSpring;
    /** 0..1: a finger anywhere on the track swells it. */
    private final FloatValueHolder mPress = new FloatValueHolder(0f);
    private final SpringAnimation mPressSpring;
    /** How far a finger has pulled the track (px) where the row has nothing more to scroll. */
    private float mPull;
    private float mLastX;
    private final SpringAnimation mLeftSpring;
    private final SpringAnimation mRightSpring;
    private final SpringAnimation mLiftSpring;
    private final SpringAnimation mJellySpring;

    @Nullable private Picked mPicked;
    private boolean mGlassed;
    private boolean mPlaced;
    private int mSelected = -1;
    private int mHover = -1;
    private boolean mArmed;
    private boolean mDragging;
    private float mDownX;
    private final Runnable mDrop = new Runnable() {
        @Override
        public void run() {
            if (!mDragging) {
                GlassSwitch.spring(mLiftSpring, 0f, GlassSwitch.SETTLE);
            }
        }
    };

    public GlassChipTrack(Context context) {
        this(context, null);
    }

    public GlassChipTrack(Context context, @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mLightTheme = ThemeMode.isLight(context);
        mTabs = YouTubeMode.isOn(context);
        mSimple = NavLens.lowEnd(context) || mTabs; // no lens, swell or jelly on the tabs
        TypedValue accent = new TypedValue();
        mUnderline = context.getTheme().resolveAttribute(com.liskovsoft.smartyoutubetv2.tv.R.attr.meetubeAccent, accent, true) ? accent.data : 0xFFFF0033;
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setHorizontalScrollBarEnabled(false);
        setFillViewport(!mTabs); // a few chips share the bar's width (weights below); many still scroll; tabs sit left
        setOverScrollMode(OVER_SCROLL_NEVER);
        setHorizontalFadingEdgeEnabled(true); // the chips melt away at a side that has more to scroll to
        setFadingEdgeLength(Math.round(28 * mDp));
        setElevation(mTabs ? 0f : 4 * mDp);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        setClipToOutline(!mTabs);
        mRow = new Row(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(PAD * mDp);
        mRow.setPadding(pad, pad, pad, pad);
        addView(mRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLeftSpring = newSpring(mLeft, true);
        mRightSpring = newSpring(mRight, false);
        mLiftSpring = newSpring(mLift, false);
        mJellySpring = newSpring(mStretch, false);
        mLeanSpring = newSpring(mLean, false);
        mPressSpring = newSpring(mPress, false);
    }

    /** Any finger on the track, pill or not: it swells under the press, stretches the way it is pulled, and wobbles back. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!mSimple) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mLastX = ev.getX();
                    mPull = 0f;
                    GlassSwitch.spring(mPressSpring, 1f, GlassSwitch.PRESS);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float delta = ev.getX() - mLastX;
                    mLastX = ev.getX();
                    // Only where the row cannot scroll that way (a short row, or at its end): there it stretches.
                    if (!mDragging && delta != 0f && !canScrollHorizontally(delta > 0 ? -1 : 1)) {
                        mPull += delta;
                        GlassSwitch.spring(mLeanSpring, (float) Math.tanh(mPull / (90 * mDp)), GlassSwitch.DRAG);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mPull = 0f;
                    GlassSwitch.spring(mPressSpring, 0f, BOUNCE);
                    GlassSwitch.spring(mLeanSpring, 0f, BOUNCE);
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * The whole track as jelly: it swells a little under a press and stretches out the way the pill
     * runs, anchored on the side it leaves, then wobbles back to size. The anchor slides with the lean,
     * so it never jumps when the direction flips.
     */
    private void shapeTrack() {
        int w = getWidth();
        if (mSimple || w == 0) {
            return;
        }
        float lean = mLean.getValue();
        float press = mPress.getValue(); // may overshoot below 0 on the way back: that is the wobble
        float side = Math.max(-1f, Math.min(1f, lean * 4f));
        setPivotX(w / 2f * (1f - side));
        setPivotY(getHeight() / 2f);
        setScaleX((1f + Math.abs(lean) * 18 * mDp / w) * (1f + 6 * mDp * press / w));
        setScaleY(1f + 0.07f * press - 0.08f * Math.abs(lean));
    }

    private SpringAnimation newSpring(FloatValueHolder holder, boolean feedsJelly) {
        SpringAnimation spring = new SpringAnimation(holder);
        spring.setSpring(new SpringForce(holder.getValue()));
        spring.setMinimumVisibleChange(0.001f);
        spring.addUpdateListener((a, value, velocity) -> {
            if (feedsJelly && !mSimple && !mDragging) { // dragged, the track holds still under the finger
                double speed = Math.tanh(velocity / (GlassSwitch.JELLY_SPEED * 220 * mDp));
                GlassSwitch.spring(mJellySpring, (float) (GlassSwitch.JELLY_MAX * Math.abs(speed)), GlassSwitch.JELLY);
                GlassSwitch.spring(mLeanSpring, (float) speed, GlassSwitch.JELLY);
            }
            shapeTrack();
            recolor();
            mRow.invalidate();
        });
        return spring;
    }

    /** The glass under the track: what it frosts (the feed, the results). Once. */
    public void setGlassSource(@Nullable GlassSource source) {
        if (!mGlassed && !mTabs) { // the tabs sit on the top bar's frost
            mGlassed = true;
            GlassDrawable.applyPage(this, source, 0);
        }
    }

    public void setOnPicked(@Nullable Picked picked) {
        mPicked = picked;
    }

    /** The chips, left to right; the same labels again change nothing. */
    public void setChips(List<? extends CharSequence> labels) {
        if (labels.equals(mLabels)) {
            return;
        }
        mLabels.clear();
        mLabels.addAll(labels);
        mRow.removeAllViews();
        mPlaced = false;
        for (int i = 0; i < mLabels.size(); i++) {
            TextView chip = new TextView(getContext());
            chip.setText(mLabels.get(i));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(com.newtube.mobile.ui.common.AppFont.medium(chip.getContext()));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine();
            int pad = Math.round(CHIP_PAD * mDp);
            chip.setPadding(pad, 0, pad, 0);
            int index = i;
            chip.setOnClickListener(v -> pick(index));
            if (mTabs) {
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            }
            mRow.addView(chip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(CHIP_H * mDp), mTabs ? 0f : 1f));
        }
        recolor();
    }

    /** The chip on the pill, -1 for none. */
    public void setSelected(int index) {
        if (index == mSelected) {
            return;
        }
        mSelected = index;
        glideToSelected(true);
        recolor();
    }

    private void pick(int index) {
        if (index == mSelected) {
            return;
        }
        setSelected(index); // the track's own press bounce marks the tap; no lens flash
        if (mPicked != null) {
            mPicked.onPicked(index);
        }
    }

    private void glideToSelected(boolean animate) {
        View chip = mSelected >= 0 ? mRow.getChildAt(mSelected) : null;
        if (chip == null || chip.getWidth() == 0) {
            return;
        }
        moveTo(chip.getLeft(), chip.getRight(), animate && mPlaced && !mSimple || animate && mPlaced);
        mPlaced = true;
        int visibleLeft = getScrollX();
        int visibleRight = visibleLeft + getWidth();
        if (chip.getLeft() < visibleLeft) {
            smoothScrollTo(chip.getLeft() - Math.round(PAD * mDp), 0);
        } else if (chip.getRight() > visibleRight) {
            smoothScrollTo(chip.getRight() - getWidth() + Math.round(PAD * mDp), 0);
        }
    }

    /** The edge it heads for leads, quick and bouncy; the other trails, so the pill stretches, then snaps back. */
    private void glide(float left, float right) {
        boolean rightward = left > mLeftSpring.getSpring().getFinalPosition();
        GlassSwitch.spring(mLeftSpring, left, rightward ? TRAIL : LEAD);
        GlassSwitch.spring(mRightSpring, right, rightward ? LEAD : TRAIL);
    }

    private void moveTo(float left, float right, boolean animate) {
        if (animate) {
            glide(left, right);
        } else {
            mLeftSpring.cancel();
            mRightSpring.cancel();
            mLeft.setValue(left);
            mRight.setValue(right);
            mLeftSpring.getSpring().setFinalPosition(left);
            mRightSpring.getSpring().setFinalPosition(right);
            mRow.invalidate();
        }
    }

    private float pillAlpha() {
        if (mSelected < 0 && !mDragging) {
            return 0f;
        }
        return mSimple ? 1f : Math.max(0f, Math.min(1f, 1f - 1.4f * mLift.getValue())); // the pill melts into the lens, no snap
    }

    /** Black on the light pill, white on the glass or the lens. */
    private void recolor() {
        int on = mDragging ? mHover : mSelected;
        int color = mLightTheme ? Color.BLACK : blend(Color.WHITE, Color.BLACK, pillAlpha());
        int rest = mLightTheme ? 0xFF3A3A3C : Color.WHITE;
        for (int i = 0; i < mRow.getChildCount(); i++) {
            TextView chip = (TextView) mRow.getChildAt(i);
            if (mTabs) { // all in the page's ink, the pick bold over its line
                chip.setTextColor(mLightTheme ? Color.BLACK : Color.WHITE);
                chip.setTypeface(android.graphics.Typeface.create(com.newtube.mobile.ui.common.AppFont.medium(chip.getContext()),
                        i == on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
                continue;
            }
            if (mDragging) {
                chip.setTextColor(i == on ? color : rest);
                continue;
            }
            // Ink follows the pill as it slides: a pick used to flip both labels at once, so the old
            // one went white on the still-white pill and the new one dark on the glass.
            float cover = chip.getWidth() == 0 ? (i == on ? 1f : 0f) : Math.max(0f,
                    Math.min(mRight.getValue(), chip.getRight()) - Math.max(mLeft.getValue(), chip.getLeft())) / chip.getWidth();
            chip.setTextColor(blend(rest, color, Math.min(1f, cover)));
        }
    }

    private static int blend(int from, int to, float t) {
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    // ---- Touch: a press on the pill lifts it into the lens; a drag moves it; anything else is the row's.

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float x = ev.getX() + getScrollX();
                mDownX = ev.getX();
                mArmed = !mSimple && mSelected >= 0 && x >= mLeft.getValue() && x <= mRight.getValue();
                if (mArmed) {
                    removeCallbacks(mDrop);
                    GlassSwitch.spring(mLiftSpring, 1f, GlassSwitch.PRESS);
                    Haptics.tick(this);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mArmed && !mDragging && Math.abs(ev.getX() - mDownX) > mSlop) {
                    mDragging = true;
                    mHover = mSelected;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mArmed && !mDragging) {
                    mArmed = false;
                    GlassSwitch.spring(mLiftSpring, 0f, GlassSwitch.SETTLE);
                }
                break;
            default:
                break;
        }
        return !mDragging ? super.onInterceptTouchEvent(ev) : true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                drag(ev.getX());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drop(ev.getActionMasked() == MotionEvent.ACTION_UP);
                break;
            default:
                break;
        }
        return true;
    }

    private float mDragX;
    private float mScrollCarry;
    private boolean mEdgeScrolling;

    /**
     * Held near either end, the row glides on under the lens every frame - faster the deeper the
     * finger sits in the edge zone, eased in - and the lens rides along to the chips coming in.
     */
    private final Runnable mEdgeScroll = new Runnable() {
        @Override
        public void run() {
            float edge = 64 * mDp;
            float depth = mDragX < edge ? -(edge - mDragX) / edge
                    : mDragX > getWidth() - edge ? (mDragX - (getWidth() - edge)) / edge : 0f;
            depth = Math.max(-1f, Math.min(1f, depth));
            if (!mDragging || depth == 0f || !canScrollHorizontally(depth < 0 ? -1 : 1)) {
                mEdgeScrolling = false;
                mScrollCarry = 0f;
                return;
            }
            mScrollCarry += Math.signum(depth) * depth * depth * 12 * mDp; // px per frame, eased
            int step = (int) mScrollCarry;
            mScrollCarry -= step;
            if (step != 0) {
                scrollBy(step, 0);
                followFinger();
            }
            postOnAnimation(this);
        }
    };

    private void drag(float viewX) {
        mDragX = viewX;
        followFinger();
        if (!mEdgeScrolling) {
            mEdgeScrolling = true;
            postOnAnimation(mEdgeScroll);
        }
    }

    private void followFinger() {
        int count = mRow.getChildCount();
        if (count == 0) {
            return;
        }
        float x = mDragX + getScrollX();
        int hover = 0;
        int before = 0; // the last chip whose centre is left of the finger
        for (int i = 0; i < count; i++) {
            View chip = mRow.getChildAt(i);
            if (x >= chip.getLeft()) {
                hover = i;
            }
            if (x >= (chip.getLeft() + chip.getRight()) / 2f) {
                before = i;
            }
        }
        // The lens grows or shrinks smoothly between two chips' widths as it crosses from one to the next.
        View a = mRow.getChildAt(before);
        View b = mRow.getChildAt(Math.min(before + 1, count - 1));
        float ca = (a.getLeft() + a.getRight()) / 2f;
        float cb = (b.getLeft() + b.getRight()) / 2f;
        float t = cb > ca ? Math.max(0f, Math.min(1f, (x - ca) / (cb - ca))) : 0f;
        t = t * t * (3f - 2f * t);
        float width = a.getWidth() + (b.getWidth() - a.getWidth()) * t;
        float min = mRow.getChildAt(0).getLeft();
        float max = mRow.getChildAt(count - 1).getRight() - width;
        float left = Math.max(min, Math.min(max, x - width / 2f));
        // Under the finger both edges follow on one well-damped spring: the lead/trail glide is for
        // a pick, and re-aimed on every move (its lead flipping with each jitter) it wobbled.
        GlassSwitch.spring(mLeftSpring, left, GlassSwitch.DRAG);
        GlassSwitch.spring(mRightSpring, left + width, GlassSwitch.DRAG);
        if (hover != mHover) {
            mHover = hover;
            Haptics.tick(this);
            recolor();
        }
    }

    private void drop(boolean up) {
        mDragging = false;
        mArmed = false;
        int target = up ? mHover : mSelected;
        GlassSwitch.spring(mLiftSpring, 0f, GlassSwitch.SETTLE);
        boolean changed = target >= 0 && target != mSelected;
        mSelected = target;
        glideToSelected(true);
        recolor();
        if (changed && mPicked != null) {
            mPicked.onPicked(target);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mDrop);
        removeCallbacks(mEdgeScroll);
        mEdgeScrolling = false;
        mLeftSpring.cancel();
        mRightSpring.cancel();
        mLiftSpring.cancel();
        mJellySpring.cancel();
        mLeanSpring.cancel();
        mPressSpring.cancel();
        mLean.setValue(0f);
        mPress.setValue(0f);
        setScaleX(1f);
        setScaleY(1f);
        super.onDetachedFromWindow();
    }

    /** Draws the pill under the chips and the lens over them, in the scrolling row's own coordinates. */
    private final class Row extends LinearLayout {
        Row(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (!mDragging) {
                glideToSelected(false);
                if (!mPlaced) {
                    recolor();
                }
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            float alpha = pillAlpha();
            float pad = PAD * mDp;
            float top = pad;
            float bottom = getHeight() - pad;
            if (mTabs) {
                if (mSelected >= 0) { // the line under the pick, its text's width
                    float inset = CHIP_PAD * mDp;
                    float line = 3 * mDp;
                    float y = (getHeight() + CHIP_H * mDp) / 2f - line;
                    mPaint.setStyle(Paint.Style.FILL);
                    mPaint.setColor(mUnderline);
                    mRect.set(mLeft.getValue() + inset, y, mRight.getValue() - inset, y + line);
                    canvas.drawRoundRect(mRect, line / 2f, line / 2f, mPaint);
                }
            } else if (alpha > 0f) {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(Color.argb(Math.round(Color.alpha(PILL) * alpha), 255, 255, 255));
                float squash = (bottom - top) * mStretch.getValue() * 0.35f; // thinner as it stretches
                mRect.set(mLeft.getValue(), top + squash, mRight.getValue(), bottom - squash);
                canvas.drawRoundRect(mRect, mRect.height() / 2f, mRect.height() / 2f, mPaint);
            }
            super.dispatchDraw(canvas);
            float lift = mSimple ? 0f : Math.max(0f, Math.min(1f, mLift.getValue()));
            if (lift > 0f && (mSelected >= 0 || mDragging)) {
                drawLens(canvas, lift, top, bottom);
            }
        }

        private void drawLens(Canvas canvas, float lift, float top, float bottom) {
            float stretch = mStretch.getValue();
            float cx = (mLeft.getValue() + mRight.getValue()) / 2f;
            float cy = (top + bottom) / 2f;
            float kw = (mRight.getValue() - mLeft.getValue() + SWELL_W * mDp * lift) * (1f + stretch);
            float kh = (bottom - top) * (1f + SWELL_H * lift) * (1f - stretch * 0.5f);
            kw = Math.max(kw, kh * 1.5f); // a capsule even over a short chip, never a stubby ball
            // Inside the track's own capsule at its ends: swollen over the first or last chip it
            // poked past the rounded end, and the track's outline cut its rim off.
            float inset = (getHeight() - kh) / 2f + mDp;
            float minX = GlassChipTrack.this.getScrollX() + inset;
            float maxX = GlassChipTrack.this.getScrollX() + GlassChipTrack.this.getWidth() - inset;
            kw = Math.min(kw, maxX - minX);
            cx = Math.max(minX + kw / 2f, Math.min(maxX - kw / 2f, cx));
            mRect.set(cx - kw / 2f, cy - kh / 2f, cx + kw / 2f, cy + kh / 2f);
            float r = kh / 2f;
            mShadow.setStyle(Paint.Style.FILL);
            mShadow.setColor(Color.TRANSPARENT);
            mShadow.setShadowLayer((3 + 5 * lift) * mDp, 0, (1 + 2 * lift) * mDp,
                    Color.argb(Math.round(255 * 0.22f * lift), 0, 0, 0));
            canvas.drawRoundRect(mRect, r, r, mShadow);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(Color.argb(Math.round(40 * lift), 255, 255, 255));
            canvas.drawRoundRect(mRect, r, r, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(0.9f * mDp);
            mPaint.setColor(Color.argb(Math.round(235 * lift), 255, 255, 255));
            canvas.drawRoundRect(mRect, r, r, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }
}
