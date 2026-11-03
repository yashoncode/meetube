package com.liskovsoft.smartyoutubetv2.tv.ui.search;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * MEETUBE: the phone app's typing glow (GlowEditText) for leanback's search field - the query glows in Siri's
 * colours while it is typed, a gradient drifting through the letters with a soft halo, and settles back to plain
 * text a moment after the last keystroke. Attached to the stock editor instead of replacing its class.
 */
public final class TypingGlow implements TextWatcher, View.OnAttachStateChangeListener {
    private static final int[] SIRI = {0xFFFF6A88, 0xFFFFB86B, 0xFFB45CFF, 0xFF5E9BFF, 0xFFFF6A88};
    private static final int HALO = 0xB45CFF;
    private static final long SETTLE_MS = 900;
    private static final long SWEEP_MS = 1600;

    private final TextView mEditor;
    private final int[] mColors = new int[SIRI.length];
    private final Runnable mSettle = () -> fadeTo(0f);
    private float mGlow;
    private float mPhase;
    @Nullable
    private ValueAnimator mSweep;
    @Nullable
    private ValueAnimator mFade;

    public static void attach(TextView editor) {
        TypingGlow glow = new TypingGlow(editor);
        editor.addTextChangedListener(glow);
        editor.addOnAttachStateChangeListener(glow);
    }

    private TypingGlow(TextView editor) {
        mEditor = editor;
    }

    @Override
    public void onTextChanged(CharSequence text, int start, int before, int count) {
        // typed, not filled in from a tag (focus is then on the tag)
        if (mEditor.isAttachedToWindow() && mEditor.hasFocus() && (before > 0 || count > 0)) {
            ignite();
        }
    }

    @Override
    public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        // NOP
    }

    @Override
    public void afterTextChanged(Editable text) {
        // NOP
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        // NOP
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        stopGlow();
    }

    private void ignite() {
        mEditor.removeCallbacks(mSettle);
        mEditor.postDelayed(mSettle, SETTLE_MS);
        fadeTo(1f);
        if (mSweep == null) {
            mSweep = ValueAnimator.ofFloat(0f, 1f);
            mSweep.setDuration(SWEEP_MS);
            mSweep.setRepeatCount(ValueAnimator.INFINITE);
            mSweep.setInterpolator(new LinearInterpolator());
            mSweep.addUpdateListener(a -> {
                mPhase = a.getAnimatedFraction();
                applyGlow();
            });
            mSweep.start();
        }
    }

    private void fadeTo(float target) {
        if (mFade != null) {
            mFade.cancel();
        }
        mFade = ValueAnimator.ofFloat(mGlow, target);
        mFade.setDuration(target > 0f ? 150 : 600);
        mFade.addUpdateListener(a -> {
            mGlow = (float) a.getAnimatedValue();
            applyGlow();
        });
        if (target == 0f) {
            mFade.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled && mSweep != null) {
                        mSweep.cancel();
                        mSweep = null;
                    }
                }
            });
        }
        mFade.start();
    }

    private void stopGlow() {
        mEditor.removeCallbacks(mSettle);
        if (mSweep != null) {
            mSweep.cancel();
            mSweep = null;
        }
        if (mFade != null) {
            mFade.cancel();
            mFade = null;
        }
        mGlow = 0f;
        applyGlow();
    }

    /**
     * Paint the glow's frame. Through setShadowLayer: the editor caches its text drawing, and only a text-paint
     * setter like that one makes it draw the letters again (invalidate() does not).
     */
    private void applyGlow() {
        TextPaint paint = mEditor.getPaint();
        if (mGlow > 0f && mEditor.length() > 0 && mEditor.getWidth() > 0) {
            int base = mEditor.getCurrentTextColor();
            for (int i = 0; i < SIRI.length; i++) {
                mColors[i] = blend(base, SIRI[i], mGlow);
            }
            float width = mEditor.getWidth();
            float shift = mPhase * width * 2f + mEditor.getScrollX();
            paint.setShader(new LinearGradient(shift, 0, shift + width, 0, mColors, null, Shader.TileMode.MIRROR));
            float density = mEditor.getResources().getDisplayMetrics().density;
            mEditor.setShadowLayer(10f * density * mGlow, 0f, 0f,
                    Color.argb(Math.round(220 * mGlow), Color.red(HALO), Color.green(HALO), Color.blue(HALO)));
        } else if (paint.getShader() != null || mEditor.getShadowRadius() > 0f) {
            paint.setShader(null);
            mEditor.setShadowLayer(0f, 0f, 0f, 0);
        }
    }

    private static int blend(int from, int to, float amount) {
        float keep = 1f - amount;
        return Color.argb(255,
                Math.round(Color.red(from) * keep + Color.red(to) * amount),
                Math.round(Color.green(from) * keep + Color.green(to) * amount),
                Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }
}
