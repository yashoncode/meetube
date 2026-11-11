package com.newtube.mobile.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.FocusRing;
import com.newtube.mobile.ui.common.MobileSheets;

// MEETUBE(coffee): a warm thank-you sheet with local cups and a UPI fallback.
final class CoffeeSheet {
    private CoffeeSheet() { }

    static void show(Context context) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        Context themed = sheet.getContext();
        ViewGroup root = (ViewGroup) LayoutInflater.from(themed).inflate(R.layout.sheet_mobile_coffee, null);
        root.findViewById(R.id.coffee_india).setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(root);
            v.setSelected(true);
            GradientDrawable selected = new GradientDrawable();
            float dp = themed.getResources().getDisplayMetrics().density;
            selected.setCornerRadius(22 * dp);
            selected.setColor(ContextCompat.getColor(themed, R.color.meetube_glass_selected));
            selected.setStroke(Math.round(2 * dp), ContextCompat.getColor(themed, R.color.meetube_accent));
            v.setBackground(selected);
            root.findViewById(R.id.coffee_india_panel).setVisibility(View.VISIBLE);
        });
        root.findViewById(R.id.coffee_elsewhere).setOnClickListener(v -> {
            Utils.openLinkExt(context, context.getString(R.string.mobile_settings_coffee_url));
            sheet.dismiss();
        });
        root.findViewById(R.id.coffee_copy).setOnClickListener(v -> copy(themed, R.string.mobile_settings_coffee_copied));
        View qr = root.findViewById(R.id.coffee_qr);
        qr.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        12 * view.getResources().getDisplayMetrics().density);
            }
        });
        qr.setClipToOutline(true);
        sheet.setContentView(root);
        sheet.setOnShowListener(dialog -> MobileSheets.expandTo(sheet, 0.9f));
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        FocusRing.install(sheet.getWindow());
        FocusRing.focusFirstRow(sheet.getWindow());
        sheet.show();
    }

    private static void copy(Context context, int message) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mobile_settings_coffee_upi_label),
                    context.getString(R.string.mobile_settings_coffee_upi_id)));
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }
}
