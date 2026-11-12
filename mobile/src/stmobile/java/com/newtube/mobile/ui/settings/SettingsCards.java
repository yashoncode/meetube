package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.newtube.mobile.ui.common.AccentCard;

/**
 * MEETUBE(settings cards): each run of rows between headers sits on the You page's glass card
 * ({@link AccentCard}: a faint fill, an accent glow, a hairline warming to the accent). Headers,
 * notes, dividers and custom views break a run; the rows are inset into the card and the first and
 * last clip their press ripple to its rounded corners.
 */
final class SettingsCards extends RecyclerView.ItemDecoration {
    private final android.content.Context mContext;
    private AccentCard mCard;
    private int mStyle;
    private final int mMargin;
    private final int mPad;
    private final float mRowCorner;
    private final Rect mBox = new Rect();

    SettingsCards(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mContext = context;
        mCard = new AccentCard(context);
        mStyle = AccentCard.style(context);
        mMargin = Math.round(14 * density);
        mPad = Math.round(6 * density);
        mRowCorner = 20 * density;
    }

    private static boolean carded(RecyclerView.Adapter<?> adapter, int position) {
        if (position < 0 || position >= adapter.getItemCount()) {
            return false;
        }
        int kind = adapter.getItemViewType(position);
        return kind == SettingsRow.KIND_LINK || kind == SettingsRow.KIND_SWITCH || kind == SettingsRow.KIND_CHOICE;
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
            @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        int position = parent.getChildAdapterPosition(view);
        if (adapter == null || !carded(adapter, position)) {
            view.setClipToOutline(false);
            return;
        }
        boolean first = !carded(adapter, position - 1);
        boolean last = !carded(adapter, position + 1);
        out.set(mMargin, first ? mPad : 0, mMargin, last ? mPad : 0);
        if (first || last) {
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    int r = Math.round(mRowCorner);
                    // a rounded rect past the open edge rounds only the card's own corners
                    outline.setRoundRect(0, first ? 0 : -r, v.getWidth(), v.getHeight() + (last ? 0 : r), mRowCorner);
                }
            });
            view.setClipToOutline(true);
        } else {
            view.setClipToOutline(false);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null) {
            return;
        }
        if (mStyle != AccentCard.style(mContext)) { // picked in Customization while this page is open
            mStyle = AccentCard.style(mContext);
            mCard = new AccentCard(mContext);
        }
        int offScreen = parent.getHeight();
        int top = Integer.MIN_VALUE;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (!carded(adapter, position)) {
                continue;
            }
            int y = Math.round(child.getTranslationY());
            if (top == Integer.MIN_VALUE) { // a card starts here, or above the screen
                top = carded(adapter, position - 1) ? -offScreen : child.getTop() + y - mPad;
            }
            if (!carded(adapter, position + 1) || i == parent.getChildCount() - 1) {
                int bottom = carded(adapter, position + 1) ? parent.getHeight() + offScreen : child.getBottom() + y + mPad;
                mBox.set(mMargin, top, parent.getWidth() - mMargin, bottom);
                mCard.setBounds(mBox);
                mCard.draw(canvas);
                top = Integer.MIN_VALUE;
            }
        }
    }
}
