package com.newtube.mobile.ui.meetune;

import android.graphics.Bitmap;
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

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * MEETUNE: a soft glow in each cover's own colour behind it (the hue covering most of the cover, as
 * the player's pill uses). Drawn by the list under its items, so no layout changes: every cover of the
 * list's items (big cards, tiles, song rows, Speed dial) found by its art id. A grey cover glows white.
 * Android 9+ (a blurred paint needs it); older phones keep plain covers.
 */
final class CoverGlow extends RecyclerView.ItemDecoration {
    private static final Map<Drawable, Integer> ACCENTS = new WeakHashMap<>();
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect mRect = new Rect();
    private final RectF mGlow = new RectF();
    private final float mDp;
    private final float mBlur;
    private final int mPad; // the blur's reach past the shape
    /**
     * Each glow shape blurred once, as an alpha mask the paint colours. A blur paint drawn straight
     * into the list was blurred again on every scroll frame, every cover on screen: MeeTune scrolled
     * at about 60 fps on a 120 Hz phone (7% janky frames, 2% without the glow).
     */
    private final Map<Long, Bitmap> mMasks = new HashMap<>();

    private CoverGlow(float dp) {
        mDp = dp;
        mBlur = 14 * dp;
        mPad = (int) Math.ceil(2 * mBlur);
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
        if (!MeeTune.coverGlow(parent.getContext())) return;
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
        boolean grey = accent == 0xFFF2F2F2; // black and white or grey: a softer white glow
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
        int alpha = grey ? 0x5C : 0x8C;
        mPaint.setColor(accent);
        mPaint.setAlpha(Math.round(alpha * frame.getAlpha()));
        Bitmap mask = mask(Math.round(mGlow.width()), Math.round(mGlow.height()), radius);
        if (mask != null) canvas.drawBitmap(mask, mGlow.left - mPad, mGlow.top - mPad, mPaint);
    }

    @androidx.annotation.Nullable
    private Bitmap mask(int width, int height, float radius) {
        if (width <= 0 || height <= 0) return null;
        long key = ((long) width << 40) | ((long) height << 20) | Math.round(radius);
        Bitmap mask = mMasks.get(key);
        if (mask == null) {
            // ponytail: a handful of cover sizes per list; cleared when sizes pile up (rotation, resizing)
            if (mMasks.size() > 24) mMasks.clear();
            mask = Bitmap.createBitmap(width + 2 * mPad, height + 2 * mPad, Bitmap.Config.ALPHA_8);
            Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG);
            blur.setMaskFilter(new BlurMaskFilter(mBlur, BlurMaskFilter.Blur.NORMAL));
            new Canvas(mask).drawRoundRect(mPad, mPad, mPad + width, mPad + height, radius, radius, blur);
            mMasks.put(key, mask);
        }
        return mask;
    }
}
