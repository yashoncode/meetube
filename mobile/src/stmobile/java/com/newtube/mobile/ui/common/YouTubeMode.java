package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(youtube mode): Settings > Customization > YouTube mode. Video cards lose their rounded
 * corners and the bottom bar docks to the screen's bottom edge, full width and flat, its frosted
 * glass running under the gesture area like YouTube's bar. The shapes are theme attributes
 * (ThemeOverlay.MeeTube.YouTubeMode); the screens with the bar let their content reach the bottom
 * edge ({@link MobileActivity#shouldInsetContentForNavigationBar}) and call {@link #dockBar}.
 */
public final class YouTubeMode {
    private static final String PREFS = "meetube_youtube_mode";

    private YouTubeMode() {
    }

    public static boolean isOn(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("on", false);
    }

    public static void setOn(Context context, boolean on) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("on", on).apply();
    }

    static void applyTo(Context context, Resources.Theme theme) {
        if (isOn(context)) {
            theme.applyStyle(R.style.ThemeOverlay_MeeTube_YouTubeMode, true);
        }
    }

    /**
     * The docked bar pads its tabs above the gesture area; {@code clearance} (may be null) gets the
     * bar's full height, for the lists and cards that must end above it.
     */
    public static void dockBar(View bar, @androidx.annotation.Nullable java.util.function.IntConsumer clearance) {
        int padBottom = bar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bar, (v, insets) -> {
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), padBottom + nav.bottom);
            return insets;
        });
        if (clearance != null) {
            bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t != ob - ot) {
                    clearance.accept(b - t);
                }
            });
        }
        ViewCompat.requestApplyInsets(bar);
    }

    /** Sets a list's bottom padding (or a view's bottom margin) to the docked bar's height. */
    public static void padBottom(@androidx.annotation.Nullable View view, int px) {
        if (view == null) {
            return;
        }
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), px);
    }

    public static void marginBottom(@androidx.annotation.Nullable View view, int px) {
        if (view != null && view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin = px;
            view.requestLayout();
        }
    }
}
