package com.newtube.mobile.ui.common;

import android.view.View;

/** METUBE(glass): wires a {@link GlassView} to the {@link GlassSource} it frosts. */
public final class Glass {
    private Glass() {
    }

    public static void blur(View glass, View source) {
        if (glass instanceof GlassView && source instanceof GlassSource) {
            ((GlassView) glass).setSource((GlassSource) source);
        }
    }
}
