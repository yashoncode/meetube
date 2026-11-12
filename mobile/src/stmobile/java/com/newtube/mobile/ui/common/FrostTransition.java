package com.newtube.mobile.ui.common;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.transition.TransitionValues;
import androidx.transition.Visibility;

/** MEETUBE(frost): settings pages fade through the same frost as Home's tabs. */
public final class FrostTransition extends Visibility {
    @Nullable
    @Override
    public Animator onAppear(@NonNull ViewGroup sceneRoot, @NonNull View view,
            @Nullable TransitionValues startValues, @Nullable TransitionValues endValues) {
        return animator(view, true);
    }

    @Nullable
    @Override
    public Animator onDisappear(@NonNull ViewGroup sceneRoot, @NonNull View view,
            @Nullable TransitionValues startValues, @Nullable TransitionValues endValues) {
        return animator(view, false);
    }

    private Animator animator(View view, boolean appearing) {
        float radius = Motion.FROST_BLUR_DP * view.getResources().getDisplayMetrics().density;
        float from = appearing ? 0f : 1f;
        apply(view, from, radius);
        ValueAnimator animator = ValueAnimator.ofFloat(from, appearing ? 1f : 0f);
        animator.setDuration(appearing ? Motion.FROST_MS : Math.round(Motion.FROST_MS * 0.6f));
        animator.setInterpolator(appearing ? Motion.EMPHASIZED_DECELERATE : Motion.EMPHASIZED_ACCELERATE);
        animator.addUpdateListener(a -> apply(view, (float) a.getAnimatedValue(), radius));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                reset(view);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                reset(view);
            }
        });
        return animator;
    }

    private static void apply(View view, float alpha, float radius) {
        view.setAlpha(alpha);
        if (Build.VERSION.SDK_INT >= 31) {
            float blur = radius * (1f - alpha);
            view.setRenderEffect(blur < 0.5f ? null : RenderEffect.createBlurEffect(
                    blur, blur, Shader.TileMode.CLAMP));
        }
    }

    private static void reset(View view) {
        view.setAlpha(1f);
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(null);
        }
    }
}
