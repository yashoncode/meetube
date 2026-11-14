package com.newtube.mobile.ui.channel;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.ChannelAvatars;

/**
 * The playlist page header, as a one-item adapter concatenated in front of the video grid.
 *
 * <p>Everything it shows comes from the card that opened the screen - cover, name, owner,
 * "N videos" - so it needs no extra request and is complete before the first item lands. It
 * holds ZERO items until {@link #setPlaylist} is given a playlist, which is how channel
 * uploads (no playlist behind them) end up with no header at all.</p>
 */
class PlaylistHeaderAdapter extends RecyclerView.Adapter<PlaylistHeaderAdapter.HeaderHolder> {
    interface Callbacks {
        void onPlayAll();
        void onShuffle();
        void onDownloadAll();
        void onSort();
        void onCoverChanged(String url);
        void onFilter(int filter);
        void onAddVideos();
        void onSave();
    }

    /** MEETUBE(playlist page): the All | Videos | Shorts pills. */
    static final int FILTER_ALL = 0;
    static final int FILTER_VIDEOS = 1;
    static final int FILTER_SHORTS = 2;

    private final Callbacks mCallbacks;
    private Video mPlaylist;
    private boolean mActionsEnabled;
    private boolean mBusy;
    private int mSort;
    private int mVideoCount;
    private String mFirstCover;
    private int mFilter = FILTER_ALL;
    private boolean mHasShorts;
    private boolean mCanAdd;
    private boolean mCanSave;
    private boolean mSaved;

    PlaylistHeaderAdapter(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    /** @param playlist the opener card, or {@code null} for "this destination has no header". */
    void setPlaylist(Video playlist) {
        boolean had = mPlaylist != null;
        mPlaylist = playlist;
        mFirstCover = null;

        if (had && playlist == null) {
            notifyItemRemoved(0);
        } else if (!had && playlist != null) {
            notifyItemInserted(0);
        } else if (playlist != null) {
            notifyItemChanged(0);
        }
    }

    /** Play all / Shuffle stay disabled until the grid actually holds something playable. */
    void setActionsEnabled(boolean enabled) {
        if (mActionsEnabled == enabled) {
            return;
        }

        mActionsEnabled = enabled;

        if (mPlaylist != null) {
            notifyItemChanged(0);
        }
    }

    boolean hasHeader() {
        return mPlaylist != null;
    }

    /** MEETUBE(playlist page): count and cover can arrive with the first page after the opener. */
    void setContents(Video first, int count) {
        String cover = first != null ? first.getCardImageUrl() : null;
        if (mVideoCount != count || !TextUtils.equals(mFirstCover, cover)) {
            mVideoCount = count;
            mFirstCover = cover;
            if (hasHeader()) {
                notifyItemChanged(0);
            }
        }
    }

    void setSortState(int sort, boolean busy) {
        if (mSort != sort || mBusy != busy) {
            mSort = sort;
            mBusy = busy;
            if (hasHeader()) {
                notifyItemChanged(0);
            }
        }
    }

    /** MEETUBE(playlist page): the pills show only while the playlist holds Shorts. */
    void setFilter(int filter, boolean hasShorts) {
        if (mFilter != filter || mHasShorts != hasShorts) {
            mFilter = filter;
            mHasShorts = hasShorts;
            if (hasHeader()) {
                notifyItemChanged(0);
            }
        }
    }

    void setCanAdd(boolean canAdd) {
        if (mCanAdd != canAdd) {
            mCanAdd = canAdd;
            if (hasHeader()) {
                notifyItemChanged(0);
            }
        }
    }

    /** MEETUBE(issue #242): the bookmark, on playlists that are not the user's own. */
    void setSaveState(boolean canSave, boolean saved) {
        if (mCanSave != canSave || mSaved != saved) {
            mCanSave = canSave;
            mSaved = saved;
            if (hasHeader()) {
                notifyItemChanged(0);
            }
        }
    }

    static boolean canDownload(Video playlist) {
        // MEETUBE(playlist page): radio mixes are unbounded; isMix also matches some finite playlist cards.
        String id = playlist != null ? playlist.getPlaylistId() : null;
        return playlist != null && id != null && !playlist.isLive && !playlist.isUpcoming
                && !id.startsWith("RD") && !(playlist.isMix() && playlist.hasVideo() && !playlist.isPlaylistCard());
    }

    @Override
    public int getItemCount() {
        return mPlaylist != null ? 1 : 0;
    }

    @NonNull
    @Override
    public HeaderHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_playlist_header, parent, false);

