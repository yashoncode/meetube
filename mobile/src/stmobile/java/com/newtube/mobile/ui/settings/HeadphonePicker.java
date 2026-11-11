package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.HeadphoneEq;
import com.newtube.mobile.ui.common.MobileSheets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEETUBE(autoeq): pick a headphone model from AutoEq's index (results/INDEX.md, ~9k profiles,
 * ~850 KB, kept 2 weeks in files/) and fetch only its ParametricEQ.txt. Search matches every word.
 */
final class HeadphonePicker {
    private static final String BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/";
    private static final long INDEX_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000;
    /** "- [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990" */
    private static final Pattern LINE = Pattern.compile("^- \\[(.+)\\]\\(\\./(.+)\\) by (.+)$", Pattern.MULTILINE);

    private static final class Entry {
        final String name;
        final String path;
        final String source;
        final String search;

        Entry(String name, String path, String source) {
            this.name = name;
            this.path = path;
            this.source = source;
            search = (name + " " + source).toLowerCase(Locale.ROOT);
        }
    }

    private HeadphonePicker() {
    }

    /** {@code changed} runs after a pick or "Off", on the main thread. */
    static void show(Context context, Runnable changed) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        Context themed = sheet.getContext();
        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(themed, 20);
        root.setPadding(pad, dp(themed, 16), pad, 0);

        TextView title = new TextView(themed);
        title.setText(R.string.mobile_settings_autoeq);
        title.setTextColor(ContextCompat.getColor(themed, R.color.mobile_color_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(title);
        TextView credit = new TextView(themed);
        credit.setText(R.string.mobile_settings_autoeq_credit);
        credit.setTextColor(ContextCompat.getColor(themed, R.color.mobile_color_on_surface_secondary));
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(credit);

        EditText query = new EditText(themed);
        query.setHint(R.string.mobile_settings_autoeq_search);
        query.setSingleLine(true);
        query.setBackgroundResource(R.drawable.mobile_settings_search_field);
        query.setTextColor(ContextCompat.getColor(themed, R.color.mobile_color_on_surface));
        query.setHintTextColor(ContextCompat.getColor(themed, R.color.mobile_color_on_surface_secondary));
        query.setPadding(dp(themed, 18), dp(themed, 12), dp(themed, 18), dp(themed, 12));
        LinearLayout.LayoutParams queryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        queryParams.topMargin = queryParams.bottomMargin = dp(themed, 12);
        root.addView(query, queryParams);

        TextView status = new TextView(themed);
        status.setText(R.string.mobile_settings_autoeq_loading);
        status.setTextColor(ContextCompat.getColor(themed, R.color.mobile_color_on_surface_secondary));
        root.addView(status);

        List<Entry> all = new ArrayList<>();
        List<Entry> shown = new ArrayList<>();
        Entry off = new Entry(themed.getString(R.string.mobile_settings_autoeq_off), "", "");
        ArrayAdapter<Entry> adapter = new ArrayAdapter<Entry>(themed, android.R.layout.simple_list_item_2,
                android.R.id.text1, shown) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Entry entry = getItem(position);
                TextView name = view.findViewById(android.R.id.text1);
                TextView source = view.findViewById(android.R.id.text2);
                name.setText(entry.name);
                name.setTextColor(ContextCompat.getColor(themed, entry == off ? R.color.mobile_color_primary
                        : R.color.mobile_color_on_surface));
                source.setText(entry.source.isEmpty() ? "" : themed.getString(R.string.mobile_settings_autoeq_by, entry.source));
                source.setVisibility(entry.source.isEmpty() ? View.GONE : View.VISIBLE);
                source.setTextColor(ContextCompat.getColor(themed, R.color.mobile_color_on_surface_secondary));
                return view;
            }
        };
        ListView list = new ListView(themed);
        list.setNestedScrollingEnabled(true);
        list.setDivider(null);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        boolean chosen = !HeadphoneEq.name(themed).isEmpty();
        Runnable filter = () -> {
            String[] words = query.getText().toString().toLowerCase(Locale.ROOT).trim().split("\\s+");
            shown.clear();
            if (chosen && words[0].isEmpty()) {
                shown.add(off);
            }
            for (Entry entry : all) {
                boolean match = true;
                for (String word : words) {
                    if (!entry.search.contains(word)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    shown.add(entry);
                }
            }
            adapter.notifyDataSetChanged();
        };
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                filter.run();
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            Entry entry = shown.get(position);
            if (entry == off) {
                HeadphoneEq.clear(themed);
                sheet.dismiss();
                changed.run();
                return;
            }
            status.setText(themed.getString(R.string.mobile_settings_autoeq_fetching, entry.name));
            status.setVisibility(View.VISIBLE);
            list.setEnabled(false);
            Context app = themed.getApplicationContext();
            new Thread(() -> {
                boolean ok;
                try {
                    String file = entry.path.substring(entry.path.lastIndexOf('/') + 1);
                    ok = HeadphoneEq.set(app, entry.name, fetch(BASE + entry.path + "/" + file + "%20ParametricEQ.txt"));
                } catch (IOException e) {
                    ok = false;
                }
                boolean applied = ok;
                root.post(() -> {
                    list.setEnabled(true);
                    if (applied) {
                        Toast.makeText(app, app.getString(R.string.mobile_settings_autoeq_applied, entry.name),
                                Toast.LENGTH_SHORT).show();
                        sheet.dismiss();
                        changed.run();
                    } else {
                        status.setText(R.string.mobile_settings_autoeq_failed);
                    }
                });
            }, "AutoEqProfile").start();
        });

        Context app = themed.getApplicationContext();
        new Thread(() -> {
            List<Entry> entries = loadIndex(app);
            root.post(() -> {
                all.addAll(entries);
                status.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                status.setText(R.string.mobile_settings_autoeq_failed);
                filter.run();
            });
        }, "AutoEqIndex").start();

        sheet.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.setOnShowListener(dialog -> MobileSheets.expandTo(sheet, 0.85f));
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.show();
    }

    /** The cached index, refreshed when 2 weeks old; a failed refresh keeps the old copy. */
    private static List<Entry> loadIndex(Context context) {
        File file = new File(context.getFilesDir(), "autoeq_index.md");
        String text = null;
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > INDEX_MAX_AGE_MS) {
            try {
                text = fetch(BASE + "INDEX.md");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // offline: the old copy, if any
            }
        }
        if (text == null && file.exists()) {
            try (InputStream in = new java.io.FileInputStream(file)) {
                text = read(in);
            } catch (IOException ignored) {
                // unreadable: nothing to show
            }
        }
        List<Entry> entries = new ArrayList<>();
        if (text != null) {
            Matcher line = LINE.matcher(text);
            while (line.find()) {
                entries.add(new Entry(line.group(1), line.group(2), line.group(3)));
            }
        }
        return entries;
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                return read(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
