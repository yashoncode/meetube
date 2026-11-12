package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Accent;
import com.newtube.mobile.ui.common.GlowProgressBar;
import com.newtube.mobile.ui.common.ProgressColor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * MEETUBE(accent): the accent colour palette, one sheet. The app's accent, then the Player and the
 * Shorts progress lines, each from the same named, slightly gradient palette; a tap applies at once.
 * A new accent rebuilds the screens (their theme carries it) when the sheet closes.
 */
final class ProgressColorSheet {
    private ProgressColorSheet() {
    }

    static void show(SettingsPageFragment page) {
        Context context = page.requireContext();
        int accentBefore = Accent.key(context);
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        Context c = sheet.getContext();
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(dp, 20), px(dp, 20), px(dp, 20), px(dp, 28));
        content.addView(text(c, R.string.mobile_settings_progress_color, 20, true));
        List<Runnable> syncs = new ArrayList<>(); // a new accent changes the Player's "Match accent" dot

        section(content, dp, syncs, R.string.mobile_settings_accent, false, 0, null,
                () -> Accent.index(c), v -> Accent.set(c, v));
        section(content, dp, syncs, R.string.mobile_settings_progress_color_player, true,
                R.string.mobile_settings_progress_color_match, () -> ProgressColor.videoColors(c),
                () -> ProgressColor.video(c), v -> ProgressColor.setVideo(c, v));
        section(content, dp, syncs, R.string.mobile_settings_progress_color_shorts, true,
                R.string.mobile_settings_progress_color_default, () -> new int[] {Color.WHITE, Color.WHITE},
                () -> ProgressColor.shorts(c), v -> ProgressColor.setShorts(c, v));

        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(content);
        sheet.setContentView(scroll);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.setOnDismissListener(dialog -> {
            if (Accent.key(context) != accentBefore && page.getActivity() != null) {
                page.getActivity().recreate(); // the accent is in the theme
            } else {
                page.rebuild();
            }
        });
        page.showDialog(sheet);
        sheet.show();
    }

    /** "Ember", the row's summary: the accent's name. */
    static CharSequence summary(Context context) {
        int i = Accent.index(context);
        return i == Accent.CUSTOM ? context.getString(R.string.mobile_accent_custom) + String.format(" #%06X", Accent.custom(context) & 0xFFFFFF)
                : context.getString(Accent.NAMES[i]);
    }

    /**
     * A heading, the pick's name, a preview line for a progress line, and the dots. With
     * {@code withDefault}, a first dot for the line's own look ({@code defaultName}, {@code defaultColors}).
     */
    private static void section(LinearLayout content, float dp, List<Runnable> syncs, int label,
                                boolean withDefault, int defaultName, @Nullable Supplier<int[]> defaultColors,
                                IntSupplier current, IntConsumer pick) {
        Context c = content.getContext();
        TextView heading = text(c, label, 16, true);
        heading.setPadding(0, px(dp, 22), 0, 0);
        content.addView(heading);
        TextView chosen = text(c, 0, 13, false);
        chosen.setTextColor(ContextCompat.getColor(c, R.color.mobile_color_on_surface_secondary));
        content.addView(chosen);

        GlowProgressBar line = null;
        if (withDefault) { // a progress line: show it as it will look, on a dark strip, 60% played
            FrameLayout stage = new FrameLayout(c);
            GradientDrawable dark = new GradientDrawable();
            dark.setColor(0xFF1A1A1A);
            dark.setCornerRadius(10 * dp);
            stage.setBackground(dark);
            line = new GlowProgressBar(c, null);
            line.setMax(100);
            line.setProgress(60);
            line.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
            FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(-1, px(dp, 3), Gravity.CENTER_VERTICAL);
            lineLp.leftMargin = lineLp.rightMargin = px(dp, 16);
            stage.addView(line, lineLp);
            LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, px(dp, 36));
            stageLp.topMargin = px(dp, 10);
            content.addView(stage, stageLp);
        }

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout dots = new LinearLayout(c);
        scroll.addView(dots);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        scrollLp.topMargin = px(dp, 12);
        content.addView(scroll, scrollLp);

        int first = withDefault ? ProgressColor.DEFAULT : 0; // dot k is value first + k
        int count = withDefault ? Accent.CUSTOM + 1 : Accent.START.length; // Custom: the accent's only
        int[] values = new int[count]; // dot k's value; the accent's Custom comes first, where it is seen
        for (int k = 0; k < count; k++) {
            values[k] = withDefault ? first + k : k == 0 ? Accent.CUSTOM : k - 1;
        }
        GradientDrawable[] circles = new GradientDrawable[count];
        ImageView[] checks = new ImageView[count];
        GlowProgressBar preview = line;
        Runnable sync = () -> {
            int value = current.getAsInt();
            for (int k = 0; k < count; k++) {
                int[] colors = colorsOf(values[k], defaultColors);
                boolean rainbow = values[k] == Accent.CUSTOM && value != Accent.CUSTOM; // "any colour" until picked
                circles[k].setGradientType(rainbow ? GradientDrawable.SWEEP_GRADIENT : GradientDrawable.LINEAR_GRADIENT);
                circles[k].setColors(rainbow ? new int[] {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                        0xFF0000FF, 0xFFFF00FF, 0xFFFF0000} : colors);
                checks[k].setColorFilter(Color.luminance(colors[0]) > 0.6f ? Color.BLACK : Color.WHITE);
                checks[k].setVisibility(values[k] == value ? View.VISIBLE : View.GONE);
            }
            chosen.setText(value == ProgressColor.DEFAULT && withDefault ? c.getString(defaultName)
                    : c.getString(Accent.NAMES[value]));
            if (preview != null) {
                preview.setProgressTintList(ColorStateList.valueOf(colorsOf(value, defaultColors)[0]));
                preview.invalidate();
            }
        };
        for (int k = 0; k < count; k++) {
            int value = values[k];
            FrameLayout dot = new FrameLayout(c);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setOrientation(GradientDrawable.Orientation.TL_BR); // the palette's slight gradient
            circle.setStroke(px(dp, 1), 0x33808080); // a light dot shows on a light sheet
            dot.setBackground(circle);
            circles[k] = circle;
            ImageView check = new ImageView(c);
            check.setImageResource(R.drawable.ic_mobile_check);
            dot.addView(check, new FrameLayout.LayoutParams(px(dp, 22), px(dp, 22), Gravity.CENTER));
            checks[k] = check;
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(px(dp, 40), px(dp, 40));
            dotLp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(dot, dotLp);
            TextView caption = text(c, value == ProgressColor.DEFAULT ? defaultName : Accent.NAMES[value], 11, false);
            caption.setTextColor(ContextCompat.getColor(c, R.color.mobile_color_on_surface_secondary));
            caption.setPadding(0, px(dp, 4), 0, 0);
            caption.setGravity(Gravity.CENTER);
            caption.setSingleLine(true);
            item.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            item.setOnClickListener(v -> {
                if (value == Accent.CUSTOM) {
                    ColorPickerSheet.show(c, Accent.custom(c), color -> {
                        Accent.setCustom(c, color);
                        for (Runnable each : syncs) {
                            each.run();
                        }
                    });
                    return;
                }
                pick.accept(value);
                for (Runnable each : syncs) {
                    each.run();
                }
            });
            dots.addView(item, new LinearLayout.LayoutParams(px(dp, 72), -2));
        }
        syncs.add(sync);
        sync.run();
    }

    private static int[] colorsOf(int value, @Nullable Supplier<int[]> defaultColors) {
        return value == ProgressColor.DEFAULT && defaultColors != null ? defaultColors.get()
                : new int[] {Accent.START[value], Accent.END[value]};
    }

    private static TextView text(Context c, int res, float sp, boolean bold) {
        TextView view = new TextView(c);
        if (res != 0) {
            view.setText(res);
        }
        view.setTextSize(sp);
        view.setTextColor(ContextCompat.getColor(c, R.color.mobile_color_on_surface));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static int px(float dp, int value) {
        return Math.round(value * dp);
    }
}
