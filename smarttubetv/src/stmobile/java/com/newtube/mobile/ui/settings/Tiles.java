package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.slider.Slider;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.Locale;

/**
 * METUBE(customize): Customization's pickers drawn on the page instead of behind a sheet: a choice
 * becomes a strip of tiles, each a little picture of what it does (a phone in that theme, Home as a
 * feed or as rows, the app icon itself), and a tap picks it with a bounce. Interface size is a
 * slider with a live preview. The row keeps its choice's title and options, so search still finds
 * it and glows it.
 */
final class Tiles {
    // What a tile's picture shows.
    static final int THEME_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;
    static final int FEED = 3;
    static final int SHELVES = 4;
    static final int LIST = 5;
    static final int GRID = 6;
    static final int THUMB_DEFAULT = 7;
    static final int THUMB_START = 8;
    static final int THUMB_MIDDLE = 9;
    static final int THUMB_END = 10;
    /** The option's own icon (the app icon picker). */
    static final int ICON = 11;

    private Tiles() {
    }

    /** {@code choice} (a bound choice row) as tiles {@code heightDp} tall, a picture per option. */
    static SettingsRow of(@NonNull SettingsRow choice, int heightDp, int... pictures) {
        SettingsRow row = choice.asCustom();
        row.custom = (parent, page) -> strip(parent, page, row, heightDp, pictures);
        return row;
    }