        return new HeaderHolder(view, mCallbacks);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderHolder holder, int position) {
        holder.bind(mPlaylist, mActionsEnabled, mBusy, mSort, mFirstCover, mVideoCount);
        holder.bindFilter(mFilter, mHasShorts);
        holder.mAdd.setVisibility(mCanAdd ? View.VISIBLE : View.GONE);
        holder.mSave.setVisibility(mCanSave ? View.VISIBLE : View.GONE);
        holder.mSave.setImageResource(mSaved ? R.drawable.ic_mobile_bookmark_filled : R.drawable.ic_mobile_bookmark);
        holder.mSave.setContentDescription(holder.itemView.getContext()
                .getString(mSaved ? R.string.mobile_playlist_unsave : R.string.mobile_playlist_save));
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        private final ImageView mCover;
        private final ImageView mAvatar;
        private final TextView mTitle;
        private final TextView mOwner;
        private final TextView mMeta;
        private final MaterialButton mPlayAll;
        private final ImageButton mShuffle;
        private final ImageButton mAdd;
        private final ImageButton mSave;
        private final ImageButton mDownload;
        private final View mSort;
        private final TextView mSortLabel;
        private final ProgressBar mSortProgress;
        private final com.newtube.mobile.ui.common.GlassChipTrack mFilters;
        private final Callbacks mCallbacks;

        HeaderHolder(@NonNull View itemView, Callbacks callbacks) {
            super(itemView);
            mCallbacks = callbacks;

            mCover = itemView.findViewById(R.id.mobile_channel_uploads_cover);
            mAvatar = itemView.findViewById(R.id.mobile_channel_uploads_header_avatar);
            mTitle = itemView.findViewById(R.id.mobile_channel_uploads_header_title);
            mOwner = itemView.findViewById(R.id.mobile_channel_uploads_header_owner);
            mMeta = itemView.findViewById(R.id.mobile_channel_uploads_header_meta);
            mPlayAll = itemView.findViewById(R.id.mobile_channel_uploads_header_play_all);
            mShuffle = itemView.findViewById(R.id.mobile_channel_uploads_shuffle);
            mAdd = itemView.findViewById(R.id.mobile_channel_uploads_add);
            mAdd.setOnClickListener(v -> callbacks.onAddVideos());
            mSave = itemView.findViewById(R.id.mobile_channel_uploads_save);
            mSave.setOnClickListener(v -> callbacks.onSave());
            mDownload = itemView.findViewById(R.id.mobile_channel_uploads_download_all);
            mSort = itemView.findViewById(R.id.mobile_channel_uploads_sort);
            mSortLabel = itemView.findViewById(R.id.mobile_channel_uploads_sort_label);
            mSortProgress = itemView.findViewById(R.id.mobile_channel_uploads_sort_progress);
            mFilters = itemView.findViewById(R.id.mobile_playlist_filters);
            mFilters.setGlassSource(null); // over the blurred cover, as the watch page's Up next chips
            android.content.Context context = itemView.getContext();
            mFilters.setChips(java.util.Arrays.asList(context.getString(R.string.mobile_playlist_filter_all),
                    context.getString(R.string.mobile_playlist_filter_videos),
                    context.getString(R.string.mobile_playlist_filter_shorts)));
            mFilters.setOnPicked(callbacks::onFilter);

            mPlayAll.setOnClickListener(v -> callbacks.onPlayAll());
            mShuffle.setOnClickListener(v -> callbacks.onShuffle());
            mDownload.setOnClickListener(v -> callbacks.onDownloadAll());
            mSort.setOnClickListener(v -> callbacks.onSort());
        }

        void bind(Video playlist, boolean actionsEnabled, boolean busy, int sort, String firstCover, int count) {
            if (playlist == null) {
                return;
            }

            mTitle.setText(playlist.getTitle());

            // MEETUBE(playlist page): getAuthor() can infer the playlist title when no owner was supplied.
            CharSequence owner = playlist.author;
            if (TextUtils.isEmpty(owner) && playlist.mediaItem != null) {
                owner = playlist.mediaItem.getAuthor();
            }
            mOwner.setText(TextUtils.isEmpty(owner) ? null
                    : itemView.getContext().getString(R.string.mobile_playlist_by_owner, owner));
            mOwner.setVisibility(TextUtils.isEmpty(owner) ? View.GONE : View.VISIBLE);

            String avatar = TextUtils.isEmpty(owner) ? null
                    : ChannelAvatars.get(itemView.getContext(), playlist.channelId, owner.toString());
            mAvatar.setVisibility(avatar != null ? View.VISIBLE : View.GONE);
            Glide.with(mAvatar).load(avatar).circleCrop().into(mAvatar);

            // "30 videos" (badge) + "Private - Updated today" (second title): the same two facts
            // YouTube stacks under a playlist name, in the same order. The second title is only
            // added when it isn't already standing in as the owner line.
            CharSequence badge = playlist.badge;
            if (TextUtils.isEmpty(badge) && count > 0) {
                badge = itemView.getResources().getQuantityString(R.plurals.mobile_playlist_video_count, count, count);
            }
            CharSequence meta = joinMeta(itemView.getContext().getString(R.string.mobile_playlist_type), badge);
            CharSequence detail = playlist.getSecondTitle();
            if (!TextUtils.isEmpty(detail) && (TextUtils.isEmpty(owner) || !detail.toString().contains(owner))) {
                meta = joinMeta(meta, detail);
            }
            mMeta.setText(meta);
            mMeta.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);

            mPlayAll.setEnabled(actionsEnabled);
            mShuffle.setEnabled(actionsEnabled);
            mDownload.setVisibility(canDownload(playlist) ? View.VISIBLE : View.GONE);
            mDownload.setEnabled(actionsEnabled && !busy);
            mDownload.setAlpha(actionsEnabled && !busy ? 1f : 0.4f);
            mShuffle.setAlpha(actionsEnabled ? 1f : 0.4f);
            mSort.setEnabled(actionsEnabled && !busy);
            mSortLabel.setText(PlaylistSort.label(sort));
            mSortProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            mSort.setContentDescription(busy ? itemView.getContext().getString(R.string.mobile_playlist_loading)
                    : itemView.getContext().getString(PlaylistSort.label(sort)));

            String coverUrl = playlist.getCardImageUrl();
            if (TextUtils.isEmpty(coverUrl)) {
                coverUrl = firstCover;
            }
            Glide.with(mCover).load(coverUrl).centerCrop().into(mCover);
            mCallbacks.onCoverChanged(coverUrl);
        }

        void bindFilter(int filter, boolean hasShorts) {
            mFilters.setVisibility(hasShorts ? View.VISIBLE : View.GONE);
            mFilters.setSelected(filter);
        }

        private CharSequence joinMeta(CharSequence first, CharSequence second) {
            if (TextUtils.isEmpty(first)) {
                return second;
            }
            if (TextUtils.isEmpty(second)) {
                return first;
            }

            return TextUtils.concat(first, " • ", second);
        }
    }
}
