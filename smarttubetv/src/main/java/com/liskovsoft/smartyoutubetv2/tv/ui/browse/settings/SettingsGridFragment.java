package com.liskovsoft.smartyoutubetv2.tv.ui.browse.settings;

import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.VerticalGridPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.SettingsCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseGlassStyle;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.SettingsSection;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.UriBackgroundManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.fragments.GridFragment;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.ArrayList;
import java.util.List;

public class SettingsGridFragment extends GridFragment implements SettingsSection {
    private static final String TAG = SettingsGridFragment.class.getSimpleName();
    private ArrayObjectAdapter mSettingsAdapter;
    private BrowsePresenter mMainPresenter;
    private UriBackgroundManager mBackgroundManager;
    private final List<SettingsGroup> mPendingUpdates = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMainPresenter = BrowsePresenter.instance(getContext());
        mBackgroundManager = ((LeanbackActivity) getActivity()).getBackgroundManager();

        setupAdapter();
        setupEventListeners();
        applyPendingUpdates();

        if (getMainFragmentAdapter().getFragmentHost() != null) {
            getMainFragmentAdapter().getFragmentHost().notifyDataReady(getMainFragmentAdapter());
        }
    }

    @Override
    protected void showOrHideTitle() {
        // NOP. Always show Browse fragment title
    }

    private void applyPendingUpdates() {
        for (SettingsGroup group : mPendingUpdates) {
            update(group);
        }

        mPendingUpdates.clear();
    }

    private void setupEventListeners() {
        setOnItemViewClickedListener(new ItemViewClickedListener());
    }

    private void setupAdapter() {
        boolean browseGlass = BrowseGlassStyle.isBrowse(this);
        // MEETUBE: settings retain the smaller zoom independently of video cards.
        VerticalGridPresenter presenter = new VerticalGridPresenter(ViewUtil.SETTINGS_FOCUS_ZOOM_FACTOR,
                !browseGlass && ViewUtil.FOCUS_DIMMER_ENABLED) {
            @Override
            protected void initializeGridViewHolder(ViewHolder vh) {
                super.initializeGridViewHolder(vh);
                // MEETUBE: the old 15dp gaps (video grids got wider ones for their bigger zoom), so settings fit 2 rows.
                vh.getGridView().setItemSpacing(getResources().getDimensionPixelSize(R.dimen.settings_item_spacing));
            }
        };
        presenter.enableChildRoundedCorners(getMainUIData().isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS));
        if (browseGlass) {
            // MEETUBE: the card drawable supplies the glass and its 16dp corners.
            presenter.enableChildRoundedCorners(false);
            presenter.setShadowEnabled(false);
        }
        presenter.setNumberOfColumns(GridFragmentHelper.getMaxColsNum(getContext(), R.dimen.settings_card_width));
        setGridPresenter(presenter);

        if (mSettingsAdapter == null) {
            SettingsCardPresenter gridPresenter = new SettingsCardPresenter(browseGlass);
            mSettingsAdapter = new ArrayObjectAdapter(gridPresenter);
            setAdapter(mSettingsAdapter);
        }
    }

    @Override
    public void clear() {
        if (mSettingsAdapter != null) {
            mSettingsAdapter.clear();
        }
    }

    @Override
    public boolean isEmpty() {
        if (mSettingsAdapter == null) {
            return mPendingUpdates.isEmpty();
        }

        return mSettingsAdapter.size() == 0;
    }

    @Override
    public void update(SettingsGroup group) {
        if (mSettingsAdapter == null) {
            mPendingUpdates.add(group);
            return;
        }

        // Always clear (continuation not supported)
        clear();

        if (group != null) {
            for (SettingsItem item : group.getItems()) {
                mSettingsAdapter.add(item);
            }
        }
    }

    private MainUIData getMainUIData() {
        return MainUIData.instance(getContext());
    }

    private GeneralData getGeneralData() {
        return GeneralData.instance(getContext());
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {

            if (item instanceof SettingsItem) {
                String password = getGeneralData().getSettingsPassword();

                if (password == null) {
                    ((SettingsItem) item).onClick.run();
                } else {
                    SimpleEditDialog.showPassword(
                            getContext(),
                            getContext().getString(R.string.enter_settings_password),
                            null,
                            newValue -> {
                                if (Utils.passwordMatch(password, newValue)) {
                                    ((SettingsItem) item).onClick.run();
                                    return true;
                                }
                                return false;
                            });
                }

                // Close PIP inside Settings section
                //if (PlaybackPresenter.instance(getContext()).isInPipMode()) {
                //    PlaybackPresenter.instance(getContext()).forceFinish();
                //}
            } else {
                Toast.makeText(getContext(), item.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
