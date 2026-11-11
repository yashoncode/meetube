package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.downloads.DownloadRegistry;
import com.newtube.mobile.player.Media3PlayerCache;
import com.newtube.mobile.ui.common.GlassDrawable;

import java.io.File;
import java.util.Collections;

/**
 * MEETUBE(storage): Settings > Storage - how full the phone is, what MeeTube takes (app, cache,
 * downloads, everything else) and a button that empties the cache. Downloads are never touched
 * here; they are deleted one by one on the Downloads screen.
 */
final class StoragePage {

    private StoragePage() {
    }

    static SettingsPages.Page page(Context context) {
        String title = context.getString(R.string.mobile_settings_storage);
        return new SettingsPages.Page(title,
                Collections.singletonList(SettingsRow.custom(title, (parent, page) -> card(parent.getContext()))));
    }

    /** Sizes, in bytes: app, cache, downloads, data. */
    private static long[] measure(Context context) {
        long app = new File(context.getApplicationInfo().sourceDir).length();
        long cache = size(context.getCacheDir()) + size(context.getExternalCacheDir());
        long downloads = size(DownloadRegistry.dir(context));
        long data = size(new File(context.getApplicationInfo().dataDir)) - size(context.getCacheDir()) - downloads;
        return new long[] {app, cache, downloads, Math.max(0, data)};
    }

    /** Below this the cache is "empty": the media cache's index and Glide's journal never go to zero. */
    private static final long EMPTY_BYTES = 512 * 1024;

    /** What the button can free: the cache, minus running downloads' parts and the bug-report log. */
    private static long clearable(Context context) {
        return size(context.getCacheDir()) + size(context.getExternalCacheDir())
                - size(DownloadRegistry.partsDir(context)) - size(new File(context.getCacheDir(), "diagnostics"));
    }

    private static long size(File file) {
        if (file == null) {
            return 0;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return file.length();
        }
        long total = 0;
        for (File child : children) {
            total += size(child);
        }
        return total;
    }

    /** Everything in the cache folder but the parts of downloads still running and the bug-report log. */
    private static void clear(Context context) {
        Media3PlayerCache.clear(context);
        Glide.get(context).clearDiskCache();
        File media = Media3PlayerCache.dir(context);
        File parts = DownloadRegistry.partsDir(context);
        File[] children = context.getCacheDir().listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.equals(media) && !child.equals(parts) && !child.getName().equals("image_manager_disk_cache")
                        && !child.getName().equals("diagnostics")) {
                    delete(child);
                }
            }
        }
        delete(context.getExternalCacheDir());
    }

    private static void delete(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static View card(Context context) {
        LinearLayout root = Tiles.column(context);

        Tiles.heading(root, context.getString(R.string.mobile_settings_storage_device));
        LinearLayout bar = new LinearLayout(context);
        bar.setBackground(rounded(ContextCompat.getColor(context, R.color.mobile_color_track), dp(context, 5)));
        View used = new View(context);
        int primary = ContextCompat.getColor(context, R.color.mobile_color_primary);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {primary, ColorUtils.blendARGB(primary, 0xFFFFFFFF, 0.6f)});
        fill.setCornerRadius(dp(context, 5));
        used.setBackground(fill);
        View free = new View(context);
        bar.addView(used, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        bar.addView(free, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 10));
        barParams.topMargin = dp(context, 12);
        root.addView(bar, barParams);

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes();
        long available = fs.getAvailableBytes();
        ((LinearLayout.LayoutParams) used.getLayoutParams()).weight = total - available;
        ((LinearLayout.LayoutParams) free.getLayoutParams()).weight = available;
        TextView[] device = line(root, context.getString(R.string.mobile_settings_storage_used,
                size(context, total - available), size(context, total)), 8);
        device[0].setTypeface(Typeface.DEFAULT_BOLD);
        device[1].setText(context.getString(R.string.mobile_settings_storage_free, size(context, available)));

        View divider = new View(context);
        divider.setBackgroundColor(ContextCompat.getColor(context, R.color.mobile_color_divider));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = dividerParams.bottomMargin = dp(context, 16);
        root.addView(divider, dividerParams);

        TextView totalLine = Tiles.heading(root, context.getString(R.string.mobile_settings_storage_app));
        totalLine.setTextColor(primary);
        int[] labels = {R.string.mobile_settings_storage_app_size, R.string.mobile_settings_storage_cache,
                R.string.header_downloads, R.string.mobile_settings_storage_data};
        TextView[] values = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            values[i] = line(root, context.getString(labels[i]), i == 0 ? 12 : 8)[1];
        }

        TextView clear = new TextView(context);
        clear.setText(R.string.mobile_settings_storage_clear);
        clear.setTextColor(primary);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        clear.setGravity(Gravity.CENTER);
        clear.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_menu_delete, 0, 0, 0);
        clear.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(primary));
        clear.setCompoundDrawablePadding(dp(context, 8));
        clear.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        // MEETUBE(glass): a frosted pill, red-tinted; no source - the list it sits in is the glass source
        GlassDrawable.apply(clear, null, 0, ColorUtils.setAlphaComponent(primary, 0x33));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.topMargin = dp(context, 20);
        root.addView(clear, clearParams);
        TextView note = new TextView(context);
        note.setText(R.string.mobile_settings_storage_clear_note);
        note.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(context, 8), 0, 0);
        root.addView(note);

        Runnable refresh = () -> fill(context, root, totalLine, values, clear, note);
        clear.setOnClickListener(v -> {
            clear.setEnabled(false);
            Context app = context.getApplicationContext();
            new Thread(() -> {
                long before = measure(app)[1];
                clear(app);
                long freed = Math.max(0, before - measure(app)[1]);
                root.post(() -> {
                    Toast.makeText(context, context.getString(R.string.mobile_settings_storage_cleared, size(context, freed)),
                            Toast.LENGTH_SHORT).show();
                    refresh.run();
                });
            }, "StorageClear").start();
        });
        refresh.run();
        return root;
    }

    private static void fill(Context context, View root, TextView totalLine, TextView[] values, TextView clear, TextView note) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            long[] sizes = measure(app);
            boolean empty = clearable(app) < EMPTY_BYTES;
            root.post(() -> {
                // MEETUBE(storage): nothing left to clear - say so with a joke, and the button rests
                clear.setEnabled(!empty);
                clear.setAlpha(empty ? 0.5f : 1f);
                clear.setText(empty ? R.string.mobile_settings_storage_clear_empty : R.string.mobile_settings_storage_clear);
                note.setText(empty ? R.string.mobile_settings_storage_clear_empty_note : R.string.mobile_settings_storage_clear_note);
                long sum = 0;
                for (int i = 0; i < sizes.length; i++) {
                    values[i].setText(size(context, sizes[i]));
                    sum += sizes[i];
                }
                totalLine.setText(context.getString(R.string.mobile_settings_storage_total, size(context, sum)));
                totalLine.setVisibility(View.VISIBLE);
            });
        }, "StorageSize").start();
    }

    /** A label on the left and its value on the right; returns both. */
    private static TextView[] line(LinearLayout root, CharSequence label, int topDp) {
        Context context = root.getContext();
        LinearLayout row = new LinearLayout(context);
        TextView left = new TextView(context);
        left.setText(label);
        left.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        TextView right = new TextView(context);
        right.setText("…");
        right.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(right);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topDp);
        root.addView(row, params);
        return new TextView[] {left, right};
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static String size(Context context, long bytes) {
        return Formatter.formatShortFileSize(context, bytes);
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
