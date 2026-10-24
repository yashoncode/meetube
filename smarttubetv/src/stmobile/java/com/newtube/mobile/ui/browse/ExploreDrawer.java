package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.SheetGlass;

import java.util.List;

/**
 * METUBE(explore): YouTube's side menu, behind the compass at the head of Home's chips: the topic
 * feeds this app can open (a section turned off in Settings gets no row), on the frosted page
 * with the RGB glow (RgbGlow).
 */
final class ExploreDrawer {
    interface Listener {
        void onPicked(int sectionId);
    }

    private static final int[][] ROWS = {
            {MediaGroup.TYPE_TRENDING, R.drawable.ic_explore_trending},
            {MediaGroup.TYPE_MUSIC, R.drawable.ic_explore_music},
            {MediaGroup.TYPE_LIVE, R.drawable.ic_explore_live},
            {MediaGroup.TYPE_GAMING, R.drawable.ic_explore_gaming},
            {MediaGroup.TYPE_NEWS, R.drawable.ic_explore_news},
            {MediaGroup.TYPE_SPORTS, R.drawable.ic_explore_sports}};
    private static final long SLIDE_MS = 220;

    private ExploreDrawer() {
    }

    static void show(Activity activity, List<BrowseSection> sections, Listener listener) {
        // A Material window: the pre-Material Translucent theme has the system paint black bars.
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        dialog.setContentView(R.layout.mobile_explore_drawer);
        View scrim = dialog.findViewById(R.id.mobile_explore_scrim);
        View panel = dialog.findViewById(R.id.mobile_explore_panel);
        LinearLayout items = dialog.findViewById(R.id.mobile_explore_items);
        for (int[] row : ROWS) {
            BrowseSection section = find(sections, row[0]);
            if (section == null) {
                continue;
            }
            TextView item = (TextView) LayoutInflater.from(activity).inflate(R.layout.mobile_explore_item, items, false);
            item.setText(section.getTitle());
            item.setCompoundDrawablesRelativeWithIntrinsicBounds(row[1], 0, 0, 0);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onPicked(row[0]);
            });
            items.addView(item);
        }
        scrim.setOnClickListener(v -> {
            scrim.animate().alpha(0f).setDuration(SLIDE_MS).start();
            panel.animate().translationX(-panel.getWidth()).setDuration(SLIDE_MS).withEndAction(dialog::dismiss).start();
        });
        // The window runs under the status bar (edge to edge): the rows start below it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            dialog.getWindow().setAttributes(lp);
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialog.getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(items, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        dialog.show();
        panel.post(() -> {
            SheetGlass.frost(panel, activity.getWindow(), 0f);
            panel.setTranslationX(-panel.getWidth());
            panel.setVisibility(View.VISIBLE);
            panel.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
            scrim.animate().alpha(1f).setDuration(SLIDE_MS).start();
        });
    }

    private static BrowseSection find(List<BrowseSection> sections, int id) {
        for (BrowseSection section : sections) {
            if (section.getId() == id && section.isEnabled()) {
                return section;
            }
        }
        return null;
    }
}
