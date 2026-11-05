package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import androidx.recyclerview.widget.DefaultItemAnimator;

/** MEETUBE: sidebar updates use the default animations; Explore no longer expands here. */
final class BrowseHeaderAnimator extends DefaultItemAnimator {
    BrowseHeaderAnimator() {
        setSupportsChangeAnimations(false); // as leanback's grids
    }
}
