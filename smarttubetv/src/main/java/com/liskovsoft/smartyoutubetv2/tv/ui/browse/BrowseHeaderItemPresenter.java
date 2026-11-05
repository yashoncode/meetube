package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.IconHeaderItemPresenter;

import java.util.Map;
import java.util.WeakHashMap;

/** Keeps the stock header layout, binding, marquee and focus handling. */
public final class BrowseHeaderItemPresenter extends IconHeaderItemPresenter {
    // MEETUBE: the focused pill tilts up into place like a glass key, its icon pops and a glint sweeps across it
    private static final float FOCUS_TILT = 22f;
    private static final float ICON_POP = 1.15f;
    private final Map<View, Long> mHeaders = new WeakHashMap<>();
    private ColorStateList mOriginalTextColors;
    private boolean mGlassEnabled = true;
    private long mSelectedHeaderId = -1;

    public BrowseHeaderItemPresenter(int resId, String iconUrl) {
        super(resId, iconUrl);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        ViewHolder holder = super.onCreateViewHolder(parent);
        TextView label = holder.view.findViewById(R.id.header_label);
        mOriginalTextColors = label.getTextColors();
        if (Build.VERSION.SDK_INT >= 23) {
            holder.view.setForeground(new GlintDrawable(holder.view.getResources().getDisplayMetrics().density * 27));
        }
        // ItemBridgeAdapter chains this listener with Leanback's own focus listener.
        // MEETUBE: Leanback forwards the wrapper; activation belongs to the actual pill.
        holder.view.setOnFocusChangeListener((view, focused) -> {
            updateColors(holder.view);
            animateFocus(holder.view, focused);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder holder, Object item) {
        super.onBindViewHolder(holder, item);
        mHeaders.put(holder.view, ((Row) item).getHeaderItem().getId());
        updateStyle(holder.view);
        if (!mGlassEnabled) {
            super.onSelectLevelChanged((ViewHolder) holder);
        }
    }

    public void setGlassState(boolean enabled, long selectedHeaderId) {
        mGlassEnabled = enabled;
        mSelectedHeaderId = selectedHeaderId;
        for (View view : mHeaders.keySet()) {
            updateStyle(view);
        }
    }

    @Override
    protected void onSelectLevelChanged(ViewHolder holder) {
        if (mGlassEnabled) {
            holder.view.setAlpha(1f);
        } else {
            super.onSelectLevelChanged(holder);
        }
    }

    private void updateStyle(View view) {
        view.setBackgroundResource(mGlassEnabled ? R.drawable.browse_glass_header : android.R.color.transparent);
        // MEETUBE: roomy, stable content insets; focus never changes an item's geometry.
        int start = mGlassEnabled ? view.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_item_padding_start) : 0;
        int end = mGlassEnabled ? view.getResources().getDimensionPixelSize(R.dimen.browse_sidebar_item_padding_end) : 0;
        Long headerId = mHeaders.get(view);
        view.setPaddingRelative(start, view.getPaddingTop(), end, view.getPaddingBottom());
        view.setActivated(mGlassEnabled && headerId != null && headerId == mSelectedHeaderId);
        view.setAlpha(mGlassEnabled || view.hasFocus() ? 1f : view.getResources()
                .getFraction(R.fraction.lb_browse_header_unselect_alpha, 1, 1));
        updateColors(view);
        resetFocusMotion(view); // a rebound (recycled) pill starts at rest, popped only if it holds focus
    }

    private void updateColors(View view) {
        TextView label = view.findViewById(R.id.header_label);
        ImageView icon = view.findViewById(R.id.header_icon);
        View indicator = view.findViewById(R.id.browse_header_indicator);
        // MEETUBE: the current section stays marked when focus moves to content or the orbs.
        indicator.setVisibility(!mGlassEnabled ? View.GONE :
                view.isActivated() && !view.hasFocus() ? View.VISIBLE : View.INVISIBLE);
        if (mGlassEnabled && view.hasFocus()) {
            label.setTextColor(0xFF141414);
            icon.setColorFilter(0xFF141414);
            icon.setImageAlpha(255);
        } else if (mGlassEnabled) {
            label.setTextColor(view.isActivated() ? 0xFFFFFFFF : 0xD9FFFFFF);
            icon.clearColorFilter();
            icon.setImageAlpha(view.isActivated() ? 255 : 217);
        } else {
            label.setTextColor(mOriginalTextColors);
            icon.clearColorFilter();
            icon.setImageAlpha(255);
        }
    }

    /**
     * MEETUBE: view-property animations only (no layout pass), cheap on a low-RAM TV. Gaining focus, the pill tilts
     * up into place from {@link #FOCUS_TILT} degrees with a small overshoot, the icon pops and the label nudges in,
     * then a glint crosses the glass. Losing focus settles everything back quickly.
     */
    private void animateFocus(View pill, boolean focused) {
        if (!mGlassEnabled) {
            resetFocusMotion(pill);
            return;
        }
        View icon = pill.findViewById(R.id.header_icon);
        View label = pill.findViewById(R.id.header_label);
        float density = pill.getResources().getDisplayMetrics().density;
        pill.animate().cancel();
        if (focused) {
            pill.setPivotX(pill.getWidth() / 2f);
            pill.setPivotY(pill.getHeight() / 2f);
            pill.setRotationX(FOCUS_TILT);
            pill.animate().rotationX(0).setDuration(420).setInterpolator(new OvershootInterpolator(2.5f)).start();
            icon.animate().scaleX(ICON_POP).scaleY(ICON_POP).setDuration(360)
                    .setInterpolator(new OvershootInterpolator(3f)).start();
            label.animate().translationX(3 * density).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
            if (Build.VERSION.SDK_INT >= 23 && pill.getForeground() instanceof GlintDrawable) {
                ((GlintDrawable) pill.getForeground()).sweep();
            }
        } else {
            pill.setRotationX(0);
            icon.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            label.animate().translationX(0).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            if (Build.VERSION.SDK_INT >= 23 && pill.getForeground() instanceof GlintDrawable) {
                ((GlintDrawable) pill.getForeground()).stop();
            }
        }
    }

    private void resetFocusMotion(View pill) {
        View icon = pill.findViewById(R.id.header_icon);
        View label = pill.findViewById(R.id.header_label);
        boolean popped = mGlassEnabled && pill.hasFocus();
        pill.animate().cancel();
        icon.animate().cancel();
        label.animate().cancel();
        pill.setRotationX(0);
        icon.setScaleX(popped ? ICON_POP : 1f);
        icon.setScaleY(popped ? ICON_POP : 1f);
        label.setTranslationX(popped ? 3 * pill.getResources().getDisplayMetrics().density : 0);
    }

    /** MEETUBE: a soft diagonal light band that crosses the focused pill once, clipped to its rounded shape. */
    private static final class GlintDrawable extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix mMatrix = new Matrix();
        private final Path mClip = new Path();
        private final float mRadius;
        private float mBand;
        private float mProgress = -1; // outside [0, 1]: nothing drawn
        private ValueAnimator mAnimator;

        GlintDrawable(float radius) {
            mRadius = radius;
        }

        void sweep() {
            stop();
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setStartDelay(140);
            mAnimator.setDuration(700);
            mAnimator.setInterpolator(new DecelerateInterpolator(1.2f));
            mAnimator.addUpdateListener(animation -> setProgress((float) animation.getAnimatedValue()));
            mAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    setProgress(-1);
                }
            });
            mAnimator.start();
        }

        void stop() {
            if (mAnimator != null) {
                mAnimator.cancel(); // onAnimationEnd hides the band
                mAnimator = null;
            }
            setProgress(-1);
        }

        private void setProgress(float progress) {
            mProgress = progress;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            mClip.reset();
            mClip.addRoundRect(new RectF(bounds), mRadius, mRadius, Path.Direction.CW);
            mBand = Math.max(1, bounds.height() * 1.4f);
            // one band, slanted across the pill's height; draw() only slides it
            mPaint.setShader(new LinearGradient(0, 0, mBand, bounds.height() * 0.6f,
                    new int[] {0x00FFFFFF, 0xD9FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mProgress < 0 || mProgress > 1 || mPaint.getShader() == null) {
                return;
            }
            Rect bounds = getBounds();
            float x = bounds.left - mBand + (bounds.width() + mBand) * mProgress;
            mMatrix.setTranslate(x, bounds.top);
            mPaint.getShader().setLocalMatrix(mMatrix);
            canvas.save();
            canvas.clipPath(mClip);
            canvas.drawRect(x, bounds.top, x + mBand, bounds.bottom, mPaint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