    private static View strip(ViewGroup parent, SettingsAdapter.Listener page, SettingsRow row, int heightDp, int[] pictures) {
        Context context = parent.getContext();
        LinearLayout root = column(context);
        TextView summary = heading(root, row.title);

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        root.setClipChildren(false);
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stripParams.topMargin = dp(context, 12);
        root.addView(strip, stripParams);

        int count = row.options.size();
        for (int i = 0; i < count; i++) {
            SettingsRow.Option option = row.options.get(i);
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setContentDescription(option.label);

            FrameLayout frame = new FrameLayout(context);
            ImageView picture = new ImageView(context);
            int picturePad = dp(context, pictures[i] == ICON ? 14 : 8);
            picture.setPadding(picturePad, picturePad, picturePad, picturePad);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.setImageDrawable(pictures[i] == ICON ? ContextCompat.getDrawable(context, option.icon)
                    : new Picture(context, pictures[i]));
            frame.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.ic_mobile_check);
            check.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            int checkPad = dp(context, 3);
            check.setPadding(checkPad, checkPad, checkPad, checkPad);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(ContextCompat.getColor(context, R.color.mobile_color_primary));
            check.setBackground(badge);
            FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(context, 20), dp(context, 20),
                    Gravity.TOP | Gravity.END);
            checkParams.setMargins(0, dp(context, 6), dp(context, 6), 0);
            frame.addView(check, checkParams);
            tile.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));

            TextView label = new TextView(context);
            label.setText(option.label);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(context, 6);
            tile.addView(label, labelParams);

            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tileParams.setMarginStart(i == 0 ? 0 : dp(context, 5));
            tileParams.setMarginEnd(i == count - 1 ? 0 : dp(context, 5));
            strip.addView(tile, tileParams);

            int which = i;
            tile.setOnClickListener(v -> {
                bounce(frame);
                if (which == row.currentIndex()) {
                    Haptics.tick(v);
                    return;
                }
                Haptics.click(v);
                select(strip, which, row, summary, true);
                // After the bounce: a theme change redraws every screen.
                v.postDelayed(() -> {
                    row.pick.pick(option.value);
                    if (row.restart && page instanceof SettingsPageFragment) {
                        ((SettingsPageFragment) page).offerRestart();
                    }
                }, 160);
            });
        }
        select(strip, row.currentIndex(), row, summary, false);
        return root;
    }

    /** Lights the picked tile: a red ring, a check that pops in, its name in red. */
    private static void select(LinearLayout strip, int index, SettingsRow row, TextView summary, boolean animate) {
        Context context = strip.getContext();
        for (int i = 0; i < strip.getChildCount(); i++) {
            ViewGroup tile = (ViewGroup) strip.getChildAt(i);
            FrameLayout frame = (FrameLayout) tile.getChildAt(0);
            TextView label = (TextView) tile.getChildAt(1);
            View check = frame.getChildAt(1);
            boolean on = i == index;
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(context, 16));
            background.setColor(ContextCompat.getColor(context, R.color.mobile_color_card));
            background.setStroke(dp(context, on ? 2 : 1), ContextCompat.getColor(context,
                    on ? R.color.mobile_color_primary : R.color.mobile_color_divider));
            frame.setBackground(background);
            label.setTextColor(ContextCompat.getColor(context,
                    on ? R.color.mobile_color_primary : R.color.mobile_color_on_surface_secondary));
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            tile.setSelected(on);
            check.animate().cancel();
            if (on && animate) {
                check.setVisibility(View.VISIBLE);
                check.setScaleX(0f);
                check.setScaleY(0f);
                check.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator(3f)).start();
            } else {
                check.setScaleX(1f);
                check.setScaleY(1f);
                check.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        }
        CharSequence description = index >= 0 ? row.options.get(index).description : null;
        summary.setText(description);
        summary.setVisibility(description != null ? View.VISIBLE : View.GONE);
    }

    private static void bounce(View view) {
        view.animate().cancel();
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(() ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(new OvershootInterpolator(3f)).start())
                .start();
    }

    /** Interface size as a slider: the preview below grows and shrinks as it moves; saved on release. */
    static SettingsRow slider(@NonNull SettingsRow choice, @NonNull MainUIData data) {
        SettingsRow row = choice.asCustom();
        row.custom = (parent, page) -> {
            Context context = parent.getContext();
            LinearLayout root = column(context);
            TextView value = heading(root, row.title);
            float stored = data.getUIScale();
            int start = Math.max(80, Math.min(130, Math.round(stored * 20) * 5));
            value.setText(percent(start));
            value.setVisibility(View.VISIBLE);

            // Sizes relative to now: this screen is already drawn at the stored scale.
            LinearLayout preview = new LinearLayout(context);
            preview.setOrientation(LinearLayout.HORIZONTAL);
            preview.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(context, 12);
            preview.setPadding(pad, pad, pad, pad);
            GradientDrawable card = new GradientDrawable();
            card.setCornerRadius(dp(context, 16));
            card.setColor(ContextCompat.getColor(context, R.color.mobile_color_card));
            preview.setBackground(card);
            ImageView thumb = new ImageView(context);
            thumb.setImageDrawable(new Picture(context, THUMB_DEFAULT));
            preview.addView(thumb, new LinearLayout.LayoutParams(dp(context, 96), dp(context, 54)));
            LinearLayout lines = new LinearLayout(context);
            lines.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(context);
            title.setText(R.string.mobile_settings_interface_size_sample);
            title.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
            title.setMaxLines(2);
            TextView meta = new TextView(context);
            meta.setText(R.string.mobile_settings_interface_size_sample_meta);
            meta.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
            lines.addView(title);
            lines.addView(meta);
            LinearLayout.LayoutParams linesParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            linesParams.setMarginStart(dp(context, 12));
            preview.addView(lines, linesParams);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            previewParams.topMargin = dp(context, 12);
            root.addView(preview, previewParams);

            Slider slider = new Slider(context);
            slider.setValueFrom(80);
            slider.setValueTo(130);
            slider.setStepSize(5);
            slider.setValue(start);
            slider.setLabelFormatter(v -> percent(Math.round(v)));
            slider.setContentDescription(row.title);
            root.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            Runnable[] resize = new Runnable[1];
            resize[0] = () -> {
                float k = slider.getValue() / (stored * 100f);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15 * k);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * k);
                thumb.getLayoutParams().width = Math.round(dp(context, 96) * k);
                thumb.getLayoutParams().height = Math.round(dp(context, 54) * k);
                thumb.requestLayout();
                value.setText(percent(Math.round(slider.getValue())));
            };
            resize[0].run();
            slider.addOnChangeListener((s, v, fromUser) -> {
                if (fromUser) {
                    Haptics.tick(s);
                    resize[0].run();
                }
            });
            slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider s) {
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider s) {
                    float scale = s.getValue() / 100f;
                    if (Math.abs(scale - data.getUIScale()) > 0.001f) {
                        data.setUIScale(scale);
                        if (page instanceof SettingsPageFragment) {
                            ((SettingsPageFragment) page).offerRestart();
                        }
                    }
                }
            });
            return root;
        };
        return row;
    }

    private static String percent(int value) {
        return String.format(Locale.US, "%d%%", value);
    }

    private static LinearLayout column(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.mobile_settings_row_inset);
        root.setPadding(inset, dp(context, 12), inset, dp(context, 12));
        return root;
    }

    /** The row's title like any row's; returns the summary line under it (hidden until set). */
    private static TextView heading(LinearLayout root, CharSequence text) {
        Context context = root.getContext();
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(R.dimen.mobile_settings_row_title_size));
        root.addView(title);
        TextView summary = new TextView(context);
        summary.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(R.dimen.mobile_settings_row_summary_size));
        summary.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 2);
        root.addView(summary, params);
        return summary;
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /**
     * A tile's little picture, drawn from rounded blocks: a phone screen with Home's feed, its rows
     * or a list on it (in a given theme's colours), or a thumbnail with where its frame comes from.
     */
    private static final class Picture extends Drawable {
        private static final int ACCENT = 0xFFFF453A;
        private final int mKind;
        private final int mScreen;
        private final int mBlock;
        private final int mLine;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mPath = new Path();

        Picture(Context context, int kind) {
            mKind = kind;
            boolean dark = kind == THEME_DARK || kind != THEME_LIGHT && kind != THEME_SYSTEM
                    && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            mScreen = dark ? 0xFF0F0F0F : 0xFFFFFFFF;
            mBlock = dark ? 0xFF2C2C30 : 0xFFE3E3E3;
            mLine = dark ? 0xFF404046 : 0xFFCFCFCF;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return; // the loops below step by the height
            }
            if (mKind >= THUMB_DEFAULT) {
                thumbnail(canvas, new RectF(b));
                return;
            }
            float h = b.height();
            float w = Math.min(b.width(), h * 0.58f);
            RectF phone = new RectF(b.exactCenterX() - w / 2, b.top, b.exactCenterX() + w / 2, b.bottom);
            if (mKind == THEME_SYSTEM) {
                // Half light, half dark, cut on the diagonal.
                phone(canvas, phone, THEME_LIGHT, 0xFFFFFFFF, 0xFFE3E3E3, 0xFFCFCFCF);
                canvas.save();
                mPath.reset();
                mPath.moveTo(phone.right, phone.top);
                mPath.lineTo(phone.right, phone.bottom);
                mPath.lineTo(phone.left, phone.bottom);
                mPath.close();
                canvas.clipPath(mPath);
                phone(canvas, phone, THEME_DARK, 0xFF0F0F0F, 0xFF2C2C30, 0xFF404046);
                canvas.restore();
            } else {
                phone(canvas, phone, mKind, mScreen, mBlock, mLine);
            }
        }

        private void phone(Canvas canvas, RectF phone, int kind, int screen, int block, int line) {
            float w = phone.width();
            float h = phone.height();
            float corner = w * 0.16f;
            mPath.reset();
            mPath.addRoundRect(phone, corner, corner, Path.Direction.CW);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(screen);
            canvas.drawPath(mPath, mPaint);
            canvas.save();
            canvas.clipPath(mPath);

            float p = w * 0.1f;
            float l = phone.left + p;
            float r = phone.right - p;
            float y = phone.top + p;
            block(canvas, l, y, l + w * 0.3f, y + h * 0.04f, ACCENT); // the logo
            y += h * 0.09f;
            switch (kind) {
                case SHELVES:
                    while (y < phone.bottom) {
                        block(canvas, l, y, l + w * 0.4f, y + h * 0.03f, line);
                        y += h * 0.055f;
                        float cell = w * 0.42f;
                        for (float x = l; x < phone.right; x += cell + w * 0.06f) {
                            block(canvas, x, y, x + cell, y + h * 0.13f, block);
                        }
                        y += h * 0.18f;
                    }
                    break;
                case LIST:
                    while (y < phone.bottom) {
                        float thumbRight = l + (r - l) * 0.45f;
                        block(canvas, l, y, thumbRight, y + h * 0.09f, block);
                        block(canvas, thumbRight + w * 0.06f, y + h * 0.01f, r, y + h * 0.035f, line);
                        block(canvas, thumbRight + w * 0.06f, y + h * 0.05f, r - w * 0.15f, y + h * 0.075f, line);
                        y += h * 0.13f;
                    }
                    break;
                case GRID:
                    float cell = (r - l - w * 0.08f) / 2;
                    for (int i = 0; y < phone.bottom; i++) {
                        float x = i % 2 == 0 ? l : l + cell + w * 0.08f;
                        block(canvas, x + cell * 0.12f, y, x + cell * 0.88f, y + h * 0.02f, line); // the stack behind
                        block(canvas, x, y + h * 0.03f, x + cell, y + h * 0.16f, block);
                        block(canvas, x, y + h * 0.18f, x + cell * 0.7f, y + h * 0.205f, line);
                        if (i % 2 == 1) {
                            y += h * 0.26f;
                        }
                    }
                    break;
                default: // the feed, also what the theme tiles show
                    while (y < phone.bottom) {
                        block(canvas, l, y, r, y + h * 0.24f, block);
                        y += h * 0.27f;
                        block(canvas, l, y, r - w * 0.15f, y + h * 0.03f, line);
                        y += h * 0.05f;
                        block(canvas, l, y, l + (r - l) * 0.5f, y + h * 0.03f, line);
                        y += h * 0.08f;
                    }
                    break;
            }
            // The floating bar, Home lit.
            float barTop = phone.bottom - p - h * 0.085f;
            mPaint.setColor(screen);
            mRect.set(l - p * 0.3f, barTop - p * 0.3f, r + p * 0.3f, phone.bottom - p * 0.7f);
            canvas.drawRoundRect(mRect, mRect.height() / 2, mRect.height() / 2, mPaint);
            block(canvas, l, barTop, r, phone.bottom - p, block);
            float dot = h * 0.022f;
            float cy = (barTop + phone.bottom - p) / 2;
            for (int i = 0; i < 4; i++) {
                mPaint.setColor(i == 0 ? ACCENT : line);
                canvas.drawCircle(l + (r - l) * (i + 0.5f) / 4, cy, dot, mPaint);
            }
            canvas.restore();

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1f, w * 0.015f));
            mPaint.setColor(line);
            canvas.drawPath(mPath, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }

        /** A landscape thumbnail: hills under a sun, and where along the video its frame is from. */
        private void thumbnail(Canvas canvas, RectF b) {
            float h = Math.min(b.height(), b.width() * 0.5625f);
            RectF t = new RectF(b.left, b.centerY() - h / 2, b.right, b.centerY() + h / 2);
            float corner = h * 0.14f;
            mPath.reset();
            mPath.addRoundRect(t, corner, corner, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mPath);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mBlock);
            canvas.drawRect(t, mPaint);
            float position = mKind == THUMB_START ? 0.12f : mKind == THUMB_MIDDLE ? 0.5f : mKind == THUMB_END ? 0.88f : 0.7f;
            mPaint.setColor(0xFFFFB300);
            canvas.drawCircle(t.left + t.width() * position, t.top + h * 0.3f, h * 0.13f, mPaint);
            mPaint.setColor(mLine);
            Path hills = new Path();
            hills.moveTo(t.left, t.bottom);
            hills.lineTo(t.left + t.width() * 0.3f, t.top + h * 0.5f);
            hills.lineTo(t.left + t.width() * 0.5f, t.top + h * 0.7f);
            hills.lineTo(t.left + t.width() * 0.72f, t.top + h * 0.42f);
            hills.lineTo(t.right, t.bottom);
            hills.close();
            canvas.drawPath(hills, mPaint);
            if (mKind == THUMB_DEFAULT) {
                // The uploader's own: a title card's bold lettering.
                block(canvas, t.left + t.width() * 0.08f, t.top + h * 0.62f, t.left + t.width() * 0.6f, t.top + h * 0.8f, ACCENT);
            } else {
                float track = t.bottom - h * 0.06f;
                mPaint.setColor(0x66FFFFFF);
                canvas.drawRect(t.left, track, t.right, t.bottom, mPaint);
                mPaint.setColor(ACCENT);
                canvas.drawRect(t.left, track, t.left + t.width() * position, t.bottom, mPaint);
                canvas.drawCircle(t.left + t.width() * position, track, h * 0.08f, mPaint);
            }
            canvas.restore();
        }

        private void block(Canvas canvas, float left, float top, float right, float bottom, int color) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            mRect.set(left, top, right, bottom);
            float radius = Math.min(mRect.height(), mRect.width()) * 0.3f;
            canvas.drawRoundRect(mRect, radius, radius, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
