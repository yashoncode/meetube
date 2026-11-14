package com.newtube.mobile.ui.browse;

import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.GlassChipTrack;

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

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        float dp = parent.getResources().getDisplayMetrics().density;
        FrameLayout frame = new FrameLayout(parent.getContext());
        frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.setPadding(Math.round(12 * dp), Math.round(4 * dp), Math.round(12 * dp), Math.round(8 * dp));
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
        ((GlassChipTrack) ((ViewGroup) holder.itemView).getChildAt(0)).setSelected(mFilter);
    }
}
