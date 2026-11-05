package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.FocusHighlightHelper;
import androidx.leanback.widget.VerticalGridView;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.headers.ExtendedHeadersSupportFragment;

public class BrowseHeadersSupportFragment extends ExtendedHeadersSupportFragment {
    public BrowseHeadersSupportFragment() {
        // MEETUBE: no zoom on the focused header, the glass pill marks it
        FocusHighlightHelper.setupHeaderItemFocusHighlight(getBridgeAdapter(), false);
    }

    @Override
    public void setAlignment(int windowAlignOffsetTop) {
        super.setAlignment(windowAlignOffsetTop);
        VerticalGridView grid = getVerticalGridView();
        if (grid != null) {
            // MEETUBE: leanback pins the selected header at a fixed height (NO_EDGE), so every move slid the whole
            // list and the headers above the selection left the panel. Clamped at both ends, the list rests at its
            // MEETUBE: top padding keeps short sidebars still; longer lists scroll past the middle.
            grid.setItemAlignmentOffsetPercent(50);
            grid.setWindowAlignmentOffset(0);
            grid.setWindowAlignmentOffsetPercent(50);
            grid.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_BOTH_EDGE);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        VerticalGridView grid = getVerticalGridView();
        if (grid != null) {
            // MEETUBE: separate the full-height pills without changing scroll or focus alignment.
            grid.setVerticalSpacing(getResources().getDimensionPixelSize(R.dimen.browse_sidebar_item_spacing));
        }
        // Child fragments inflate after BrowseFragment.onCreateView, so decorate once they exist.
        if (getParentFragment() instanceof BrowseFragment) {
            ((BrowseFragment) getParentFragment()).onHeadersViewCreated(view);
        }
    }
}
