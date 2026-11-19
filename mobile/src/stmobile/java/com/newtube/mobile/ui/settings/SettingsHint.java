package com.newtube.mobile.ui.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.HashSet;
import java.util.Set;

/**
 * A setting's explanation behind a small i after its title (plain rows and picture tiles alike).
 * The i spins over and takes the accent, the row grows smoothly and the hint drops in from above.
 */
final class SettingsHint {
    /** Titles whose hint is open: kept while the app runs, so a page refresh leaves them open. */
    private static final Set<String> sOpen = new HashSet<>();

    private SettingsHint() {
    }

    /** Puts {@code title} in a line with an i after it (hidden until {@link #bind}); the title only shrinks when too long. */
    static ImageView attach(@NonNull TextView title) {
        Context c = title.getContext();
        ViewGroup parent = (ViewGroup) title.getParent();
        int index = parent.indexOfChild(title);
        parent.removeView(title);
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView info = icon(c);
        ((LinearLayout.LayoutParams) info.getLayoutParams()).setMarginStart(dp(c, 4));
        line.addView(info);
        parent.addView(line, index, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return info;
    }

    /** A small i on its own (hidden until {@link #bind}), with LinearLayout params. */
    static ImageView icon(@NonNull Context c) {
        ImageView info = new ImageView(c);
        info.setImageResource(R.drawable.ic_settings_info);
        info.setContentDescription(c.getString(R.string.settings_what_this_does));
        int pad = dp(c, 5);
        info.setPadding(pad, pad, pad, pad);
        info.setVisibility(View.GONE);
        info.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 26), dp(c, 26)));
        return info;
    }

    /**
     * Shows {@code text} in {@code summary}: right away when it is not a hint (a value), behind
     * {@code info} when it is.
     */
    static void bind(@NonNull ImageView info, @NonNull TextView summary, @Nullable CharSequence text, boolean hint,
                     @NonNull String key) {
        boolean hasText = text != null && text.length() > 0;
        boolean isHint = hint && hasText;
        boolean open = isHint && sOpen.contains(key);
        cancel(summary);
        summary.animate().cancel();
        ViewGroup.LayoutParams lp = summary.getLayoutParams();
        if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            summary.setLayoutParams(lp);
        }
        summary.setAlpha(1f);
        summary.setTranslationY(0f);
        if (hasText) {
            summary.setText(text);
        }
        summary.setVisibility(hasText && (!isHint || open) ? View.VISIBLE : View.GONE);
        info.setVisibility(isHint ? View.VISIBLE : View.GONE);
        info.animate().cancel();
        info.setRotation(0f);
        info.setScaleX(open ? 1.15f : 1f);
        info.setScaleY(open ? 1.15f : 1f);
        tint(info, open);
        glow(info, open, false);
        info.setOnClickListener(isHint ? v -> toggle(info, summary, key) : null);
    }

    private static void tint(ImageView info, boolean on) {
        Context c = info.getContext();
        int color = ContextCompat.getColor(c, R.color.mobile_color_on_surface_secondary);
        if (on) {
            color = ContextCompat.getColor(c, R.color.mobile_color_primary); // the theme's colour, as picked tiles
        }
        info.setImageTintList(ColorStateList.valueOf(color));
    }

    /**
     * The i's glass glow: a soft accent light inside a faint glass ring, in place of a press ripple.
     * {@code pulse} (a tap) flares it and lets it fade out.
     */
    static void glow(ImageView info, boolean on, boolean pulse) {
        android.content.Context c = info.getContext();
        android.graphics.drawable.GradientDrawable glow;
        if (info.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
            glow = (android.graphics.drawable.GradientDrawable) info.getBackground();
        } else {
            int a = ContextCompat.getColor(c, R.color.mobile_color_primary) & 0xFFFFFF;
            float density = c.getResources().getDisplayMetrics().density;
            glow = new android.graphics.drawable.GradientDrawable();
            glow.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
            glow.setGradientRadius(13 * density);
            glow.setColors(new int[] {0x66000000 | a, 0x2E000000 | a, 0x00000000 | a}); // fades to nothing: no edge to cut
            glow.setAlpha(0);
            info.setBackground(glow);
        }
        Object old = info.getTag(R.id.settings_row_icon);
        if (old instanceof android.animation.ValueAnimator) {
            ((android.animation.ValueAnimator) old).cancel();
        }
        int rest = 0; // a flash on tap only: the open i shows by its colour
        if (!pulse) {
            glow.setAlpha(rest);
            return;
        }
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(glow.getAlpha(), 255, rest);
        anim.setDuration(620);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(v -> glow.setAlpha((int) v.getAnimatedValue()));
        info.setTag(R.id.settings_row_icon, anim);
        anim.start();
    }

    private static void cancel(View summary) {
        Object running = summary.getTag(R.id.settings_row_summary);
        if (running instanceof ValueAnimator) {
            ((ValueAnimator) running).cancel();
        }
    }

    private static void toggle(ImageView info, TextView summary, String key) {
        boolean on = !sOpen.remove(key);
        if (on) sOpen.add(key);
        Context c = info.getContext();
        Haptics.click(info);
        info.animate().rotationBy(on ? 360f : -360f).scaleX(on ? 1.15f : 1f).scaleY(on ? 1.15f : 1f)
                .setDuration(480).setInterpolator(new OvershootInterpolator(1.6f)).start();
        tint(info, on);
        glow(info, on, true);
        cancel(summary);
        int width = ((View) summary.getParent()).getWidth();
        summary.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int full = summary.getMeasuredHeight();
        int from = summary.getVisibility() == View.VISIBLE ? summary.getHeight() : 0;
        ViewGroup.LayoutParams lp = summary.getLayoutParams();
        lp.height = from;
        summary.setLayoutParams(lp);
        summary.setVisibility(View.VISIBLE);
        if (on) {
            summary.setAlpha(0f);
            summary.setTranslationY(-dp(c, 10));
            summary.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            summary.animate().alpha(0f).translationY(-dp(c, 6)).setStartDelay(0).setDuration(140).start();
        }
        ValueAnimator anim = ValueAnimator.ofInt(from, on ? full : 0);
        summary.setTag(R.id.settings_row_summary, anim);
        anim.setDuration(300);
        if (!on) anim.setStartDelay(100);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            summary.setLayoutParams(lp);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                summary.setTag(R.id.settings_row_summary, null);
                if (mCancelled) return;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                summary.setLayoutParams(lp);
                if (!on) {
                    summary.setVisibility(View.GONE);
                    summary.setTranslationY(0f);
                }
            }
        });
        anim.start();
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
