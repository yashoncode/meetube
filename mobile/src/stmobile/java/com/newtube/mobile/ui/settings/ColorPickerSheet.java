package com.newtube.mobile.ui.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * MEETUBE(accent): any colour. A live preview bubble with the hex code, a saturation / brightness
 * square and a hue slider whose thumbs carry the colour and grow while dragged, a grid of quick
 * picks, a hex field (type or paste), and one big Use colour button tinted with the pick.
 */
final class ColorPickerSheet {
    /** Quick picks: a spread around the wheel, then softer and deeper ones. */
    private static final int[] SWATCHES = {
            0xFFFF3B30, 0xFFFF6B00, 0xFFFFB800, 0xFFFFE600, 0xFF9BE15D, 0xFF00C853, 0xFF00BFA5, 0xFF00B8D4,
            0xFF2979FF, 0xFF3D5AFE, 0xFF7C4DFF, 0xFFD500F9, 0xFFFF4081, 0xFFFF8A80, 0xFF8D6E63, 0xFF90A4AE};

    private ColorPickerSheet() {
    }

    static void show(Context context, int initial, IntConsumer onPick) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        Context c = sheet.getContext();
        float dp = c.getResources().getDisplayMetrics().density;
        int onSurface = ContextCompat.getColor(c, R.color.mobile_color_on_surface);
        int secondary = ContextCompat.getColor(c, R.color.mobile_color_on_surface_secondary);
        float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);

        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(dp, 20), px(dp, 8), px(dp, 20), px(dp, 24));

        // Header: the title and hint, the live colour bubble on the right
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(c, c.getString(R.string.mobile_accent_custom_title), 22, true, onSurface);
        titles.addView(title);
        TextView hint = text(c, c.getString(R.string.mobile_accent_custom_hint), 13, false, secondary);
        hint.setPadding(0, px(dp, 2), 0, 0);
        titles.addView(hint);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        View bubble = new View(c);
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubble.setBackground(bubbleBg);
        bubble.setElevation(6 * dp);
        header.addView(bubble, new LinearLayout.LayoutParams(px(dp, 52), px(dp, 52)));
        content.addView(header);

        SvSquare square = new SvSquare(c, hsv, dp);
        LinearLayout.LayoutParams squareLp = new LinearLayout.LayoutParams(-1, px(dp, 210));
        squareLp.topMargin = px(dp, 18);
        content.addView(square, squareLp);
        HueBar hue = new HueBar(c, hsv, dp);
        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(-1, px(dp, 36));
        hueLp.topMargin = px(dp, 16);
        content.addView(hue, hueLp);

        // Quick picks: 8 a row, the picked one ringed
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(8);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = px(dp, 18);
        content.addView(grid, gridLp);
        GradientDrawable[] swatchBgs = new GradientDrawable[SWATCHES.length];

        // Hex pill and the button
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = px(dp, 20);
        content.addView(row, rowLp);
        EditText hex = new EditText(c);
        hex.setSingleLine(true);
        hex.setTextSize(16);
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setTextColor(onSurface);
        hex.setGravity(Gravity.CENTER);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[] {new InputFilter.LengthFilter(7)});
        GradientDrawable hexBg = new GradientDrawable();
        hexBg.setCornerRadius(24 * dp);
        hexBg.setColor(withAlpha(onSurface, 0x14));
        hex.setBackground(hexBg);
        hex.setPadding(px(dp, 14), 0, px(dp, 14), 0);
        row.addView(hex, new LinearLayout.LayoutParams(px(dp, 124), px(dp, 48)));
        MaterialButton use = new MaterialButton(c);
        use.setText(R.string.mobile_accent_custom_use);
        use.setAllCaps(false);
        use.setTextSize(16);
        use.setCornerRadius(px(dp, 24));
        use.setInsetTop(0);
        use.setInsetBottom(0);
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(0, px(dp, 48), 1);
        useLp.leftMargin = px(dp, 12);
        row.addView(use, useLp);

        boolean[] typing = {false};
        Runnable sync = () -> {
            int color = Color.HSVToColor(hsv);
            bubbleBg.setColor(color);
            bubbleBg.setStroke(px(dp, 3), withAlpha(Color.WHITE, 0xCC));
            use.setBackgroundTintList(ColorStateList.valueOf(color));
            use.setTextColor(Color.luminance(color) > 0.5f ? Color.BLACK : Color.WHITE);
            for (int i = 0; i < SWATCHES.length; i++) {
                swatchBgs[i].setStroke(px(dp, (SWATCHES[i] & 0xFFFFFF) == (color & 0xFFFFFF) ? 3 : 0), onSurface);
            }
            square.invalidate();
            hue.invalidate();
            if (!typing[0]) {
                hex.setText(String.format(Locale.US, "#%06X", color & 0xFFFFFF));
            }
        };
        square.onChange = sync;
        hue.onChange = sync;

        int cell = px(dp, 34);
        for (int i = 0; i < SWATCHES.length; i++) {
            int color = SWATCHES[i];
            View swatch = new View(c);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            swatch.setBackground(bg);
            swatchBgs[i] = bg;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(i / 8), GridLayout.spec(i % 8, 1f));
            lp.width = cell;
            lp.height = cell;
            lp.setGravity(Gravity.CENTER);
            lp.topMargin = i < 8 ? 0 : px(dp, 10);
            grid.addView(swatch, lp);
            swatch.setOnClickListener(v -> {
                Haptics.click(v);
                Color.colorToHSV(color, hsv);
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                        .withEndAction(() -> v.animate().scaleX(1).scaleY(1).setDuration(160).start()).start();
                sync.run();
            });
        }

        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String text = s.toString().trim().replace("#", "");
                if (text.length() != 6 || typing[0]) {
                    return;
                }
                try {
                    Color.colorToHSV(0xFF000000 | Integer.parseInt(text, 16), hsv);
                } catch (NumberFormatException e) {
                    return;
                }
                typing[0] = true;
                sync.run();
                typing[0] = false;
            }
        });
        use.setOnClickListener(v -> {
            Haptics.click(v);
            onPick.accept(Color.HSVToColor(hsv));
            sheet.dismiss();
        });
        sync.run();

        sheet.setContentView(content);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setDraggable(false); // dragging in the square must not pull the sheet down
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
    }

    /** Saturation left to right, brightness bottom to top, at the current hue. */
    private static final class SvSquare extends View {
        private final float[] mHsv;
        private final float mDp;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Thumb mThumb;
        private int mShaderHue = -1;
        Runnable onChange;

        SvSquare(Context context, float[] hsv, float dp) {
            super(context);
            mHsv = hsv;
            mDp = dp;
            mThumb = new Thumb(this, dp);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (mShaderHue != (int) mHsv[0] || mPaint.getShader() == null) {
                mShaderHue = (int) mHsv[0];
                int pure = Color.HSVToColor(new float[] {mHsv[0], 1, 1});
                mPaint.setShader(new ComposeShader(
                        new LinearGradient(0, 0, w, 0, Color.WHITE, pure, Shader.TileMode.CLAMP),
                        new LinearGradient(0, 0, 0, h, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP),
                        PorterDuff.Mode.MULTIPLY));
            }
            canvas.drawRoundRect(0, 0, w, h, 20 * mDp, 20 * mDp, mPaint);
            float inset = 4 * mDp; // the thumb stays inside the rounded corners
            mThumb.draw(canvas, inset + mHsv[1] * (w - 2 * inset), inset + (1 - mHsv[2]) * (h - 2 * inset),
                    Color.HSVToColor(mHsv));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mPaint.setShader(null);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            getParent().requestDisallowInterceptTouchEvent(true);
            mThumb.touch(event);
            mHsv[1] = clamp(event.getX() / getWidth());
            mHsv[2] = 1 - clamp(event.getY() / getHeight());
            onChange.run();
            return true;
        }
    }

    /** The rainbow as a thick pill; the thumb shows the pure hue. */
    private static final class HueBar extends View {
        private static final int[] RAINBOW = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        private final float[] mHsv;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Thumb mThumb;
        Runnable onChange;

        HueBar(Context context, float[] hsv, float dp) {
            super(context);
            mHsv = hsv;
            mThumb = new Thumb(this, dp);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mPaint.setShader(new LinearGradient(h / 2f, 0, w - h / 2f, 0, RAINBOW, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            canvas.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, mPaint);
            mThumb.draw(canvas, h / 2f + mHsv[0] / 360 * (w - h), h / 2f, Color.HSVToColor(new float[] {mHsv[0], 1, 1}));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            getParent().requestDisallowInterceptTouchEvent(true);
            mThumb.touch(event);
            int h = getHeight();
            mHsv[0] = clamp((event.getX() - h / 2f) / (getWidth() - h)) * 359.9f;
            onChange.run();
            return true;
        }
    }

    /** A white ring filled with the colour, a soft shadow; it grows while a finger holds it. */
    private static final class Thumb {
        private final View mView;
        private final float mDp;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mScale = 1;

        Thumb(View view, float dp) {
            mView = view;
            mDp = dp;
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null); // the ring's shadow layer
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(3.5f * dp);
            mRing.setColor(Color.WHITE);
            mRing.setShadowLayer(4 * dp, 0, dp, 0x66000000);
        }

        void draw(Canvas canvas, float x, float y, int color) {
            float r = 12 * mDp * mScale;
            mFill.setColor(color);
            canvas.drawCircle(x, y, r, mFill);
            canvas.drawCircle(x, y, r, mRing);
        }

        void touch(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                Haptics.tick(mView);
                mScale = 1.35f;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mScale = 1;
            }
        }
    }

    private static TextView text(Context c, CharSequence value, float sp, boolean bold, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0xFFFFFF) | (alpha << 24);
    }

    private static int px(float dp, int value) {
        return Math.round(value * dp);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
