package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MEETUBE(select): pick rows of a list (a playlist's videos, MeeTune's songs or downloads). A tap ticks or
 * unticks a row (a check circle in its corner) instead of opening it; a glass bar at the bottom counts
 * them and runs its actions (Download, Pause, Cancel) on the ticked ones. Back or the bar's X leaves.
 */
public final class ListSelection<T> extends RecyclerView.ItemDecoration {
    public interface Items<T> {
        /** The row's item, or null for a row that can't be picked (a header, a footer). */
        @Nullable T at(RecyclerView.ViewHolder holder);

        /** Everything Select all ticks. */
        List<T> all();
    }

    public interface Action<T> {
        void run(List<T> picked);
    }

    private final RecyclerView mGrid;
    private final Items<T> mItems;
    private final Set<T> mPicked = new LinkedHashSet<>();
    private final GlassView mBar;
    private final LinearLayout mRow;
    private final TextView mCount;
    private final List<View> mActions = new ArrayList<>();
    private final List<View> mHidden = new ArrayList<>();
    private final List<Float> mHiddenWas = new ArrayList<>();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mCheck;
    private final float mDp;
    private final int mBox;
    private boolean mActive;

    public ListSelection(FrameLayout root, View blurSource, RecyclerView grid, Items<T> items) {
        Context context = root.getContext();
        mGrid = grid;
        mItems = items;
        mDp = context.getResources().getDisplayMetrics().density;
        mBox = Math.round(44 * mDp);
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(2 * mDp);
        mRing.setColor(Color.WHITE);
        mCheck = ContextCompat.getDrawable(context, R.drawable.ic_mobile_check).mutate();
        mCheck.setTint(Color.BLACK);

        // MEETUBE(glass): a frosted liquid-glass pill over the page, like the nav bar.
        mBar = (GlassView) LayoutInflater.from(context).inflate(R.layout.mobile_playlist_select_bar, root, false);
        Glass.blur(mBar, blurSource);
        mBar.setVisibility(View.GONE);
        mBar.setClickable(true); // taps on the bar never reach the rows under it
        mBar.setTranslationZ(12 * mDp); // over a page's own floating nav bar
        mRow = mBar.findViewById(R.id.mobile_playlist_select_row);
        int onSurface = ContextCompat.getColor(context, R.color.mobile_color_on_surface);

        ImageButton close = new ImageButton(context);
        close.setImageResource(R.drawable.ic_sheet_close);
        close.setColorFilter(onSurface);
        close.setBackgroundResource(R.drawable.bg_glass_circle);
        close.setContentDescription(context.getString(android.R.string.cancel));
        int iconPad = Math.round(11 * mDp);
        close.setPadding(iconPad, iconPad, iconPad, iconPad);
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        close.setOnClickListener(v -> stop());
        mRow.addView(close, new LinearLayout.LayoutParams(mBox, mBox));

        mCount = new TextView(context);
        mCount.setTextColor(onSurface);
        mCount.setTextSize(15);
        mCount.setSingleLine(true);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(mCount, 11, 15, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP); // "17 selected" fits beside three buttons
        mCount.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mCount.setTypeface(AppFont.medium(context));
        mCount.setPadding(Math.round(10 * mDp), 0, Math.round(6 * mDp), 0);
        mRow.addView(mCount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView all = pill(context, context.getString(R.string.mobile_playlist_select_all), onSurface);
        all.setOnClickListener(v -> selectAll());
        mRow.addView(all, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, mBox));

        root.addView(mBar);
        grid.addItemDecoration(this);
    }

