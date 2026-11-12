package com.newtube.mobile.ui.channel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.Haptics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MEETUBE(playlist page): Download > Select videos. A tap ticks a card (a check circle in its corner)
 * instead of playing it; a glass bar at the bottom counts them and downloads the ticked ones.
 * Back or the bar's X leaves the mode.
 */
final class PlaylistSelection extends RecyclerView.ItemDecoration {
    interface Listener {
        void onDownload(List<Video> videos);
    }

    private final RecyclerView mGrid;
    private final VideoCardAdapter mAdapter;
    private final Listener mListener;
    private final Set<Video> mPicked = new LinkedHashSet<>();
    private final com.newtube.mobile.ui.common.GlassView mBar;
    private final TextView mCount;
    private final MaterialButton mDownload;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mCheck;
    private final float mDp;
    private boolean mActive;

    PlaylistSelection(FrameLayout root, RecyclerView grid, VideoCardAdapter adapter, Listener listener) {
        Context context = root.getContext();
        mGrid = grid;
        mAdapter = adapter;
        mListener = listener;
        mDp = context.getResources().getDisplayMetrics().density;
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(2 * mDp);
        mRing.setColor(Color.WHITE);
        mCheck = ContextCompat.getDrawable(context, R.drawable.ic_mobile_check).mutate();
        mCheck.setTint(Color.BLACK);

        // MEETUBE(glass): a frosted liquid-glass pill over the page, like the nav bar.
        mBar = (com.newtube.mobile.ui.common.GlassView) android.view.LayoutInflater.from(context)
                .inflate(R.layout.mobile_playlist_select_bar, root, false);
        com.newtube.mobile.ui.common.Glass.blur(mBar, root.findViewById(R.id.mobile_channel_uploads_glass));
        mBar.setVisibility(View.GONE);
        mBar.setClickable(true); // taps on the bar never reach the cards under it
        LinearLayout row = mBar.findViewById(R.id.mobile_playlist_select_row);

        ImageButton close = new ImageButton(context);
        close.setImageResource(R.drawable.ic_sheet_close);
        close.setColorFilter(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
        close.setBackgroundResource(R.drawable.bg_glass_circle);
        close.setContentDescription(context.getString(android.R.string.cancel));
        int box = Math.round(44 * mDp);
        int iconPad = Math.round(11 * mDp);
        close.setPadding(iconPad, iconPad, iconPad, iconPad);
        close.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        close.setOnClickListener(v -> stop());
        row.addView(close, new LinearLayout.LayoutParams(box, box));

        mCount = new TextView(context);
        mCount.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
        mCount.setTextSize(15);
        mCount.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        mCount.setPadding(Math.round(12 * mDp), 0, Math.round(8 * mDp), 0);
        row.addView(mCount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView all = new TextView(context);
        all.setText(R.string.mobile_playlist_select_all);
        all.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
        all.setTextSize(14);
        all.setGravity(Gravity.CENTER);
        all.setPadding(Math.round(12 * mDp), 0, Math.round(12 * mDp), 0);
        all.setBackgroundResource(R.drawable.bg_glass_pill);
        all.setOnClickListener(v -> selectAll());
        row.addView(all, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, box));

        mDownload = new MaterialButton(context);
        mDownload.setText(R.string.mobile_playlist_download_selected);
        mDownload.setIconResource(R.drawable.ic_watch_download);
        mDownload.setIconTint(android.content.res.ColorStateList.valueOf(Color.BLACK));
        mDownload.setTextColor(Color.BLACK);
        mDownload.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mDownload.setCornerRadius(box / 2);
        mDownload.setInsetTop(0);
        mDownload.setInsetBottom(0);
        mDownload.setAllCaps(false);
        mDownload.setOnClickListener(v -> {
            if (!mPicked.isEmpty()) {
                List<Video> picked = new ArrayList<>(mPicked);
                stop();
                mListener.onDownload(picked);
            }
        });
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, box);
        downloadParams.setMarginStart(Math.round(6 * mDp));
        row.addView(mDownload, downloadParams);

        root.addView(mBar);
        grid.addItemDecoration(this);
    }

    boolean isActive() {
        return mActive;
    }

    void start() {
        mActive = true;
        mPicked.clear();
        mBar.setVisibility(View.VISIBLE);
        mBar.setTranslationY(mBar.getHeight() + 40 * mDp);
        mBar.animate().translationY(0).setDuration(220).start();
        refresh();
    }

    void stop() {
        if (!mActive) {
            return;
        }
        mActive = false;
        mPicked.clear();
        mBar.setVisibility(View.GONE);
        mGrid.invalidateItemDecorations();
    }

    /** A tap on a card while selecting ticks or unticks it; true when it was taken. */
    boolean toggle(Video video) {
        if (!mActive) {
            return false;
        }
        if (!mPicked.remove(video)) {
            mPicked.add(video);
        }
        Haptics.tick(mGrid);
        refresh();
        return true;
    }

    private void selectAll() {
        List<Video> shown = mAdapter.getCurrentList();
        boolean all = mPicked.containsAll(shown);
        if (all) {
            mPicked.removeAll(shown);
        } else {
            mPicked.addAll(shown);
        }
        refresh();
    }

    private void refresh() {
        mCount.setText(mGrid.getContext().getString(R.string.mobile_playlist_selected, mPicked.size()));
        mDownload.setEnabled(!mPicked.isEmpty());
        mDownload.setAlpha(mPicked.isEmpty() ? 0.5f : 1f);
        mGrid.invalidateItemDecorations();
    }

    @Override
    public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!mActive) {
            return;
        }
        List<Video> shown = mAdapter.getCurrentList();
        float radius = 13 * mDp;
        float inset = 10 * mDp;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            RecyclerView.ViewHolder holder = parent.getChildViewHolder(child);
            int position = holder.getBindingAdapter() == mAdapter ? holder.getBindingAdapterPosition() : -1;
            if (position < 0 || position >= shown.size()) {
                continue;
            }
            boolean picked = mPicked.contains(shown.get(position));
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
