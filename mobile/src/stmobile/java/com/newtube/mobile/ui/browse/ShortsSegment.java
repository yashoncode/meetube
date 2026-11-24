package com.newtube.mobile.ui.browse;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Glass;
import com.newtube.mobile.ui.common.GlassChipTrack;
import com.newtube.mobile.ui.common.GlassDrawable;

import java.util.Arrays;

/**
 * MEETUBE(feed segment): the All | Videos | Shorts pill at the top of Subscriptions and of a
 * channel pinned to You, the playlist page's control. One row in front of the cards, or none.
 */
class ShortsSegment extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int ALL = 0;
    static final int VIDEOS = 1;
    static final int SHORTS = 2;

    interface Picked {
        void onPicked(int filter);
    }

    private final Picked mPicked;
    private boolean mShown;
    private int mFilter = ALL;

    ShortsSegment(Picked picked) {
        mPicked = picked;
    }

    int filter() {
        return mFilter;
    }

    /** Shown or not; a new page starts on All. */
    void show(boolean shown, boolean reset) {
        if (reset) {
            mFilter = ALL;
        }
        if (shown != mShown) {
            mShown = shown;
            if (shown) {
                notifyItemInserted(0);
            } else {
                notifyItemRemoved(0);
            }
        } else if (shown && reset) {
            notifyItemChanged(0);
        }
    }

    @Override
    public int getItemCount() {
        return mShown ? 1 : 0;
    }

    /** MEETUBE(issue #309): Settings > Chips style, as on Home and Search (it was always the segment, YouTube mode's tabs). */
    @Override
    public int getItemViewType(int position) {
        return Glass.chipStyle(mContext);
    }

    private android.content.Context mContext;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        mContext = recyclerView.getContext();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        float dp = parent.getResources().getDisplayMetrics().density;
        FrameLayout frame = new FrameLayout(parent.getContext());
        frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.setPadding(Math.round(12 * dp), Math.round(4 * dp), Math.round(12 * dp), Math.round(8 * dp));
        if (viewType == Glass.CHIPS_PILLS) {
            LinearLayout row = new LinearLayout(parent.getContext());
            int[] labels = {R.string.mobile_playlist_filter_all, R.string.mobile_playlist_filter_videos,
                    R.string.mobile_playlist_filter_shorts};
            for (int i = 0; i < labels.length; i++) {
                TextView chip = (TextView) LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.mobile_search_chip, row, false);
                chip.setText(labels[i]);
                GlassDrawable.applyPage(chip, null, 0);
                chip.setTag(chip.getBackground());
                int index = i;
                chip.setOnClickListener(v -> {
                    mFilter = index;
                    notifyItemChanged(0);
                    mPicked.onPicked(index);
                });
                row.addView(chip);
            }
            frame.addView(row);
            return new RecyclerView.ViewHolder(frame) {
            };
        }
        GlassChipTrack track = new GlassChipTrack(parent.getContext());
        track.setGlassSource(null);
        track.setChips(Arrays.asList(parent.getContext().getString(R.string.mobile_playlist_filter_all),
                parent.getContext().getString(R.string.mobile_playlist_filter_videos),
                parent.getContext().getString(R.string.mobile_playlist_filter_shorts)));
        track.setOnPicked(index -> {
            mFilter = index;
            mPicked.onPicked(index);
        });
        frame.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * dp)));
        return new RecyclerView.ViewHolder(frame) {
        };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        android.view.View child = ((ViewGroup) holder.itemView).getChildAt(0);
        if (child instanceof GlassChipTrack) {
            ((GlassChipTrack) child).setSelected(mFilter);
            return;
        }
        ViewGroup row = (ViewGroup) child;
        android.content.Context context = row.getContext();
        for (int i = 0; i < row.getChildCount(); i++) {
            // The picked chip is the solid pill (as Search's), the rest glass.
            TextView chip = (TextView) row.getChildAt(i);
            if (i == mFilter) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(18 * context.getResources().getDisplayMetrics().density);
                bg.setColor(GlassDrawable.pickedPill(context));
                chip.setBackground(bg);
            } else {
                chip.setBackground((android.graphics.drawable.Drawable) chip.getTag());
            }
            chip.setTextColor(i == mFilter ? GlassDrawable.pickedInk(context) : GlassDrawable.pageInk(context));
        }
    }
}
