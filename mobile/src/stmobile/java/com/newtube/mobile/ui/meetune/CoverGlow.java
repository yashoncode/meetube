package com.newtube.mobile.ui.meetune;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.playback.MeeTunePlayerSkin;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * MEETUNE: a soft glow in each cover's own colour behind it (the hue covering most of the cover, as
 * the player's pill uses). Drawn by the list under its items, so no layout changes: every cover of the
 * list's items (big cards, tiles, song rows, Speed dial) found by its art id. A grey cover gets none.
 * Android 9+ (a blurred paint needs it); older phones keep plain covers.
 */
final class CoverGlow extends RecyclerView.ItemDecoration {
    private static final Map<Drawable, Integer> ACCENTS = new WeakHashMap<>();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mRect = new Rect();
    private final RectF mGlow = new RectF();
    private final float mDp;

    private CoverGlow(float dp) {
        mDp = dp;
        mPaint.setMaskFilter(new BlurMaskFilter(14 * dp, BlurMaskFilter.Blur.NORMAL));
    }

    /** Once per list. */
    static void attach(RecyclerView list) {
        if (Build.VERSION.SDK_INT < 28) return;
        for (int i = 0; i < list.getItemDecorationCount(); i++) {
            if (list.getItemDecorationAt(i) instanceof CoverGlow) return;
        }
        list.addItemDecoration(new CoverGlow(list.getResources().getDisplayMetrics().density));
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            draw(canvas, parent, parent.getChildAt(i));
        }
    }

    private void draw(Canvas canvas, RecyclerView list, View view) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f) return;
        if (view instanceof RecyclerView) return; // a shelf inside the list glows its own covers
        if (view.getId() == R.id.meetune_art && view instanceof ImageView) {
            glow(canvas, list, (ImageView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) draw(canvas, list, group.getChildAt(i));
        }
    }

    private void glow(Canvas canvas, RecyclerView list, ImageView art) {
        Drawable cover = art.getDrawable();
        if (cover == null || cover.getIntrinsicWidth() <= 0) return;
        Integer accent = ACCENTS.get(cover);
        if (accent == null) {
            accent = MeeTunePlayerSkin.accent(cover);
            ACCENTS.put(cover, accent);
        }
        if (accent == 0xFFF2F2F2) return; // a grey cover: no colour to glow
        View frame = art.getParent() instanceof MaterialCardView ? (View) art.getParent() : art;
        if (frame.getVisibility() != View.VISIBLE) return;
        mRect.set(0, 0, frame.getWidth(), frame.getHeight());
        try {
            list.offsetDescendantRectToMyCoords(frame, mRect);
        } catch (IllegalArgumentException detached) {
            return;
        }
        float inset = Math.min(mRect.width(), mRect.height()) * 0.06f; // it peeks out, it doesn't box
        mGlow.set(mRect.left + inset, mRect.top + inset * 1.6f, mRect.right - inset, mRect.bottom);
        float radius = frame instanceof MaterialCardView ? ((MaterialCardView) frame).getRadius() : 8 * mDp;
        mPaint.setColor((accent & 0x00FFFFFF) | 0x8C000000);
        mPaint.setAlpha(Math.round(0x8C * frame.getAlpha()));
        canvas.drawRoundRect(mGlow, radius, radius, mPaint);
    }
}
