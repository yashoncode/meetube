package com.newtube.mobile.ui.channel;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.QueuePlaybackMode;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.downloads.PlaylistDownloads;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.FilteredPageTopUp;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.common.ShortsFilter;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Touch Channel-Uploads list (Wave 4a).
 *
 * <p>Renders a MIX / PLAYLIST / CHART / channel-uploads destination as a single Material
 * RecyclerView GRID, mirroring {@code MobileBrowseActivity}'s grid setup (same
 * {@link VideoCardAdapter}, same runtime span-count math, same {@code onScrollEnd}
 * pagination), but with a Toolbar (title + back) instead of a bottom-nav shell. It is the
 * touch replacement for the Leanback {@code ChannelUploadsFragment}/{@code
 * ChannelUploadsActivity}.</p>
 *
 * <p>Drives the unchanged {@link ChannelUploadsPresenter} via the standard MVP seam
 * (setView/onViewInitialized + the {@code VideoGroupPresenter} input contract). The
 * presenter already has its target ({@code mChannel}/{@code mPendingGroup}) set by whoever
 * opened this view ({@code ChannelUploadsPresenter.openChannel()} /
 * {@code VideoActionPresenter.apply()}); {@code onViewInitialized()} -> {@code refresh()}
 * kicks off the actual content load against this freshly-created view, exactly like the TV
 * fragment.</p>
 *
 * <ul>
 *   <li>Tap a card -> {@link ChannelUploadsPresenter#onVideoItemClicked} ->
 *       {@code VideoActionPresenter.apply()} -> normal routing (plays a video via
 *       {@code MobilePlaybackActivity}; opens a nested playlist/sub-channel via the natural
 *       Channel(Uploads) routing).</li>
 *   <li>Long-press a card -> {@link ChannelUploadsPresenter#onVideoItemLongClicked} ->
 *       {@code VideoMenuPresenter}/{@code AppDialogPresenter}, rendered by the Wave-3
 *       {@code MobileAppDialogActivity}.</li>
 *   <li>Scroll near the end -> {@link ChannelUploadsPresenter#onScrollEnd} paginates.</li>
 * </ul>
 */
public class MobileChannelUploadsActivity extends MobileActivity
        implements ChannelUploadsView, MiniPlayerBridge.MiniHost {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;

    private ChannelUploadsPresenter mPresenter;

    /** Docks the live player card when a video opened from this list is minimized. */
    private MobileMiniPlayerController mMiniPlayer;

    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    private ImageButton mBackButton;
    private MaterialButton mPlayAllButton;
    private PlaylistBackdrop mBackdrop;

    /** Playlist header row (absent for plain channel uploads) - see {@link PlaylistHeaderAdapter}. */
    private PlaylistHeaderAdapter mHeaderAdapter;
    /** "Couldn't load more" row after the cards when a next page failed. */
    private LoadMoreFailureAdapter mLoadMoreFooter;
    private SwipeRefreshLayout mSwipe;
    /** First page failed / came back empty (see {@link #showLoadFailure}). */
    private PageLoadState mLoadState;

    private final List<Video> mVideos = new ArrayList<>();
    /** MEETUBE(playlist page): all entries in server order, including cards hidden by ShortsFilter. */
    private final List<Video> mPlaylistVideos = new ArrayList<>();
    private static final int BULK_NONE = 0;
    private static final int BULK_SORT = 1;
    private static final int BULK_DOWNLOAD = 2;
    private static final int BULK_CONFIRM_DOWNLOAD = 3;
    private static final Pattern PLAYLIST_COUNT = Pattern.compile("(?i)^\\s*([0-9][0-9,\\u00a0 ]*)\\s*(?:videos?)?\\s*$");
    private int mSort = PlaylistSort.MANUAL;
    private int mFilter = PlaylistHeaderAdapter.FILTER_ALL;
    private PlaylistSelection mSelection;
    private int mPendingSort;
    private int mBulkAction;
    private int mDownloadQuality;
    private int mPlaylistGeneration;
    private boolean mPageLoading;
    private boolean mQueueingDownloads;
    private final Set<String> mBulkPageKeys = new HashSet<>();
    @Nullable private Disposable mDownloadTask;
    private int mLastPaginationTriggerCount = -1;
    /** NEWTUBE(shorts): pages this list fetches itself when its dropped Shorts left it short. */
    private final FilteredPageTopUp mTopUp = new FilteredPageTopUp();
    /** The group the last page came in (the anchor to continue when every card was a Short). */
    private VideoGroup mLastGroup;
    /** NEWTUBE(shorts): pages arrived but the Shorts filter left nothing of them (#63). */
    private boolean mShortsEmptied;
    /** NEWTUBE(shorts): onDestroy has begun - the presenter's teardown must not page on. */
    private boolean mTornDown;
    /** MEETUBE(#65): the playlist found to be the user's own (its cards swipe out of it), and its name. */
    @Nullable private String mOwnPlaylistId;
    @Nullable private String mOwnPlaylistTitle;
    /** The playlist already asked about, so each one is checked once. */
    @Nullable private String mOwnCheckedFor;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_channel_uploads);

        registerBackHandler(this::handleBack);

        bindViews();
        mMiniPlayer = new MobileMiniPlayerController(this);
        setupGrid();
        setupSwipeRefresh();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        mPlayAllButton.setOnClickListener(v -> playAll());

        mPresenter = ChannelUploadsPresenter.instance(this);

        applyOpenerTitle();

        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    /**
     * This activity is {@code singleTop} and every {@code startView()} adds
     * {@code FLAG_ACTIVITY_REORDER_TO_FRONT}, so opening a second playlist/channel REUSES this
     * instance: {@link #onCreate} never runs again and the toolbar would keep showing the
     * previous destination's name until a titled {@code VideoGroup} happens to arrive (the first
     * delivered group often has none, so the wrong title survived the whole content load -
     * "Watch later" rendered as "Recommended" on the emulator).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        applyOpenerTitle();
    }

    /**
     * Prefer the channel/playlist title the opener already knows ({@code mChannel} is set before
     * this view is started). The delivered {@link VideoGroup}s also carry a title and refine it.
     */
    private void applyOpenerTitle() {
        Video channel = mPresenter != null ? mPresenter.getChannel() : null;

        if (channel != null && channel.getTitle() != null) {
            mTitleView.setText(channel.getTitle());
        }

        applyHeader(channel);
    }

    /**
     * Hands the opener card to the header row - it already carries everything the header shows,
     * so no extra request is needed and the header is complete before the first item lands.
     *
     * <p>Only playlists get one. Channel uploads have no playlist behind them and would render
     * a header that just repeats the toolbar, so they keep the compact toolbar button.</p>
     */
    private void applyHeader(Video opener) {
        boolean isPlaylist = opener != null && opener.getPlaylistId() != null;

        mHeaderAdapter.setPlaylist(isPlaylist ? opener : null);
        updateCanAdd();
        submitVideos();
        applyToolbarTitleAlpha();
        mGrid.post(this::applyToolbarTitleAlpha);
    }

    /**
     * The playlist name is already the biggest thing on screen while the header is in view, so
     * the toolbar copy of it only fades in as the header scrolls away - the YouTube playlist page
     * shows a bare back arrow over the cover for the same reason. Screens without a header
     * (channel uploads) keep the title visible at all times.
     */
    private void applyToolbarTitleAlpha() {
        applyToolbarTitleAlpha(0);
    }

    private void applyToolbarTitleAlpha(int dy) {
        // No header in view - none at all, or the grid (header included) gave way to the
        // failure state: the toolbar is the only place the name shows.
        if (!mHeaderAdapter.hasHeader() || mGrid.getVisibility() != View.VISIBLE) {
            mTitleView.setAlpha(1f);
            mBackdrop.follow(null, mGrid.getPaddingTop(), dy, false);
            return;
        }

        View header = mLayoutManager.findViewByPosition(0);
        float collapsed;

        if (header == null) {
            collapsed = 1f; // scrolled past it entirely
        } else if (header.getHeight() <= 0) {
            collapsed = 0f;
        } else {
            collapsed = Math.min(1f, Math.max(0f, -header.getTop() / (float) header.getHeight()));
        }

        // Hold at 0 until the header is mostly gone, then fade in over the last quarter.
        float alpha = Math.max(0f, (collapsed - 0.75f) * 4f);
        mTitleView.setAlpha(alpha);
        mBackdrop.follow(header, mGrid.getPaddingTop(), dy, true);
    }

    private void bindViews() {
        mGrid = findViewById(R.id.mobile_channel_uploads_grid);
        // NEWTUBE(mini-inset): the last row can scroll clear of the docked mini-player card.
        com.newtube.mobile.ui.playback.MiniPlayerListInset.attach(findViewById(R.id.mobile_mini_player), mGrid);
        mProgressBar = findViewById(R.id.mobile_channel_uploads_progress);
        mTitleView = findViewById(R.id.mobile_channel_uploads_title);
        mBackButton = findViewById(R.id.mobile_channel_uploads_back);
        // MEETUBE(glass): the top bar frosts the cover and cards scrolling under it, like Settings'.
        com.newtube.mobile.ui.common.Glass.blur(findViewById(R.id.mobile_channel_uploads_bar),
                findViewById(R.id.mobile_channel_uploads_glass));
        mBackdrop = new PlaylistBackdrop(findViewById(R.id.mobile_channel_uploads_ambient),
                findViewById(R.id.mobile_channel_uploads_backdrop));
        mPlayAllButton = findViewById(R.id.mobile_channel_uploads_play_all);
        mSwipe = findViewById(R.id.mobile_channel_uploads_swipe);
        // MEETUBE(playlist page): the list runs under the toolbar now; the refresh spinner starts below it.
        int bar = getResources().getDimensionPixelSize(R.dimen.mobile_title_bar_height);
        float dp = getResources().getDisplayMetrics().density;
        mSwipe.setProgressViewOffset(false, bar - Math.round(40 * dp), bar + Math.round(24 * dp));
        mLoadState = new PageLoadState(findViewById(R.id.mobile_page_load_state), this::retryFirstPage);
    }

    private void setupGrid() {
        int spanCount = computeSpanCount();
        mLayoutManager = new GridLayoutManager(this, spanCount);
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked)
                .setListRows(com.newtube.mobile.ui.browse.HomeLayout.isVideoList(this, com.newtube.mobile.ui.browse.HomeLayout.PAGE_PLAYLISTS));
        mHeaderAdapter = new PlaylistHeaderAdapter(new PlaylistHeaderAdapter.Callbacks() {
            @Override
            public void onPlayAll() {
                playAll();
            }

            @Override
            public void onShuffle() {
                shuffle();
            }

            @Override
            public void onDownloadAll() {
                downloadAll();
            }

            @Override
            public void onSort() {
                showSortMenu();
            }

            @Override
            public void onCoverChanged(String url) {
                mBackdrop.setCover(url);
            }

            @Override
            public void onAddVideos() {
                String playlistId = openerPlaylistId();
                if (playlistId != null) {
                    com.newtube.mobile.ui.search.MobileSearchActivity.startAddingTo(playlistId,
                            mTitleView.getText().toString());
                    com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter
                            .instance(MobileChannelUploadsActivity.this).startSearch(null);
                }
            }

            @Override
            public void onFilter(int filter) {
                mFilter = filter;
                submitVideos();
            }
        });

        mLoadMoreFooter = new LoadMoreFailureAdapter(this::retryLoadMore);

        // The header is one full-width row in front of the cards; in landscape the grid is
        // multi-column, so it has to claim every span or it would sit in the first cell. Same for
        // the "Couldn't load more" row after them.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return isHeaderPosition(position) || isFooterPosition(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mGrid.setItemViewCacheSize(8);
        mGrid.setLayoutManager(mLayoutManager);
        // NOT setHasFixedSize: the header row makes the content height change with the data.
        mGrid.setAdapter(new ConcatAdapter(mHeaderAdapter, mAdapter, mLoadMoreFooter));
        // Next cards' thumbnails decoded before they scroll in (no grey card + fade on a fling).
        // Grid positions are shifted by the optional playlist header row.
        com.newtube.mobile.ui.common.FeedThumbnailPreloader.attach(mGrid, position -> {
            int card = position - (mHeaderAdapter.hasHeader() ? 1 : 0);
            return card >= 0 && card < mAdapter.getItemCount() ? mAdapter.getCurrentList().get(card) : null;
        });
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // dy == 0: a layout pass changed the visible range, not the user.
                maybeTriggerPagination(dy != 0);
                applyToolbarTitleAlpha(dy);
            }
        });
        // MEETUBE(#65): in the user's own playlist a card swiped sideways leaves it (Undo on the snackbar).
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public int getDragDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
                // MEETUBE(playlist page): any ordering gesture belongs to Manual, outside a full-playlist load.
                return mSort == PlaylistSort.MANUAL && !playlistBusy()
                        ? super.getDragDirs(recyclerView, holder) : 0;
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
                return holder.getBindingAdapter() == mAdapter && isOwnPlaylistShown() && !playlistBusy()
                        ? super.getSwipeDirs(recyclerView, holder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder,
                    @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                removeSwiped(holder.getBindingAdapterPosition());
            }
        }).attachToRecyclerView(mGrid);
    }

    @Nullable
    private String openerPlaylistId() {
        Video opener = mPresenter != null ? mPresenter.getChannel() : null;
        return opener != null ? opener.getPlaylistId() : null;
    }

    /** MEETUBE(playlist page): + is for Watch later and your own playlists (Liked videos fills by liking). */
    private void updateCanAdd() {
        String id = openerPlaylistId();
        mHeaderAdapter.setCanAdd(id != null && !"LL".equals(id) && ("WL".equals(id) || isOwnPlaylistShown()));
    }

    private boolean isOwnPlaylistShown() {
        return mOwnPlaylistId != null && mOwnPlaylistId.equals(openerPlaylistId());
    }

    /** MEETUBE(#65): once per playlist, with its first video: the menu's own-playlist test. */
    private void checkOwnPlaylist() {
        String playlistId = openerPlaylistId();
        if (playlistId == null || playlistId.equals(mOwnCheckedFor)) {
            return;
        }
        for (Video video : mVideos) {
            if (video != null && video.hasVideo()) {
                mOwnCheckedFor = playlistId;
                VideoMenuPresenter.instance(this).findOwnPlaylist(video, playlistId, title -> runOnUiThread(() -> {
                    mOwnPlaylistId = playlistId;
                    mOwnPlaylistTitle = title;
                    updateCanAdd();
                }));
                return;
            }
        }
    }

    private void removeSwiped(int position) {
        Video video = position >= 0 && position < mAdapter.getItemCount() ? mAdapter.getCurrentList().get(position) : null;
        int index = video != null ? mVideos.indexOf(video) : -1;
        String playlistId = mOwnPlaylistId;
        if (index < 0 || playlistId == null) {
            mAdapter.notifyItemChanged(position); // slide it back
            return;
        }
        mVideos.remove(index);
        int originalIndex = mPlaylistVideos.indexOf(video);
        mPlaylistVideos.remove(video);
        submitVideos();
        updatePlayAllVisibility();
        VideoMenuPresenter.instance(this).removeFromOwnPlaylist(video, playlistId, mOwnPlaylistTitle,
                (item, action) -> runOnUiThread(() -> {
                    // Undo, or the removal failed: the card goes back where it was.
                    if (action == VideoMenuPresenter.VideoMenuCallback.ACTION_RESTORE_TO_PLAYLIST
                            && !mVideos.contains(item) && playlistId.equals(openerPlaylistId())) {
                        mVideos.add(Math.min(index, mVideos.size()), item);
                        mPlaylistVideos.add(Math.min(Math.max(0, originalIndex), mPlaylistVideos.size()), item);
                        submitVideos();
                        updatePlayAllVisibility();
                    }
                }));
    }

    private boolean isHeaderPosition(int position) {
        return position == 0 && mHeaderAdapter.hasHeader();
    }

    private boolean isFooterPosition(int position) {
        return mLoadMoreFooter.isFailed()
                && position == (mHeaderAdapter.hasHeader() ? 1 : 0) + mAdapter.getItemCount();
    }

    private void setupSwipeRefresh() {
        mSwipe.setOnRefreshListener(() -> {
            cancelPlaylistWork();
            com.newtube.mobile.ui.common.RefreshFeedback.play(mSwipe);
            if (mPresenter == null || !mPresenter.reload(!mVideos.isEmpty())) {
                mSwipe.setRefreshing(false);
            }
        });
    }

    /** Failure state's Try again: the same first load, from a blank list. */
    private void retryFirstPage() {
        if (mPresenter != null) {
            mPresenter.reload(false);
        }
    }

    /** Footer's Try again: re-ask for the page that failed (its continuation key is unchanged). */
    private void retryLoadMore() {
        mLoadMoreFooter.setFailed(false);

        if (mVideos.isEmpty() || mPresenter == null) {
            return;
        }

        mLastPaginationTriggerCount = mAdapter.getItemCount();
        Video anchor = continuationAnchor();
        if (anchor != null) {
            mPresenter.onScrollEnd(anchor);
        }
    }

    private void hideLoadState() {
        if (!mLoadState.isShowing()) {
            return;
        }

        mLoadState.hide();
        mGrid.setVisibility(View.VISIBLE);
        // The header row is back: re-derive the title fade once it has been laid out.
        mGrid.post(this::applyToolbarTitleAlpha);
    }

    private void onVideoClicked(Video video) {
        if (mSelection != null && mSelection.toggle(video)) {
            return;
        }
        if (mPresenter != null) {
            mPresenter.onVideoItemClicked(mHeaderAdapter.hasHeader() ? queueShorts(video) : video);
        }
    }

    /**
     * MEETUBE(playlist page): playlist items (Liked videos, Watch later) never say they are Shorts,
     * but YouTube serves a Short's thumbnail as a vertical-crop sddefault with this sqp, which a
     * regular video's never carries. Up to 3 minutes, as a Short can run. Checked on a real
     * Liked videos list: every Short had it, no long video did.
     */
    private static final String SHORTS_THUMB = "sddefault.jpg?sqp=-oaymwEmCIAFEOAD8quKqQMa8AEB";
    private static final long SHORTS_MAX_MS = 180_000;

    private static void markPlaylistShorts(@Nullable List<Video> videos) {
        if (videos == null) {
            return;
        }
        for (Video video : videos) {
            if (video == null || video.isShorts || !video.hasVideo()) {
                continue;
            }
            String thumb = video.getCardImageUrl();
            long duration = video.mediaItem != null ? video.mediaItem.getDurationMs() : 0;
            if (thumb != null && thumb.contains(SHORTS_THUMB) && duration > 0 && duration <= SHORTS_MAX_MS) {
                video.isShorts = true;
            }
        }
    }

    /**
     * MEETUBE(playlist page): a Short opens the Shorts player with the list's Shorts as its queue, in
     * the order shown. Marked copies, so the cards here keep their look.
     */
    private Video queueShorts(Video tapped) {
        if (!tapped.hasVideo() || !ShortsFilter.isShort(tapped)) {
            return tapped;
        }
        List<Video> shorts = new ArrayList<>();
        Video opened = null;
        for (Video video : mAdapter.getCurrentList()) {
            if (video.hasVideo() && ShortsFilter.isShort(video)) {
                Video copy = video.copy();
                copy.isShorts = true;
                copy.author = video.author; // copy() leaves it, and the overlay names the channel
                copy.markShorts();
                shorts.add(copy);
                if (video == tapped) {
                    opened = copy;
                }
            }
        }
        if (opened == null) {
            return tapped;
        }
        ShortsFilter.queueAround(shorts, opened);
        return opened;
    }

    private boolean onVideoLongClicked(Video video) {
        if (mSelection != null && mSelection.toggle(video)) {
            return true;
        }
        if (mPresenter == null) {
            return false;
        }

        mPresenter.onVideoItemLongClicked(video);
        return true;
    }

    /** Start from the first playable item, carrying this destination's playlist context. */
    private void playAll() {
        Video first = findFirstPlayableVideo();

        if (first != null) {
            onVideoClicked(withPlaylistContext(first));
        }
    }

    private Video findFirstPlayableVideo() {
        for (Video video : PlaylistSort.apply(mVideos, mSort)) {
            if (video != null && video.hasVideo()) {
                return video;
            }
        }

        return null;
    }

    /**
     * Not every path fills the playlist id INTO the listed items: a playlist reached through a
     * card that also carries a video resolves via {@code getMetadataObserve() ->
     * findPlaylistRow()}, whose rows are plain suggestion items. Those items still belong to this
     * playlist - the opener knows its id - so borrow it, or "Play all" would either hide itself
     * or start a single video with no queue behind it.
     *
     * <p>Returns a COPY when it has to inject the id: {@code Video}'s identity is a composite
     * hash that includes the playlist, and the grid's list/diffing holds the original.</p>
     */
    private Video withPlaylistContext(Video video) {
        Video opener = mPresenter != null ? mPresenter.getChannel() : null;
        String playlistId = opener != null ? opener.getPlaylistId() : null;

        if (video.getPlaylistId() != null || playlistId == null) {
            return video;
        }

        Video copy = Video.from(video);
        copy.playlistId = playlistId;
        copy.playlistParams = opener.playlistParams;

        return copy;
    }

    /**
     * Starts a random item and keeps the REST of this queue shuffling too - randomizing only the
     * first video would be a lie.
     *
     * <p>Scoped to THIS playlist ({@link QueuePlaybackMode}), not written to the player's stored
     * repeat mode. Writing the stored mode is what it used to do, and it made one tap here change
     * every later video's behaviour until the user hunted down the repeat picker; the toast that
     * stood here existed only to warn about that. YouTube scopes shuffle to the queue, so there is
     * nothing lasting left to warn about - and a toast would be torn away anyway, since the player
     * opens in the same breath and takes this screen with it.</p>
     */
    private void shuffle() {
        Video random = findRandomPlayableVideo();

        if (random == null) {
            return;
        }

        Video opening = withPlaylistContext(random);
        QueuePlaybackMode.shuffle(opening.getPlaylistId());

        onVideoClicked(opening);
    }

    /** Random pick among the rows in hand (the loaded page), never the "no items" case. */
    private Video findRandomPlayableVideo() {
        List<Video> playable = new ArrayList<>();

        for (Video video : mVideos) {
            if (video != null && video.hasVideo()) {
                playable.add(video);
            }
        }

        return playable.isEmpty() ? null : playable.get(new Random().nextInt(playable.size()));
    }

    /**
     * Visible whenever this destination can start a queue: either the items already carry the
     * playlist context, or the opener does (see {@link #withPlaylistContext}).
     *
     * <p>Play all lives in TWO places and only one shows at a time: the playlist header has its
     * own pair of buttons, so the compact toolbar button is for the header-less case (channel
     * uploads) - otherwise the same action would sit on screen twice.</p>
     */
    private void updatePlayAllVisibility() {
        Video opener = mPresenter != null ? mPresenter.getChannel() : null;
        boolean hasContext = findFirstPlayableVideo() != null
                && (hasPlaylistItem() || (opener != null && opener.getPlaylistId() != null));
        boolean hasHeader = mHeaderAdapter.hasHeader();

        mPlayAllButton.setVisibility(hasContext && !hasHeader ? View.VISIBLE : View.GONE);
        mHeaderAdapter.setActionsEnabled(hasContext);
    }

    private boolean hasPlaylistItem() {
        for (Video video : mVideos) {
            if (video != null && video.hasVideo() && video.getPlaylistId() != null) {
                return true;
            }
        }

        return false;
    }

    private void maybeTriggerPagination(boolean userScroll) {
        // A failed next page waits for Try again instead of re-firing on every scroll frame.
        if (mVideos.isEmpty() || mPresenter == null || mLoadMoreFooter.isFailed() || playlistBusy() || mPageLoading) {
            return;
        }

        // Grid positions include the header row, so shift back into card space before comparing.
        int headerOffset = mHeaderAdapter.hasHeader() ? 1 : 0;
        int lastVisible = mLayoutManager.findLastVisibleItemPosition() - headerOffset;
        int itemCount = mAdapter.getItemCount();

        if (lastVisible < 0 || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            Video last = continuationAnchor();
            if (last == null) {
                return;
            }
            if (userScroll) {
                mTopUp.onUserAction(mVideos.size()); // NEWTUBE(shorts): the user asked for more
            } else if (!mTopUp.takeAutomatic(last.getGroup() != null && last.getGroup().getNextPageKey() != null)) {
                return; // a layout pass, not the user: only within the top-up budget
            }
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(last);
        }
    }

    /**
     * NEWTUBE(shorts): with its Shorts dropped the list may be too short to scroll, and then
     * nothing would ever ask for its next page: fetch it now, within {@link FilteredPageTopUp}'s
     * budget - only right after a page landed (not after a cancel or a failure). Returns whether a
     * page was asked for.
     */
    private boolean topUpIfShort() {
        VideoGroup group = mLastGroup;
        List<Video> anchor = group != null ? group.getVideos() : null;
        if (mPresenter == null || mTornDown || isFinishing() || isDestroyed() || mLoadMoreFooter.isFailed() || playlistBusy()
                || anchor == null || anchor.isEmpty()) {
            return false;
        }
        if (!mTopUp.take(mVideos.size(), group.getNextPageKey() != null)) {
            return false;
        }
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("uploads-topup page=" + mTopUp.pages()
                + " items=" + mVideos.size());
        mPresenter.onScrollEnd(anchor.get(anchor.size() - 1));
        return true;
    }

    /** MEETUBE(playlist page): keep the server order as the source for Manual and pagination. */
    private void submitVideos() {
        // MEETUBE(playlist page): All | Videos | Shorts, then the sort, over the kept entries.
        boolean hasShorts = false;
        List<Video> filtered = new ArrayList<>();
        for (Video video : mVideos) {
            boolean isShort = ShortsFilter.isShort(video);
            hasShorts |= isShort;
            if (mFilter == PlaylistHeaderAdapter.FILTER_ALL
                    || isShort == (mFilter == PlaylistHeaderAdapter.FILTER_SHORTS)) {
                filtered.add(video);
            }
        }
        if (!hasShorts && mFilter != PlaylistHeaderAdapter.FILTER_ALL) {
            mFilter = PlaylistHeaderAdapter.FILTER_ALL;
            filtered = mVideos;
        }
        mHeaderAdapter.setFilter(mFilter, hasShorts);
        mAdapter.submitList(PlaylistSort.apply(filtered, mSort));
        Video first = null;
        for (Video video : mPlaylistVideos) {
            if (video.hasVideo()) {
                first = video;
                break;
            }
        }
        int count = playlistCount();
        mHeaderAdapter.setContents(first, count);
    }

    private void updatePlaylistVideos(VideoGroup group) {
        List<Video> videos = group.getVideos();
        if (videos == null) {
            return;
        }
        switch (group.getAction()) {
            case VideoGroup.ACTION_REPLACE:
                mPlaylistVideos.clear();
                mPlaylistVideos.addAll(videos);
                break;
            case VideoGroup.ACTION_PREPEND:
                mPlaylistVideos.addAll(0, videos);
                break;
            case VideoGroup.ACTION_REMOVE:
                mPlaylistVideos.removeAll(videos);
                break;
            case VideoGroup.ACTION_SYNC:
                for (Video video : videos) {
                    int index = mPlaylistVideos.indexOf(video);
                    if (index >= 0) {
                        mPlaylistVideos.set(index, video);
                    }
                }
                break;
            default:
                Set<Video> known = new HashSet<>(mPlaylistVideos);
                for (Video video : videos) {
                    if (known.add(video)) {
                        mPlaylistVideos.add(video);
                    }
                }
                break;
        }
    }

    private boolean playlistBusy() {
        return mBulkAction != BULK_NONE || mQueueingDownloads;
    }

    private void updatePlaylistBusy() {
        mHeaderAdapter.setSortState(mBulkAction == BULK_SORT ? mPendingSort : mSort, playlistBusy());
        mSwipe.setEnabled(!playlistBusy());
    }

    private void showSortMenu() {
        if (playlistBusy()) {
            return;
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(this);
        int generation = mPlaylistGeneration;
        for (int sort : new int[] {PlaylistSort.MANUAL, PlaylistSort.NEWEST, PlaylistSort.OLDEST, PlaylistSort.POPULAR}) {
            if (!PlaylistSort.available(mVideos, sort)) {
                continue;
            }
            dialog.appendSingleButton(UiOptionItem.from(getString(PlaylistSort.label(sort)), option -> {
                dialog.closeDialog();
                if (generation != mPlaylistGeneration || mTornDown) {
                    return;
                }
                if (sort == PlaylistSort.MANUAL) {
                    mSort = sort;
                    submitVideos();
                    updatePlaylistBusy();
                } else {
                    mPendingSort = sort;
                    loadPlaylist(BULK_SORT);
                }
            }));
        }
        dialog.showDialog(getString(R.string.mobile_playlist_sort));
    }

    /** MEETUBE(playlist page): confirm the full count, never just the currently visible page. */
    private void downloadAll() {
        if (playlistBusy() || !PlaylistHeaderAdapter.canDownload(mPresenter.getChannel())) {
            return;
        }
        // MEETUBE(playlist page): the whole list, or pick the videos on the grid.
        AppDialogPresenter dialog = AppDialogPresenter.instance(this);
        dialog.appendSingleButton(UiOptionItem.from(getString(R.string.mobile_playlist_download_all), option -> {
            dialog.closeDialog();
            startDownloadAll();
        }));
        dialog.appendSingleButton(UiOptionItem.from(getString(R.string.mobile_playlist_download_select), option -> {
            dialog.closeDialog();
            if (mSelection == null) {
                mSelection = new PlaylistSelection(findViewById(R.id.mobile_channel_uploads_root), mGrid, mAdapter,
                        this::downloadSelected);
            }
            mSelection.start();
        }));
        dialog.showDialog(getString(R.string.mobile_playlist_download_all));
    }

    private void downloadSelected(List<Video> videos) {
        int generation = mPlaylistGeneration;
        PlaylistDownloads.chooseQuality(this, quality -> {
            if (generation == mPlaylistGeneration && !mTornDown && !playlistBusy()) {
                mDownloadQuality = quality;
                queuePlaylistDownloads(videos);
            }
        });
    }

    private void startDownloadAll() {
        if (playlistBusy()) {
            return;
        }
        int count = playlistCount();
        if (count >= 0) {
            confirmDownloadAll(count);
        } else {
            // MEETUBE(playlist page): some openers have no total; count every page before asking.
            loadPlaylist(BULK_CONFIRM_DOWNLOAD);
        }
    }

    private void confirmDownloadAll(int count) {
        int generation = mPlaylistGeneration;
        AppDialogUtil.showConfirmationDialog(this, getString(R.string.mobile_playlist_download_confirm, count), () -> {
            if (generation != mPlaylistGeneration || mTornDown) {
                return;
            }
            PlaylistDownloads.chooseQuality(this, quality -> {
                if (generation == mPlaylistGeneration && !mTornDown) {
                    mDownloadQuality = quality;
                    loadPlaylist(BULK_DOWNLOAD);
                }
            });
        });
    }

    private int playlistCount() {
        Video opener = mPresenter != null ? mPresenter.getChannel() : null;
        if (opener == null) {
            return -1;
        }
        if (opener.playlistInfo != null && opener.playlistInfo.getSize() > 0
                && (opener.playlistInfo.getPlaylistId() == null
                || opener.playlistInfo.getPlaylistId().equals(opener.getPlaylistId()))) {
            return opener.playlistInfo.getSize();
        }
        Matcher count = PLAYLIST_COUNT.matcher(opener.badge != null ? opener.badge : "");
        if (count.matches()) {
            try {
                return Integer.parseInt(count.group(1).replace(",", "").replace("\u00a0", "").replace(" ", ""));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return mLastGroup != null && mLastGroup.getNextPageKey() == null ? mPlaylistVideos.size() : -1;
    }

    @Nullable
    private Video continuationAnchor() {
        List<Video> videos = mLastGroup != null ? mLastGroup.getVideos() : null;
        return videos != null && !videos.isEmpty() ? videos.get(videos.size() - 1) : null;
    }

    private void loadPlaylist(int action) {
        if (playlistBusy() || mTornDown) {
            return;
        }
        mBulkAction = action;
        mBulkPageKeys.clear();
        mLoadMoreFooter.setFailed(false);
        updatePlaylistBusy();
        mGrid.post(this::continuePlaylistLoad);
    }

    private void continuePlaylistLoad() {
        if (mBulkAction == BULK_NONE || mTornDown || mPageLoading) {
            return;
        }
        if (mLoadMoreFooter.isFailed() || mLastGroup == null) {
            failPlaylistLoad();
            return;
        }
        String key = mLastGroup.getNextPageKey();
        if (key != null) {
            Video anchor = continuationAnchor();
            if (anchor == null || !mBulkPageKeys.add(key)) {
                mLoadMoreFooter.setFailed(true);
                failPlaylistLoad();
                return;
            }
            // MEETUBE(playlist page): use the presenter's continuation, anchored in server order.
            mPresenter.onScrollEnd(anchor);
            return;
        }
        int action = mBulkAction;
        mBulkAction = BULK_NONE;
        mBulkPageKeys.clear();
        if (action == BULK_SORT) {
            mSort = mPendingSort;
            submitVideos();
        }
        updatePlaylistBusy();
        if (action == BULK_CONFIRM_DOWNLOAD) {
            confirmDownloadAll(mPlaylistVideos.size());
        } else if (action == BULK_DOWNLOAD) {
            queuePlaylistDownloads(new ArrayList<>(mPlaylistVideos));
        }
    }

    private void queuePlaylistDownloads(List<Video> videos) {
        mQueueingDownloads = true;
        updatePlaylistBusy();
        int generation = mPlaylistGeneration;
        mDownloadTask = PlaylistDownloads.enqueue(this, videos, mDownloadQuality,
                (queued, unavailable) -> {
                    if (generation != mPlaylistGeneration || mTornDown) {
                        return;
                    }
                    mDownloadTask = null;
                    mQueueingDownloads = false;
                    updatePlaylistBusy();
                    String result = unavailable == 0 ? getString(R.string.mobile_playlist_download_queued, queued)
                            : getString(R.string.mobile_playlist_download_skipped, queued, unavailable);
                    MobileSnackbar.show(this, result, null, null);
                });
    }

    private void failPlaylistLoad() {
        if (mBulkAction != BULK_NONE) {
            mBulkAction = BULK_NONE;
            mBulkPageKeys.clear();
            updatePlaylistBusy();
            MobileSnackbar.show(this, R.string.mobile_playlist_load_failed);
        }
    }

    private void cancelPlaylistWork() {
        mPlaylistGeneration++;
        mBulkAction = BULK_NONE;
        mBulkPageKeys.clear();
        mQueueingDownloads = false;
        if (mDownloadTask != null) {
            mDownloadTask.dispose();
            mDownloadTask = null;
        }
        updatePlaylistBusy();
    }

    private int computeSpanCount() {
        return com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }
        // MEETUBE(playlist page): back from + search with videos added: show them.
        String playlistId = openerPlaylistId();
        if (playlistId != null && com.newtube.mobile.ui.search.MobileSearchActivity.consumeAdded(playlistId)
                && mPresenter != null && !playlistBusy()) {
            mPresenter.reload(!mVideos.isEmpty());
        }
        // Last-resumed host wins: a video opened from this list minimizes back onto it.
        MiniPlayerBridge.registerMiniHost(this);
        if (mMiniPlayer != null) {
            mMiniPlayer.sync(false);
        }
    }

    @Override
    protected void onPause() {
        // Free the mini bar's video surface whenever this screen leaves the foreground - the
        // playback activity may be about to re-claim it (expand / new video).
        // NEWTUBE(motion): a docked card stays up frozen until the player covers it.
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostPause();
        }
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostStop();
        }
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);
        mTornDown = true;
        cancelPlaylistWork(); // Glide drops the backdrop's load itself; Glide.with() here throws on a destroyed activity

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        return mMiniPlayer != null && mMiniPlayer.prepareForHandoff(onDrawn);
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return ChannelUploadsView.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        // Overlay card (mobile_mini_player_overlay.xml) sits flush at the content bottom.
        return 0;
    }

    private void handleBack() {
        if (mSelection != null && mSelection.isActive()) {
            mSelection.stop();
            return;
        }
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }

        finish();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(
                    com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(newConfig));
        }
    }

    // ---------------------------------------------------------------------------------
    // ChannelUploadsView
    // ---------------------------------------------------------------------------------

    @Override
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            if (mTornDown) {
                return;
            }
            if (group.getTitle() != null && !group.getTitle().isEmpty()) {
                mTitleView.setText(group.getTitle());
            }

            updatePlaylistVideos(group);
            if (mHeaderAdapter.hasHeader()) {
                markPlaylistShorts(group.getVideos());
            }
            // NEWTUBE(shorts): Shorts are dropped, except on a playlist page, where the pills sort them out.
            List<Video> shown = mHeaderAdapter.hasHeader() && group.getVideos() != null
                    ? new ArrayList<>(group.getVideos()) : ShortsFilter.withoutShorts(group.getVideos());
            if (group.getAction() != VideoGroup.ACTION_REMOVE && group.getAction() != VideoGroup.ACTION_SYNC) {
                mLastGroup = group;
                int dropped = (group.getVideos() != null ? group.getVideos().size() : 0)
                        - (shown != null ? shown.size() : 0);
                mTopUp.onPageLanded(dropped);
                mShortsEmptied |= dropped > 0;
            }
            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mVideos.clear();
                    mVideos.addAll(shown);
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mVideos.addAll(0, shown);
                    break;
                case VideoGroup.ACTION_REMOVE:
                    mVideos.removeAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_SYNC:
                    syncVideos(group.getVideos());
                    // NEWTUBE(#45): a SYNC item was mutated in place (Original titles, DeArrow),
                    // so submitList() sees the same objects and never rebinds - rebind it here.
                    mAdapter.refreshItems(group.getVideos());
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    appendNew(shown);
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to fire again at the new size
            submitVideos();
            updatePlayAllVisibility();
            checkOwnPlaylist();

            if (!mVideos.isEmpty()) {
                hideLoadState();
            }
        });
    }

    private void appendNew(List<Video> videos) {
        Set<Video> known = new HashSet<>(mVideos);
        for (Video video : videos) {
            if (known.add(video)) {
                mVideos.add(video);
            }
        }
    }

    private void syncVideos(List<Video> videos) {
        for (Video video : videos) {
            int idx = mVideos.indexOf(video);
            if (idx >= 0) {
                mVideos.set(idx, video);
            }
        }
    }

    @Override
    public void clear() {
        runOnUiThread(() -> {
            cancelPlaylistWork();
            mVideos.clear();
            mPlaylistVideos.clear();
            mSort = PlaylistSort.MANUAL;
            mPageLoading = false;
            mBackdrop.clear();
            mLastPaginationTriggerCount = -1;
            mTopUp.clear();
            mLastGroup = null;
            mShortsEmptied = false;
            mLoadMoreFooter.setFailed(false);
            mAdapter.submitList(new ArrayList<>());
            mHeaderAdapter.setContents(null, 0);
            updatePlaylistBusy();
            updatePlayAllVisibility();
            hideLoadState();
        });
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            mPageLoading = show;
            // MEETUBE(playlist page): bulk paging uses the chip spinner and continues after Rx completes.
            if (mBulkAction != BULK_NONE) {
                mProgressBar.setVisibility(View.GONE);
                if (!show) {
                    mGrid.post(this::continuePlaylistLoad);
                }
                return;
            }
            if (!show && topUpIfShort()) {
                return; // NEWTUBE(shorts): another page is on its way - still loading
            }
            // Pull-to-refresh draws its own spinner; don't stack the centered one over it.
            mProgressBar.setVisibility(show && !mSwipe.isRefreshing() ? View.VISIBLE : View.GONE);
            if (!show) {
                mSwipe.setRefreshing(false);
                showEmptyIfShortsLeftNothing();
            }
        });
    }

    /**
     * NEWTUBE(shorts): the list loaded, but every card was a Short and no more pages are coming -
     * the Home-style empty state, never a blank page (#63). A list of only Shorts should not be
     * reachable at all (ShortsFilter drops the tiles that lead here); this is the safety net.
     */
    private void showEmptyIfShortsLeftNothing() {
        if (mShortsEmptied && mVideos.isEmpty() && !mLoadState.isShowing()) {
            mGrid.setVisibility(View.GONE);
            mLoadState.show(LoadFailure.EMPTY);
            applyToolbarTitleAlpha();
        }
    }

    /**
     * NEWTUBE(page-load-errors): the first load put nothing on screen. Items still on screen (a
     * pull-to-refresh that failed) stay - stale items beat a full-page error, as on Home - with a
     * snackbar saying the refresh failed. Otherwise the grid, playlist header included, gives way
     * to the Home-style empty state with Try again; the toolbar keeps the name.
     */
    @Override
    public void showLoadFailure(int state) {
        runOnUiThread(() -> {
            failPlaylistLoad();
            mSwipe.setRefreshing(false);
            mProgressBar.setVisibility(View.GONE);

            if (!mVideos.isEmpty()) {
                if (state != LoadFailure.EMPTY) {
                    com.newtube.mobile.ui.common.MobileSnackbar.show(this, state == LoadFailure.NO_CONNECTION ? R.string.mobile_empty_no_connection : R.string.mobile_refresh_error);
                }
                return;
            }

            mGrid.setVisibility(View.GONE);
            mLoadState.show(state);
            applyToolbarTitleAlpha();
        });
    }

    /** NEWTUBE(page-load-errors): a next page failed - footer row with Try again, items stay. */
    @Override
    public void showLoadMoreFailure() {
        runOnUiThread(() -> {
            mLoadMoreFooter.setFailed(true);
            failPlaylistLoad();
        });
    }
}
