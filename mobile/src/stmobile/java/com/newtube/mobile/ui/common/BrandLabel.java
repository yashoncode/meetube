package com.newtube.mobile.ui.common;

import android.content.Context;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.function.Consumer;

/**
 * MEETUBE(app label): the name in the top-left brand pill, MeeTube's or MeeTune's, picked by the
 * user (Customization). Up to {@link #MAX} characters; saved empty, the app's own name comes back.
 */
public final class BrandLabel {
    public static final int MAX = 10;

    private BrandLabel() {
    }

    /** The saved label, or {@code fallback} when none is set. */
    public static String or(String saved, String fallback) {
        return saved == null || saved.trim().isEmpty() ? fallback : saved.trim();
    }

    /** Shows the rename dialog (returned so a settings page can track it); {@code onSet} gets the trimmed text, empty to restore {@code fallback}. */
    public static AlertDialog ask(@NonNull Context context, String current, String fallback, @NonNull Consumer<String> onSet) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(MAX)});
        input.setHint(fallback);
        input.setText(current);
        input.setSelection(input.getText().length());
        FrameLayout frame = new FrameLayout(context);
        int pad = Math.round(24 * context.getResources().getDisplayMetrics().density);
        frame.setPadding(pad, pad / 3, pad, 0);
        frame.addView(input);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_settings_brand_label)
                .setMessage(context.getString(R.string.mobile_settings_brand_label_body, MAX))
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (d, w) -> onSet.accept(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.mobile_settings_brand_label_reset, (d, w) -> onSet.accept(""))
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
        });
        dialog.show();
        return dialog;
    }

    /** The row's summary: the label now, and that a tap renames it. */
    public static String summary(Context context, String label) {
        return context.getString(R.string.mobile_settings_brand_label_summary, label, MAX);
    }
}
