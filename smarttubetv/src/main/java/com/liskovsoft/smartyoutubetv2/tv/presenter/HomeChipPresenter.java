package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowPresenter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** MEETUBE: compact feed selectors with their own row, without card zoom, shadows or a heading. */
public final class HomeChipPresenter extends Presenter {
    public static final class ChipRow extends ListRow {
        public ChipRow(ObjectAdapter adapter) {
            super(adapter);
        }
    }

    public static ListRowPresenter rowPresenter() {
        ListRowPresenter presenter = new ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false) {
            @Override
            protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
                super.initializeRowViewHolder(holder);
                HorizontalGridView grid = ((ListRowPresenter.ViewHolder) holder).getGridView();
                float density = grid.getResources().getDisplayMetrics().density;
                grid.setHorizontalSpacing(Math.round(8 * density));
                grid.setRowHeight(Math.round(38 * density));
                applyChipPadding(holder);
            }

            // MEETUBE: a headerless chip row must not reserve Leanback's expanded header gap.
            private void applyChipPadding(RowPresenter.ViewHolder holder) {
                HorizontalGridView grid = ((ListRowPresenter.ViewHolder) holder).getGridView();
                int bottom = grid.getResources().getDimensionPixelSize(
                        androidx.leanback.R.dimen.lb_browse_expanded_row_no_hovercard_bottom_padding);
                grid.setPadding(grid.getPaddingLeft(), 0, grid.getPaddingRight(), bottom);
            }

            @Override
            protected void onRowViewSelected(RowPresenter.ViewHolder holder, boolean selected) {
                super.onRowViewSelected(holder, selected);
                applyChipPadding(holder);
            }

            @Override
            protected void onRowViewExpanded(RowPresenter.ViewHolder holder, boolean expanded) {
                super.onRowViewExpanded(holder, expanded);
                applyChipPadding(holder);
            }

            @Override
            protected void onBindRowViewHolder(RowPresenter.ViewHolder holder, Object item) {
                super.onBindRowViewHolder(holder, item);
                ObjectAdapter adapter = ((ListRow) item).getAdapter();
                BrowsePresenter browse = BrowsePresenter.instance(holder.view.getContext());
                for (int i = 0; i < adapter.size(); i++) {
                    if (browse.isHomeChipActive((Video) adapter.get(i))) {
                        ((ListRowPresenter.ViewHolder) holder).getGridView().setSelectedPosition(i);
                        break;
                    }
                }
            }
        };
        presenter.setHeaderPresenter(null);
        presenter.setShadowEnabled(false);
        presenter.enableChildRoundedCorners(false);
        presenter.setSelectEffectEnabled(false);
        presenter.setSyncActivatePolicy(RowPresenter.SYNC_ACTIVATED_CUSTOM); // MEETUBE: each chip owns its active state.
        return presenter;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        TextView pill = new TextView(parent.getContext());
        float density = parent.getResources().getDisplayMetrics().density;
        pill.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(38 * density)));
        pill.setPadding(Math.round(18 * density), 0, Math.round(18 * density), 0);
        pill.setGravity(Gravity.CENTER);
        pill.setTextSize(14);
        pill.setSingleLine(true);
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        pill.setBackgroundResource(R.drawable.home_glass_chip);
        pill.setTextColor(0xFFFFFFFF);
        pill.setOnFocusChangeListener((v, focused) ->
                pill.setTextColor(focused ? 0xFF141414 : 0xFFFFFFFF));
        pill.setOnLongClickListener(v -> true); // consume a long press without selecting or opening a menu
        return new ViewHolder(pill);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, Object item) {
        Video chip = (Video) item;
        TextView pill = (TextView) holder.view;
        pill.setText(chip.getTitle());
        pill.setActivated(BrowsePresenter.instance(pill.getContext()).isHomeChipActive(chip));
        pill.setTextColor(pill.hasFocus() ? 0xFF141414 : 0xFFFFFFFF);
    }

    @Override
    public void onUnbindViewHolder(ViewHolder holder) {
        ((TextView) holder.view).setText(null);
        holder.view.setActivated(false);
    }
}