    private TextView pill(Context context, String text, int color) {
        TextView pill = new TextView(context);
        pill.setText(text);
        pill.setTextColor(color);
        pill.setTextSize(14);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Math.round(12 * mDp), 0, Math.round(12 * mDp), 0);
        pill.setBackgroundResource(R.drawable.bg_glass_pill);
        pill.setForeground(ContextCompat.getDrawable(context, R.drawable.glass_press_pill));
        return pill;
    }

    /**
     * Adds a button to the bar. {@code primary}: the white filled one (with {@code icon} when not 0);
     * otherwise a glass pill. The selection closes before {@code action} runs.
     */
    public ListSelection<T> action(int label, int icon, boolean primary, Action<T> action) {
        Context context = mGrid.getContext();
        View button;
        if (primary) {
            MaterialButton filled = new MaterialButton(context);
            filled.setText(label);
            if (icon != 0) {
                filled.setIconResource(icon);
                filled.setIconTint(ColorStateList.valueOf(Color.BLACK));
            }
            filled.setTextColor(Color.BLACK);
            filled.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            filled.setCornerRadius(mBox / 2);
            filled.setInsetTop(0);
            filled.setInsetBottom(0);
            filled.setAllCaps(false);
            button = filled;
        } else {
            button = pill(context, context.getString(label), ContextCompat.getColor(context, R.color.mobile_color_on_surface));
        }
        button.setOnClickListener(v -> {
            if (!mPicked.isEmpty()) {
                List<T> picked = new ArrayList<>(mPicked);
                stop();
                action.run(picked);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, mBox);
        params.setMarginStart(Math.round(6 * mDp));
        mRow.addView(button, params);
        mActions.add(button);
        return this;
    }

    /**
     * Views the bar sits over (a floating nav bar, a mini player): moved off the bottom while selecting.
     * Moved, not hidden: their own scroll animations set visibility and alpha as the page moves.
     */
    public ListSelection<T> hiding(View... views) {
        for (View view : views) {
            if (view != null) mHidden.add(view);
        }
        return this;
    }

    public boolean isActive() {
        return mActive;
    }

    /** Opens with {@code ticked} already picked. */
    public void start(List<T> ticked) {
        mActive = true;
        mPicked.clear();
        mPicked.addAll(ticked);
        if (mHiddenWas.isEmpty()) { // not hidden yet (a second start keeps the first saved states)
            mHiddenWas.clear();
            for (View view : mHidden) {
                mHiddenWas.add(view.getTranslationY());
                view.setTranslationY(view.getTranslationY() + 300 * mDp);
            }
        }
        mBar.setVisibility(View.VISIBLE);
        mBar.setTranslationY(mBar.getHeight() + 40 * mDp);
        mBar.animate().translationY(0).setDuration(220).start();
        refresh();
    }

    public void stop() {
        if (!mActive) {
            return;
        }
        mActive = false;
        mPicked.clear();
        mBar.setVisibility(View.GONE);
        for (int i = 0; i < mHidden.size() && i < mHiddenWas.size(); i++) {
            mHidden.get(i).setTranslationY(mHiddenWas.get(i));
        }
        mHiddenWas.clear();
        mGrid.invalidateItemDecorations();
    }

    /** A tap on a row while selecting ticks or unticks it; true when it was taken. */
    public boolean toggle(T item) {
        if (!mActive) {
            return false;
        }
        if (!mPicked.remove(item)) {
            mPicked.add(item);
        }
        Haptics.tick(mGrid);
        refresh();
        return true;
    }

    private void selectAll() {
        List<T> all = mItems.all();
        if (mPicked.containsAll(all)) {
            mPicked.removeAll(all);
        } else {
            mPicked.addAll(all);
        }
        refresh();
    }

    private void refresh() {
        mCount.setText(mGrid.getContext().getString(R.string.mobile_playlist_selected, mPicked.size()));
        for (View action : mActions) {
            action.setEnabled(!mPicked.isEmpty());
            action.setAlpha(mPicked.isEmpty() ? 0.5f : 1f);
        }
        mGrid.invalidateItemDecorations();
    }

    @Override
    public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!mActive) {
            return;
        }
        float radius = 13 * mDp;
        float inset = 10 * mDp;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            T item = mItems.at(parent.getChildViewHolder(child));
            if (item == null) {
                continue;
            }
            boolean picked = mPicked.contains(item);
            float cx = child.getLeft() + child.getTranslationX() + inset + radius;
            float cy = child.getTop() + child.getTranslationY() + inset + radius;
            mFill.setColor(picked ? Color.WHITE : 0x66000000);
            canvas.drawCircle(cx, cy, radius, mFill);
            canvas.drawCircle(cx, cy, radius, mRing);
            if (picked) {
                int half = Math.round(radius * 0.7f);
                mCheck.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
                mCheck.draw(canvas);
            }
        }
    }
}
