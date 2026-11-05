package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.app.HeadersSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.DiffCallback;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.PageRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;
import androidx.leanback.widget.SearchOrbView;
import androidx.leanback.widget.TitleHelper;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SplashPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.CrashRestorer;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.dialog.ErrorDialogFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.MultipleRowsFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.headers.ExtendedHeadersSupportFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.SettingsSection;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.misc.ProgressBarManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.browse.NavigateTitleView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Main class to show BrowseFragment with header and rows of videos
 */
public class BrowseFragment extends BrowseSupportFragment implements BrowseView {
    private static final String TAG = BrowseFragment.class.getSimpleName();
    private ArrayObjectAdapter mSectionRowAdapter;
    private BrowsePresenter mBrowsePresenter;
    // MEETUBE: presenter indices include hidden settings/explore sections; sidebar rows exclude them.
    private final List<PageRow> mSectionRows = new ArrayList<>();
    // MEETUBE: Settings and direct Explore links (when Home is disabled) use a visible sidebar anchor.
    private PageRow mHiddenRow;
    private long mHiddenReturnSectionId = -1;
    private BrowseSectionFragmentFactory mSectionFragmentFactory;
    private Handler mHandler;
    private ProgressBarManager mProgressBarManager;
    private boolean mIsFragmentCreated;
    private boolean mFocusOnContent;
    private CrashRestorer mCrashRestorer;
    private BrowseGlassDecoration mGlassDecoration;
    private boolean mGlassEnabled = true;
    private long mGlassSelectedHeaderId = -1;
    private final Map<Integer, BrowseHeaderItemPresenter> mGlassHeaderPresenters = new HashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);

        if (getContext() == null) {
            return;
        }
        
        mCrashRestorer = new CrashRestorer(getContext(), savedInstanceState);
        mIsFragmentCreated = true;

        mHandler = new Handler();
        mBrowsePresenter = BrowsePresenter.instance(getContext());
        mBrowsePresenter.setView(this);
        mProgressBarManager = new ProgressBarManager();

        setupAdapter();
        setupFragmentFactory();
        setupUi();

        enableMainFragmentScaling(false);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // Called when the activity is paused
        mCrashRestorer.persistHeaderIndex(outState, indexOfSection(getSelectedHeaderId()));
        mCrashRestorer.persistVideo(outState, mBrowsePresenter.getCurrentVideo());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);

        mProgressBarManager.setRootView((ViewGroup) root);
        if (Build.VERSION.SDK_INT >= 23) { // the loader's keyframe animators need API 23
            // MEETUBE: the phone app's neon ring loader instead of the holo spinner
            ProgressBar loader = new ProgressBar(root.getContext());
            loader.setIndeterminate(true);
            loader.setIndeterminateDrawable(ContextCompat.getDrawable(root.getContext(), R.drawable.avd_neon_ring));
            int size = Math.round(72 * root.getResources().getDisplayMetrics().density);
            ((ViewGroup) root).addView(loader, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            mProgressBarManager.setProgressBarView(loader);
        }

        return root;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setupEventListeners();

        prepareEntranceTransition();

        mBrowsePresenter.onViewInitialized();

        // Restore state after crash
        mCrashRestorer.restoreHeader((idx, video) -> {
            selectSection(idx, true);
            selectSectionItem(video);
        });
        mCrashRestorer.restorePlayback();
    }

    @Override
    public HeadersSupportFragment onCreateHeadersSupportFragment() {
        return new BrowseHeadersSupportFragment();
    }

    void onHeadersViewCreated(View headers) {
        mGlassDecoration = new BrowseGlassDecoration(requireView(), headers);
        mGlassDecoration.setSidebarShown(isShowingHeaders(), false);
        updateGlassAppearance();
    }

    private void setupEventListeners() {
        getHeadersSupportFragment().setOnHeaderClickedListener(
                (viewHolder, row) -> {
                    long headerId = row.getHeaderItem().getId();
                    int newPosition = indexOf(headerId);

                    if (getHeadersSupportFragment().getSelectedPosition() != newPosition) {
                        // touch screen support
                        getHeadersSupportFragment().setSelectedPosition(newPosition);
                    } else {
                        // update section when clicked or pressed
                        if (headerId == MediaGroup.TYPE_HOME) {
                            mFocusOnContent = true; // MEETUBE: complete the handoff when video's row arrives.
                        }
                        mBrowsePresenter.onSectionFocused((int) headerId);
                        startHeadersTransitionSafe(false);
                    }
                }
        );

        ((ExtendedHeadersSupportFragment) getHeadersSupportFragment()).setOnHeaderLongPressedListener(
                (viewHolder, row) -> {
                    long headerId = row.getHeaderItem().getId();

                    mBrowsePresenter.onSectionLongPressed((int) headerId);
                }
        );

        setOnSearchClickedListener(view -> SearchPresenter.instance(getContext()).startSearch(null));
        setBrowseTransitionListener(new BrowseTransitionListener() {
            @Override
            public void onHeadersTransitionStart(boolean withHeaders) {
                if (mGlassDecoration != null) {
                    mGlassDecoration.setSidebarShown(withHeaders, true);
                }
                if (withHeaders) {
                    returnFromSettings(false);
                }
            }
        });
        updateSettingsButton();
    }

    private void updateSettingsButton() {
        if (getTitleView() instanceof NavigateTitleView) {
            ((NavigateTitleView) getTitleView()).setBrowseTitleEnabled(true); // MEETUBE: greeting is browse-only.
            boolean enabled = indexOfSection(MediaGroup.TYPE_SETTINGS) != -1 &&
                    SidebarService.instance(getContext()).isSettingsSectionEnabled();
            ((NavigateTitleView) getTitleView()).setOnSettingsClickedListener(enabled ?
                    view -> selectSection(indexOfSection(MediaGroup.TYPE_SETTINGS), true) : null);
        }
    }

    boolean returnFromSettings(boolean focusOnContent) {
        if (mHiddenRow == null) {
            return false;
        }
        int index = indexOfSection(mHiddenReturnSectionId);
        if (index == -1) {
            for (int i = 0; i < mSectionRows.size(); i++) {
                // MEETUBE: return to a visible sidebar item, not another hidden Explore feed.
                long id = mSectionRows.get(i).getHeaderItem().getId();
                if (id != MediaGroup.TYPE_SETTINGS && !BrowsePresenter.isExploreSection(id)) {
                    index = i;
                    break;
                }
            }
        }
        if (index == -1) {
            return false;
        }
        mHiddenRow = null;
        selectSection(index, focusOnContent);
        return true;
    }

    private void setupFragmentFactory() {
        mSectionFragmentFactory = new BrowseSectionFragmentFactory(
                (row) -> {
                    int sectionId = (int) row.getHeaderItem().getId();
                    if (sectionId != MediaGroup.TYPE_HOME) {
                        focusOnContentIfNeeded();
                    }
                    mBrowsePresenter.onSectionFocused(sectionId);
                    if (sectionId == MediaGroup.TYPE_HOME) {
                        // MEETUBE: populate the chips before handing focus to Home's content.
                        focusOnContentIfNeeded();
                    }
                }
        );

        getMainFragmentRegistry().registerFragment(PageRow.class, mSectionFragmentFactory);
        mSectionFragmentFactory.setOnSectionCreatedListener((type, headerId) -> {
            boolean sectionChanged = mGlassSelectedHeaderId != headerId;
            mGlassSelectedHeaderId = headerId;
            updateGlassAppearance();
            // MEETUBE: Settings keeps the last browse backdrop instead of clearing it.
            if (sectionChanged && type != BrowseSection.TYPE_SETTINGS_GRID && getActivity() instanceof LeanbackActivity) {
                ((LeanbackActivity) getActivity()).getBackgroundManager().setBackgroundFrom(null);
            }
        });
    }

    private int indexOf(long headerId) {
        for (int i = 0; i < mSectionRowAdapter.size(); i++) {
            PageRow row = (PageRow) mSectionRowAdapter.get(i);
            HeaderItem header = row.getHeaderItem();
            if (header.getId() == headerId) {
                return i;
            }
        }

        return -1;
    }

    private int indexOfSection(long headerId) {
        for (int i = 0; i < mSectionRows.size(); i++) {
            if (mSectionRows.get(i).getHeaderItem().getId() == headerId) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected boolean isTitleKeptWithHeaders() {
        return mGlassEnabled; // MEETUBE: Settings, Search and the account sit on the glass sidebar, always reachable
    }

    @Override
    protected Object getMainFragmentRow(Object row) {
        return mHiddenRow != null ? mHiddenRow : row;
    }

    private void setupAdapter() {
        // Map category results from the database to ListRow objects.
        // This Adapter is used to render the MainFragment sidebar labels.
        mSectionRowAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(mSectionRowAdapter);
    }

    private void setupUi() {
        if (getContext() == null) {
            return;
        }

        setHeadersState(HEADERS_ENABLED);
        setHeadersTransitionOnBackEnabled(true);

        int brandColorRes = Helpers.getThemeAttr(getContext(), R.attr.brandColor);
        int brandAccentColorRes = Helpers.getThemeAttr(getContext(), R.attr.brandAccentColor);

        updateBadge();

        // This title replaces badge in case one is null
        //setTitle(getString(R.string.browse_title));

        // Set fastLane (or headers) background color
        setBrandColor(ContextCompat.getColor(getContext(), brandColorRes));

        // Set search icon color.
        setSearchAffordanceColor(ContextCompat.getColor(getContext(), brandAccentColorRes));

        setHeaderPresenterSelector(new PresenterSelector() {
            @Override
            public Presenter getPresenter(Object o) {
                BrowseHeaderItemPresenter presenter = mGlassHeaderPresenters.get(o.hashCode());

                if (presenter == null) {
                    presenter = new BrowseHeaderItemPresenter(getHeaderResId(o), getIconUrl(o));
                    presenter.setGlassState(mGlassEnabled, mGlassSelectedHeaderId);
                    mGlassHeaderPresenters.put(o.hashCode(), presenter);
                }

                return presenter;
            }

            private int getHeaderResId(Object o) {
                if (o instanceof PageRow) {
                    HeaderItem header = ((PageRow) o).getHeaderItem();
                    return header instanceof SectionHeaderItem ?
                            BrowseGlassStyle.headerIcon(header.getId(), ((SectionHeaderItem) header).getResId()) : R.drawable.browse_ic_explore;
                }

                return -1;
            }

            private String getIconUrl(Object o) {
                if (o instanceof PageRow && ((PageRow) o).getHeaderItem() instanceof SectionHeaderItem) {
                    return ((SectionHeaderItem) ((PageRow) o).getHeaderItem()).getIconUrl();
                }

                return null;
            }
        });
    }

    private int getSelectedHeaderId() {
        if (mHiddenRow != null) {
            return (int) mHiddenRow.getHeaderItem().getId();
        }
        if (getSelectedPosition() < 0 || getSelectedPosition() >= mSectionRowAdapter.size()) {
            return -1;
        }

        long id = ((PageRow) mSectionRowAdapter.get(getSelectedPosition())).getHeaderItem().getId();
        return (int) id; // MEETUBE: no synthetic Explore header.
    }

    private void updateGlassAppearance() {
        if (getContext() == null || mGlassDecoration == null) {
            return;
        }
        mGlassDecoration.setEnabled(mGlassEnabled);
        int brandColor = ContextCompat.getColor(getContext(), Helpers.getThemeAttr(getContext(), R.attr.brandColor));
        int accentColor = ContextCompat.getColor(getContext(), Helpers.getThemeAttr(getContext(), R.attr.brandAccentColor));
        setBrandColor(mGlassEnabled ? android.graphics.Color.TRANSPARENT : brandColor);
        if (mGlassEnabled) {
            // MEETUBE: a glass search circle, brighter when focused
            setSearchAffordanceColors(new SearchOrbView.Colors(0x33FFFFFF, 0x66FFFFFF, android.graphics.Color.WHITE));
        } else {
            setSearchAffordanceColor(accentColor);
        }
        ((LeanbackActivity) requireActivity()).getBackgroundManager().setBrowseGlassEnabled(mGlassEnabled);
        for (BrowseHeaderItemPresenter presenter : mGlassHeaderPresenters.values()) {
            presenter.setGlassState(mGlassEnabled, mGlassSelectedHeaderId);
        }
    }
    
    public void updateErrorIfEmpty(ErrorFragmentData data) {
        mHandler.postDelayed(() -> showErrorIfEmpty(data), 500); // need delay because header may be not updated
    }

    @Override
    public void showError(ErrorFragmentData data) {
        replaceMainFragment(new ErrorDialogFragment(data));
    }

    private void showErrorIfEmpty(ErrorFragmentData data) {
        if (isEmpty()) {
            replaceMainFragment(new ErrorDialogFragment(data));
        }
    }

    private void replaceMainFragment(Fragment fragment) {
        //Object mainFragment = Helpers.getField(this,"mMainFragment");
        Fragment mainFragment = getMainFragment();

        if (mainFragment != null && fragment != null && mainFragment != fragment) {
            Helpers.setField(this, "mMainFragment", fragment);

            FragmentTransaction ft = getChildFragmentManager().beginTransaction();
            ft.replace(R.id.scale_frame, fragment);
            //mFocusOnContent = !isShowingHeaders(); // Fix focus lost when error fragment shown and sidebar is hidden
            mFocusOnContent = hasFocus(); // Maintain focus
            ft.runOnCommit(this::focusOnContentIfNeeded);
            ft.commitAllowingStateLoss(); // FIX: "Can not perform this action after onSaveInstanceState"
        }
    }

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }

        int oldIndex = indexOfSection(section.getId());
        if (oldIndex != -1 && (index == -1 || oldIndex == index)) {
            return;
        }

        if (oldIndex != -1) {
            mSectionRows.remove(oldIndex);
        }
        createHeader(index, section);
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        removeHeader(section);
    }

    @Override
    public void removeAllSections() {
        mSectionRows.clear();
        mHiddenRow = null;
        mSectionRowAdapter.clear();
        updateSettingsButton();
    }

    @Override
    public void updateSection(VideoGroup group) {
        restoreMainFragment();

        mSectionFragmentFactory.updateCurrentFragment(group);

        if (mGlassSelectedHeaderId == MediaGroup.TYPE_HOME && !BrowsePresenter.isHomeChipGroup(group)) {
            focusOnContentIfNeeded(); // MEETUBE: Home may now have its asynchronous first video row.
        }
        fixInvisibleSearchOrb();
    }

    @Override
    public void updateSection(SettingsGroup group) {
        restoreMainFragment();

        mSectionFragmentFactory.updateCurrentFragment(group);
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (index >= 0 && !mSectionRows.isEmpty()) {
            // MEETUBE: Explore launchers/boot choices open Home with the requested feed active.
            PageRow row = mSectionRows.get(Math.min(index, mSectionRows.size() - 1));
            long headerId = row.getHeaderItem().getId();
            if (BrowsePresenter.isExploreSection(headerId)) {
                int homeIndex = indexOfSection(MediaGroup.TYPE_HOME);
                if (homeIndex != -1) {
                    mBrowsePresenter.setHomeSection((int) headerId);
                    row = mSectionRows.get(homeIndex);
                    headerId = MediaGroup.TYPE_HOME;
                }
            }
            // If Home is disabled, an Explore deep link still opens directly on the current sidebar anchor.
            if (headerId == MediaGroup.TYPE_SETTINGS || BrowsePresenter.isExploreSection(headerId)) {
                if (mHiddenRow == null && mGlassSelectedHeaderId != headerId) {
                    mHiddenReturnSectionId = mGlassSelectedHeaderId;
                }
                mHiddenRow = row;
                // MEETUBE: a hidden section uses the current visible header as its sidebar anchor.
                mFocusOnContent = true;
                startHeadersTransitionSafe(false);
                if (mGlassSelectedHeaderId == headerId) {
                    focusOnContentIfNeeded();
                    mBrowsePresenter.onSectionFocused((int) headerId);
                }
                setSelectedPosition(Math.max(0, Math.min(getSelectedPosition(), mSectionRowAdapter.size() - 1)), false);
                return;
            }
            mHiddenRow = null;
            int position = indexOf(headerId);
            mFocusOnContent = focusOnContent; // focus after header transition

            // Fix refresh current section
            if (mGlassSelectedHeaderId == headerId) {
                // update section manually
                // headers transition event not fired on the same index
                if (headerId != MediaGroup.TYPE_HOME) {
                    focusOnContentIfNeeded();
                }
                mBrowsePresenter.onSectionFocused((int) headerId);
                if (headerId == MediaGroup.TYPE_HOME) {
                    focusOnContentIfNeeded();
                }
            }

            // Need select again if current header is removed previously (can't check for it right now)
            // Fallback to the last section if index above size
            setSelectedPosition(position, false);
        }
    }

    @Override
    public void focusOnContent() {
        startHeadersTransitionSafe(false);
        if (getMainFragment() != null && getMainFragment().getView() != null) {
            getMainFragment().getView().requestFocus();
        }
    }

    /**
     * Usually called after header transition or fragment transaction
     */
    private void focusOnContentIfNeeded() {
        if (mFocusOnContent) {
            if (mGlassSelectedHeaderId == MediaGroup.TYPE_HOME && (getMainFragment() == null ||
                    getMainFragment().getView() == null || (getMainFragment() instanceof MultipleRowsFragment &&
                    !((MultipleRowsFragment) getMainFragment()).isHomeVideoReady()))) {
                return; // MEETUBE: chips arrive before videos; keep the fresh-load focus request pending.
            }
            focusOnContent();
            mFocusOnContent = false;
        }
    }

    private boolean hasFocus() {
        if (getMainFragment() == null || getMainFragment().getView() == null) {
            return false;
        }

        return getMainFragment().getView().hasFocus();
    }

    @Override
    public void selectSectionItem(int index) {
        if (index >= 0) {
            mSectionFragmentFactory.setCurrentFragmentItemIndex(index);
        }
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item != null) {
            mSectionFragmentFactory.selectCurrentFragmentItem(item);
        }
    }

    /**
     * Fix: IllegalStateException: "Can not perform this action after onSaveInstanceState"
     */
    private void startHeadersTransitionSafe(boolean withHeaders) {
        // Fix: IllegalStateException: "Can not perform this action after onSaveInstanceState"
        if (!Utils.checkActivity(getActivity())) {
            return;
        }

        try {
            startHeadersTransition(withHeaders);
        } catch (IllegalStateException e) {
            // NOP
        }
    }

    /**
     * Restore after the error fragment
     */
    private void restoreMainFragment() {
        Fragment currentFragment = mSectionFragmentFactory.getCurrentFragment();

        if (currentFragment != null) {
            replaceMainFragment(currentFragment);
        }
    }

    private void createHeader(int index, BrowseSection header) {
        long selectedId = getFocusedHeaderId();
        PageRow pageRow = new PageRow(new SectionHeaderItem(header));
        mSectionRows.add(index == -1 ? mSectionRows.size() : Math.min(index, mSectionRows.size()), pageRow);
        updateHeaders(selectedId);
    }

    private void removeHeader(BrowseSection header) {
        int index = indexOfSection(header.getId());
        if (index != -1) {
            long selectedId = getFocusedHeaderId();
            mSectionRows.remove(index);
            updateHeaders(selectedId);
        }
    }

    private long getFocusedHeaderId() {
        HeadersSupportFragment headers = getHeadersSupportFragment();
        int position = isShowingHeaders() && headers != null ? headers.getSelectedPosition() : getSelectedPosition();
        return position >= 0 && position < mSectionRowAdapter.size() ?
                ((PageRow) mSectionRowAdapter.get(position)).getHeaderItem().getId() : -1;
    }

    private void updateHeaders(long selectedId) {
        List<PageRow> rows = new ArrayList<>();
        // MEETUBE: Explore feeds live in Home's chip row, never in the sidebar.
        for (PageRow row : mSectionRows) {
            long id = row.getHeaderItem().getId();
            if (id != MediaGroup.TYPE_SETTINGS && !BrowsePresenter.isExploreSection(id)) {
                rows.add(row);
            }
        }

        // MEETUBE: preserve PageRow identities and sidebar focus during section updates.
        mSectionRowAdapter.setItems(rows, new DiffCallback<PageRow>() {
            @Override
            public boolean areItemsTheSame(@NonNull PageRow oldRow, @NonNull PageRow newRow) {
                return oldRow == newRow;
            }

            @Override
            public boolean areContentsTheSame(@NonNull PageRow oldRow, @NonNull PageRow newRow) {
                return oldRow == newRow;
            }
        });
        int position = indexOf(selectedId);
        if (position == -1 && BrowsePresenter.isExploreSection(selectedId)) {
            position = indexOf(MediaGroup.TYPE_HOME);
        }
        if (position == -1 && selectedId != -1 && !rows.isEmpty()) {
            position = Math.max(0, Math.min(getSelectedPosition(), rows.size() - 1));
        }
        if (position != -1) {
            setSelectedPosition(position, false);
        }
        updateSettingsButton();
    }

    @Override
    public void clearSection(BrowseSection section) {
        mSectionFragmentFactory.clearCurrentFragment();
    }

    @Override
    public void onDestroyView() {
        mSectionFragmentFactory.cleanup();
        mGlassDecoration = null;
        mGlassHeaderPresenters.clear();

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBrowsePresenter.onViewDestroyed();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (!mIsFragmentCreated) {
            mBrowsePresenter.onViewPaused();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!mIsFragmentCreated) {
            mBrowsePresenter.onViewResumed();
        }

        mIsFragmentCreated = false;
        updateSettingsButton();
    }

    /**
     * Fix suddenly invisible search orb<br/>
     * Could happen on topmost category when the page partially scrolled<br/>
     * More info: {@link TitleHelper}
     */
    private void fixInvisibleSearchOrb() {
        if (isShowingTitle() && getTitleView() != null && getTitleView().getVisibility() != View.VISIBLE) {
            getTitleView().setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        // MEETUBE: Settings loads nothing; a video load that raced the switch to it must not leave the loader on top
        Runnable callback = () -> {
            if (show && !(getMainFragment() instanceof SettingsSection)) {
                mProgressBarManager.show();
            } else {
                mProgressBarManager.hide();
            }
        };

        // Essential. Need to run on the main thread.
        new Handler(Looper.getMainLooper()).post(callback);
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgressBarManager.isShowing();
    }

    @Override
    public boolean isEmpty() {
        return mSectionFragmentFactory == null || mSectionFragmentFactory.isEmpty();
    }

    @Override
    public void updateBadge() {
        if (getContext() == null) {
            return;
        }

        SplashPresenter splashPresenter = SplashPresenter.instance(getContext());

        if (splashPresenter == null) {
            return;
        }

        int appLogoRes = Helpers.getThemeAttr(getContext(), R.attr.appLogo);

        Drawable bridgeIcon = Utils.getDrawable(getContext(), splashPresenter.getBridgePackageName(), "app_icon");

        // Top right corner logo
        setBadgeDrawable(bridgeIcon != null ? bridgeIcon : appLogoRes > 0 ? ContextCompat.getDrawable(getContext(), appLogoRes) : null);
    }
}
