package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.RowPresenter.ViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.adapter.VideoGroupObjectAdapter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ChannelHeaderPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ChannelHeaderPresenter.ChannelHeaderCallback;
import com.liskovsoft.smartyoutubetv2.tv.presenter.HomeChipPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ShortsCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.CustomListRowPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.OnItemLongPressedListener;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.VideoSection;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseGlassStyle;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.UriBackgroundManager;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class MultipleRowsFragment extends RowsSupportFragment implements VideoSection {
    private static final String TAG = MultipleRowsFragment.class.getSimpleName();
    private UriBackgroundManager mBackgroundManager;
    private ArrayObjectAdapter mRowsAdapter;
    private ListRowPresenter mRowPresenter;
    private Map<Integer, VideoGroupObjectAdapter> mVideoGroupAdapters;
    private final List<VideoGroup> mPendingUpdates = new ArrayList<>();
    private VideoGroupPresenter mMainPresenter;
    private VideoCardPresenter mCardPresenter;
    private ShortsCardPresenter mShortsPresenter;
    private final HomeChipPresenter mHomeChipPresenter = new HomeChipPresenter(); // MEETUBE: only Home's navigation row.
    private int mSelectedRowIndex = -1;
    private boolean mHomeFirstVideoPending; // MEETUBE: wait for asynchronous content, once per fresh load.
    private int mRowsAlignment = -1;
    private ChannelHeaderCallback mChannelHeaderCallback;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mMainPresenter = getMainPresenter();
        mCardPresenter = new VideoCardPresenter(BrowseGlassStyle.isBrowse(this));
        mShortsPresenter = new ShortsCardPresenter(BrowseGlassStyle.isBrowse(this));
        mBackgroundManager = ((LeanbackActivity) getActivity()).getBackgroundManager();

        setupAdapter();
        setupEventListeners();
        applyPendingUpdates();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // MEETUBE: measure the first row's real header height; leave all video presenters unchanged.
        view.getViewTreeObserver().addOnPreDrawListener(() -> !updateHomeAlignment());
        restorePosition();
    }

    @Override
    public void setAlignment(int alignment) {
        mRowsAlignment = alignment;
        super.setAlignment(alignment);
    }

    private boolean updateHomeAlignment() {
        if (getVerticalGridView() == null || mRowsAlignment < 0) {
            return false;
        }
        int alignment = mRowsAlignment;
        if (hasHomeChips() && getSelectedPosition() <= 1 && getParentFragment() != null &&
                getParentFragment().getView() != null) {
            View title = getParentFragment().getView().findViewById(R.id.browse_title_group);
            if (title == null || title.getHeight() == 0) {
                return false;
            }
            int[] titleLocation = new int[2];
            int[] rowsLocation = new int[2];
            title.getLocationOnScreen(titleLocation);
            getVerticalGridView().getLocationOnScreen(rowsLocation);
            // MEETUBE: 14dp below the visible top bar, excluding its trailing layout padding.
            alignment = titleLocation[1] - rowsLocation[1] + title.getHeight() - title.getPaddingBottom() +
                    Math.round(14 * title.getResources().getDisplayMetrics().density);
            if (getSelectedPosition() == 1) {
                ViewHolder chips = findRowViewHolderByPosition(0);
                ViewHolder first = findRowViewHolderByPosition(1);
                if (!(chips instanceof ListRowPresenter.ViewHolder) || !(first instanceof ListRowPresenter.ViewHolder)) {
                    return false;
                }
                Rect bounds = new Rect();
                getVerticalGridView().offsetDescendantRectToMyCoords(
                        ((ListRowPresenter.ViewHolder) first).getGridView(), bounds);
                alignment += bounds.top;
                bounds.setEmpty();
                getVerticalGridView().offsetDescendantRectToMyCoords(
                        ((ListRowPresenter.ViewHolder) chips).getGridView(), bounds);
                alignment -= bounds.top;
            }
        }
        if (getVerticalGridView().getWindowAlignmentOffset() == alignment) {
            return false;
        }
        getVerticalGridView().setWindowAlignmentOffset(alignment);
        return true;
    }

    private boolean hasHomeChips() {
        return mRowsAdapter != null && mRowsAdapter.size() > 0 &&
                mRowsAdapter.get(0) instanceof HomeChipPresenter.ChipRow;
    }

    @Override
    protected int getLastTitleRow() {
        return hasHomeChips() ? 1 : 0; // MEETUBE: the top bar stays over the chips and the first video row
    }

    public boolean isHomeVideoReady() {
        return hasHomeChips() && mRowsAdapter.size() > 1 && !mHomeFirstVideoPending;
    }

    protected void addHeader(ChannelHeaderCallback callback) {
        mChannelHeaderCallback = callback;
    }

    protected abstract VideoGroupPresenter getMainPresenter();

    private void applyPendingUpdates() {
        // prevent modification within update method
        List<VideoGroup> copyArray = new ArrayList<>(mPendingUpdates);

        mPendingUpdates.clear();

        for (VideoGroup group : copyArray) {
            update(group);
        }
    }

    private void setupAdapter() {
        if (mVideoGroupAdapters == null) {
            mVideoGroupAdapters = new HashMap<>();
        }

        if (mRowsAdapter == null) {
            mRowPresenter = BrowseGlassStyle.isBrowse(this) ? BrowseGlassStyle.rowPresenter() : new CustomListRowPresenter();
            mRowPresenter.enableChildRoundedCorners(getMainUIData().isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS));
            if (BrowseGlassStyle.isBrowse(this)) {
                // Our card owns its 12dp outline; don't let the shared row helper replace it.
                mRowPresenter.enableChildRoundedCorners(false);
                mRowPresenter.setShadowEnabled(false);
                mRowPresenter.setHeaderPresenter(BrowseGlassStyle.rowHeaderPresenter());
            }

            ClassPresenterSelector presenterSelector = new ClassPresenterSelector();
            presenterSelector.addClassPresenter(ListRow.class, mRowPresenter);
            // MEETUBE: a separate row presenter prevents chip sizing/focus rules affecting video cards.
            presenterSelector.addClassPresenter(HomeChipPresenter.ChipRow.class, HomeChipPresenter.rowPresenter());
            presenterSelector.addClassPresenter(ChannelHeaderCallback.class, new ChannelHeaderPresenter());

            mRowsAdapter = new ArrayObjectAdapter(presenterSelector);
            setAdapter(mRowsAdapter);
        }
    }

    private void setupEventListeners() {
        setOnItemViewClickedListener(new ItemViewClickedListener());
        setOnItemViewSelectedListener(new ItemViewSelectedListener());
        mCardPresenter.setOnItemViewLongPressedListener(new ItemViewLongPressedListener());
        mShortsPresenter.setOnItemViewLongPressedListener(new ItemViewLongPressedListener());
    }

    @Override
    public void clear() {
        if (mRowsAdapter != null) {
            mRowsAdapter.clear();
            if (mChannelHeaderCallback != null) {
                mRowsAdapter.add(mChannelHeaderCallback);
            }
        }

        if (mVideoGroupAdapters != null) {
            mVideoGroupAdapters.clear();
        }

        // Reset the position (bug appeared after fragment been reused)
        setPosition(mChannelHeaderCallback != null ? 1 : 0);
    }

    private void removeByIndex(int idx) {
        if (mRowsAdapter != null && mRowsAdapter.size() > idx) {
            ListRow row = (ListRow) mRowsAdapter.get(idx);
            mRowsAdapter.remove(row);
            VideoGroupObjectAdapter group = (VideoGroupObjectAdapter) row.getAdapter();
            mVideoGroupAdapters.values().remove(group);
        }
    }

    private void removeById(int id) {
        if (mRowsAdapter != null) {
            VideoGroupObjectAdapter needed = mVideoGroupAdapters.get(id);
            for (int i = 0; i < mRowsAdapter.size(); i++) {
                Object row = mRowsAdapter.get(i);

                if (row instanceof ListRow) {
                    VideoGroupObjectAdapter adapter = (VideoGroupObjectAdapter) ((ListRow) row).getAdapter();
                    if (adapter == needed) {
                        mRowsAdapter.remove(row);
                        mVideoGroupAdapters.remove(id);
                    }
                }
            }
        }
    }

    private int findPositionById(int id) {
        if (mRowsAdapter != null) {
            VideoGroupObjectAdapter needed = mVideoGroupAdapters.get(id);
            for (int i = 0; i < mRowsAdapter.size(); i++) {
                Object row = mRowsAdapter.get(i);

                if (row instanceof ListRow) {
                    VideoGroupObjectAdapter adapter = (VideoGroupObjectAdapter) ((ListRow) row).getAdapter();
                    if (adapter == needed) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    private boolean isComputingLayout(VideoGroup group) {
        int action = group.getAction();

        // Attempt to fix: IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling
        if ((action == VideoGroup.ACTION_SYNC || action == VideoGroup.ACTION_REPLACE) && getVerticalGridView() != null) {
            if (getVerticalGridView().isComputingLayout()) {
                return true;
            }
            int position = findPositionById(group.getId());
            if (position != -1) {
                RecyclerView.ViewHolder viewHolder = getVerticalGridView().findViewHolderForAdapterPosition(position);
                if (viewHolder != null) {
                    Object nestedRecyclerView = Helpers.getField(viewHolder, "mNestedRecyclerView");
                    if (nestedRecyclerView instanceof WeakReference) {
                        Object recyclerView = ((WeakReference<?>) nestedRecyclerView).get();
                        return recyclerView instanceof RecyclerView && ((RecyclerView) recyclerView).isComputingLayout();
                    }
                }
            }
        }

        return false;
    }

    @Override
    public boolean isEmpty() {
        if (mRowsAdapter == null) {
            return mPendingUpdates.isEmpty();
        }

        return mRowsAdapter.size() == 0;
    }

    @Override
    public void update(VideoGroup group) {
        if (isComputingLayout(group)) {
            return;
        }

        if (mVideoGroupAdapters == null) {
            mPendingUpdates.add(group);
            return;
        }

        // Correct position depending on the search bar presence
        if (group.getPosition() != -1 && mChannelHeaderCallback != null) {
            group.setPosition(group.getPosition() + 1);
        }

        int action = group.getAction();

        if (action == VideoGroup.ACTION_REPLACE) {
            if (BrowsePresenter.isHomeChipGroup(group)) {
                mHomeFirstVideoPending = group.getPosition() != 0;
                if (replaceHomeFeed(group)) {
                    return; // MEETUBE: preserve chips; fresh loads still wait for video row one.
                }
            }
            if (group.getPosition() == -1 || BrowsePresenter.isHomeChipGroup(group)) {
                clear(); // MEETUBE: a changed chip list must also discard the old feed.
            } else {
                removeById(group.getId());
            }
        } else if (action == VideoGroup.ACTION_REMOVE) {
            VideoGroupObjectAdapter adapter = mVideoGroupAdapters.get(group.getId());
            if (adapter != null) {
                adapter.remove(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_SYNC) {
            VideoGroupObjectAdapter adapter = mVideoGroupAdapters.get(group.getId());
            if (adapter != null) {
                freeze(true);
                adapter.sync(group);
                freeze(false);
            }
            return;
        }

        if (group.isEmpty()) {
            return;
        }

        VideoGroupObjectAdapter existingAdapter = GridFragmentHelper.findRelatedAdapter(mVideoGroupAdapters, group, this::freeze);

        if (existingAdapter == null) {
            HeaderItem rowHeader = new HeaderItem(group.getTitle());
            int videoGroupId = group.getId(); // Create unique int from category.

            // MEETUBE: use the normal row-adapter presenter choice for the special chip Videos.
            boolean chips = BrowsePresenter.isHomeChipGroup(group);
            VideoGroupObjectAdapter videoGroupAdapter = new VideoGroupObjectAdapter(group,
                    chips ? mHomeChipPresenter : group.isShorts() ? mShortsPresenter : mCardPresenter);

            mVideoGroupAdapters.put(videoGroupId, videoGroupAdapter);

            ListRow row = chips ? new HomeChipPresenter.ChipRow(videoGroupAdapter) : new ListRow(rowHeader, videoGroupAdapter);

            if (group.getPosition() == -1 || group.getPosition() > mRowsAdapter.size()) {
                mRowsAdapter.add(row);
            } else {
                mRowsAdapter.add(group.getPosition(), row);
            }
        } else {
            Log.d(TAG, "Continue row %s %s", group.getTitle(), System.currentTimeMillis());

            freeze(true);

            existingAdapter.add(group); // continue

            freeze(false);
        }

        if (BrowsePresenter.isHomeChipGroup(group)) {
            setPosition(mHomeFirstVideoPending ? 1 : 0);
        }
        restorePosition();
    }

    // MEETUBE: preserve row zero across feed switches/refreshes; rebuild it only if enabled chips changed.
    private boolean replaceHomeFeed(VideoGroup group) {
        VideoGroupObjectAdapter chips = mVideoGroupAdapters.get(group.getId());
        if (chips == null || chips.size() != group.getVideos().size()) {
            return false;
        }
        for (int i = 0; i < chips.size(); i++) {
            Video oldChip = (Video) chips.get(i);
            Video newChip = group.getVideos().get(i);
            if (!TextUtils.equals(oldChip.channelId, newChip.channelId) ||
                    !TextUtils.equals(oldChip.getTitle(), newChip.getTitle())) {
                return false;
            }
        }
        for (int i = mRowsAdapter.size() - 1; i > 0; i--) {
            removeByIndex(i);
        }
        chips.notifyItemRangeChanged(0, chips.size());
        setPosition(mHomeFirstVideoPending ? 1 : 0);
        return true;
    }

    private void restorePosition() {
        if (mHomeFirstVideoPending && hasHomeChips() && mRowsAdapter.size() > 1 && getVerticalGridView() != null) {
            // MEETUBE: select the first card only after its content row exists; chip switches skip this.
            ListRowPresenter.SelectItemViewHolderTask firstVideo = new ListRowPresenter.SelectItemViewHolderTask(0);
            firstVideo.setSmoothScroll(false);
            setSelectedPosition(1, false, firstVideo);
            mHomeFirstVideoPending = false;
            mSelectedRowIndex = -1;
            return;
        }
        setPosition(mSelectedRowIndex);

        // Maybe we don't need to load next group since all rows already fetched?
    }

    @Override
    public int getPosition() {
        return getSelectedPosition();
    }

    @Override
    public void setPosition(int index) {
        if (index < 0) {
            return;
        }

        if (mRowsAdapter != null && index < mRowsAdapter.size()) {
            setSelectedPosition(index, false);
            mSelectedRowIndex = -1;
        } else {
            mSelectedRowIndex = index;
        }
    }

    @Override
    public void selectItem(Video item) {
        // NOP
    }

    /**
     * Disable scrolling on partially updated rows. This prevent cards from misbehaving.
     */
    private void freeze(boolean freeze) {
        // Disable scrolling on partially updated rows. This prevent controls from misbehaving.
        if (mRowPresenter != null) {
            ViewHolder vh = getRowViewHolder(getSelectedPosition());
            if (vh instanceof ListRowPresenter.ViewHolder) {
                mRowPresenter.freeze(vh, freeze);
            }
        }
    }

    private MainUIData getMainUIData() {
        return MainUIData.instance(getContext());
    }

    private final class ItemViewLongPressedListener implements OnItemLongPressedListener {
        @Override
        public void onItemLongPressed(Presenter.ViewHolder itemViewHolder, Object item) {

            if (item instanceof Video) {
                mMainPresenter.onVideoItemLongClicked((Video) item);
            } else {
                Toast.makeText(getActivity(), item.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class ItemViewClickedListener implements androidx.leanback.widget.OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {

            if (item instanceof Video) {
                mMainPresenter.onVideoItemClicked((Video) item);
            } else {
                Toast.makeText(getActivity(), item.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof Video && BrowsePresenter.isHomeChipItem((Video) item)) {
                return; // MEETUBE: navigation pills do not change the backdrop or trigger continuation.
            }
            mBackgroundManager.setBackgroundFrom(item instanceof Video ? (Video) item : null);
            if (item instanceof Video) {
                mMainPresenter.onVideoItemSelected((Video) item);

                checkScrollEnd((Video)item);
            }
        }

        private void checkScrollEnd(Video item) {
            for (VideoGroupObjectAdapter adapter : mVideoGroupAdapters.values()) {
                int index = adapter.indexOf(item);

                if (index != -1) {
                    int size = adapter.size();
                    if (index > (size - ViewUtil.ROW_SCROLL_CONTINUE_NUM)) {
                        mMainPresenter.onScrollEnd((Video) adapter.get(size - 1));
                    }
                    break;
                }
            }
        }
    }
}
