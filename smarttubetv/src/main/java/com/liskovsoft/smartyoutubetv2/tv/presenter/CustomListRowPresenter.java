package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.view.View;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.RowPresenter;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class CustomListRowPresenter extends ListRowPresenter {
    public CustomListRowPresenter() {
        super(ViewUtil.FOCUS_ZOOM_FACTOR, ViewUtil.FOCUS_DIMMER_ENABLED);
        setSelectEffectEnabled(ViewUtil.ROW_SELECT_EFFECT_ENABLED);
    }

    @Override
    protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
        super.initializeRowViewHolder(holder);
        HorizontalGridView grid = ((ListRowPresenter.ViewHolder) holder).getGridView();
        // MEETUBE: match LARGE's fraction; include captions below the thumbnail-centered pivot.
        float extraScale = grid.getResources().getFraction(
                androidx.leanback.R.fraction.lb_focus_zoom_factor_large, 1, 1) - 1f;
        grid.addOnLayoutChangeListener((view, left, gridTop, right, gridBottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int top = grid.getPaddingTop();
            int bottom = grid.getPaddingBottom();
            for (int i = 0; i < grid.getChildCount(); i++) {
                View card = grid.getChildAt(i);
                top = Math.max(top, (int) Math.ceil(card.getPivotY() * extraScale));
                bottom = Math.max(bottom, (int) Math.ceil(
                        (card.getHeight() - card.getPivotY()) * extraScale));
            }
            // MEETUBE: add only missing space; a padding change requests another layout pass.
            if (top != grid.getPaddingTop() || bottom != grid.getPaddingBottom()) {
                grid.setPadding(grid.getPaddingLeft(), top, grid.getPaddingRight(), bottom);
            }
        });
    }
}
