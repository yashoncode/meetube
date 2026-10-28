package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEETUBE(description): YouTube's Description panel - the title, a Likes / Views / date row and the
 * description, selectable so any of it can be copied. Opened from the watch page's title; a plain
 * {@link BottomSheetDialog} on the frosted sheet glass, shown by the player (showPlayerSheet).
 */
final class DescriptionSheet {
    /** "1.8B views" -> "1.8B" over "Views"; other shapes (other languages) show whole. */
    private static final Pattern VIEWS = Pattern.compile("^(.*\\d\\S*)\\s+views?$", Pattern.CASE_INSENSITIVE);
    /** "Oct 25, 2009" -> "Oct 25" over "2009", as YouTube stacks it. */
    private static final Pattern DATE = Pattern.compile("^(.+?),?\\s+(\\d{4})$");

    private DescriptionSheet() {
    }

    /** MEETUBE(description): the sections under the description; any left null is not shown. */
    static final class Extras {
        /** Opens the transcript; null for a video without captions. */
        @Nullable
        Runnable transcript;
        @Nullable
        CharSequence channel;
        @Nullable
        CharSequence subscribers;
        @Nullable
        android.graphics.drawable.Drawable avatar;
        /** The watch page's Subscribe: the sheet's one taps it and follows its label and state. */
        @Nullable
        TextView subscribe;
        @Nullable
        Runnable openChannel;
    }

    /**
     * Empty parts are left out. With {@code video} (the portrait page) the sheet fills the screen
     * under it exactly, however short the text; without it (fullscreen) it is a plain sheet.
     */
    static BottomSheetDialog create(@NonNull Activity activity, CharSequence title, @Nullable CharSequence likes,
                                    @Nullable String views, @Nullable String date, CharSequence description,
                                    @Nullable View video, @NonNull Extras extras) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_description, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f); // the playing video above stays as it is, never dimmed
        }
        if (video != null) {
            fillUnder(dialog, content, video);
        }

        content.findViewById(R.id.description_sheet_close).setOnClickListener(v -> dialog.dismiss());
        ((TextView) content.findViewById(R.id.description_sheet_title)).setText(title);
        TextView text = content.findViewById(R.id.description_sheet_text);
        text.setText(description);
        text.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);

        LinearLayout stats = content.findViewById(R.id.description_sheet_stats);
        if (!TextUtils.isEmpty(likes)) {
            addStat(stats, likes, activity.getString(R.string.mobile_description_likes));
        }
        if (!TextUtils.isEmpty(views)) {
            Matcher m = VIEWS.matcher(views.trim());
            addStat(stats, m.matches() ? m.group(1) : views, m.matches() ? activity.getString(R.string.mobile_description_views) : null);
        }
        if (!TextUtils.isEmpty(date)) {
            Matcher m = DATE.matcher(date.trim());
            addStat(stats, m.matches() ? m.group(1) : date, m.matches() ? m.group(2) : null);
        }
        stats.setVisibility(stats.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        if (extras.transcript != null) {
            content.findViewById(R.id.description_sheet_transcript).setVisibility(View.VISIBLE);
            content.findViewById(R.id.description_sheet_transcript_show).setOnClickListener(v -> {
                dialog.dismiss();
                extras.transcript.run();
            });
        }
        bindChannel(dialog, content, extras);

        LinearLayout details = content.findViewById(R.id.description_sheet_details_rows);
        if (!TextUtils.isEmpty(date)) {
            addDetail(details, R.drawable.ic_desc_calendar, R.string.mobile_description_date, date);
        }
        if (!TextUtils.isEmpty(views)) {
            Matcher m = VIEWS.matcher(views.trim());
            addDetail(details, R.drawable.ic_desc_videos, R.string.mobile_description_views, m.matches() ? m.group(1) : views);
        }
        if (!TextUtils.isEmpty(likes)) {
            addDetail(details, R.drawable.ic_watch_thumb_up_outline, R.string.mobile_description_likes, likes);
        }
        content.findViewById(R.id.description_sheet_details)
                .setVisibility(details.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        return dialog;
    }

    /**
     * Sizes the sheet to reach exactly the video's bottom edge, measured on screen before its first
     * frame: the dialog's frame need not end where the page does (a navigation bar inset).
     */
    static void fillUnder(BottomSheetDialog dialog, View content, View video) {
        int minPx = Math.round(240 * content.getResources().getDisplayMetrics().density);
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            private final int[] mAt = new int[2];

            @Override
            public boolean onPreDraw() {
                View sheet = (View) content.getParent();
                View frame = sheet != null ? (View) sheet.getParent() : null;
                if (frame == null || frame.getHeight() == 0) {
                    return true;
                }
                frame.getLocationOnScreen(mAt);
                int frameBottom = mAt[1] + frame.getHeight();
                video.getLocationOnScreen(mAt);
                int height = frameBottom - (mAt[1] + video.getHeight());
                int contentHeight = height - sheet.getPaddingTop() - sheet.getPaddingBottom();
                if (height < minPx || contentHeight == content.getMinimumHeight()) {
                    return true;
                }
                content.setMinimumHeight(contentHeight);
                dialog.getBehavior().setMaxHeight(height);
                return false; // laid out again at that height before anything is drawn
            }
        });
    }

    private static void bindChannel(BottomSheetDialog dialog, View content, Extras extras) {
        if (TextUtils.isEmpty(extras.channel)) {
            return;
        }
        content.findViewById(R.id.description_sheet_channel).setVisibility(View.VISIBLE);
        ((TextView) content.findViewById(R.id.description_sheet_channel_name)).setText(extras.channel);
        TextView subs = content.findViewById(R.id.description_sheet_channel_subs);
        subs.setText(extras.subscribers);
        subs.setVisibility(TextUtils.isEmpty(extras.subscribers) ? View.GONE : View.VISIBLE);
        if (extras.avatar != null && extras.avatar.getConstantState() != null) {
            ((android.widget.ImageView) content.findViewById(R.id.description_sheet_channel_avatar))
                    .setImageDrawable(extras.avatar.getConstantState().newDrawable().mutate());
        }
        View videos = content.findViewById(R.id.description_sheet_channel_videos);
        if (extras.openChannel != null) {
            View.OnClickListener open = v -> {
                dialog.dismiss();
                extras.openChannel.run();
            };
            videos.setOnClickListener(open);
            content.findViewById(R.id.description_sheet_channel_avatar).setOnClickListener(open);
        } else {
            videos.setVisibility(View.GONE);
        }
        TextView subscribe = content.findViewById(R.id.description_sheet_subscribe);
        TextView source = extras.subscribe;
        if (source == null || source.getVisibility() != View.VISIBLE) {
            subscribe.setVisibility(View.GONE);
            return;
        }
        Runnable mirror = () -> {
            subscribe.setText(source.getText());
            subscribe.setSelected(source.isSelected());
            subscribe.setTextColor(source.getCurrentTextColor());
        };
        mirror.run();
        subscribe.setOnClickListener(v -> source.performClick());
        // The watch page's pill changes when YouTube answers (or after a confirm): follow it.
        View.OnLayoutChangeListener follow = (v, l, t, r, b, ol, ot, or, ob) -> mirror.run();
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                subscribe.post(mirror);
            }
        };
        source.addTextChangedListener(watcher);
        source.addOnLayoutChangeListener(follow);
        // Not setOnDismissListener: showPlayerSheet owns the dialog's.
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                source.removeTextChangedListener(watcher);
                source.removeOnLayoutChangeListener(follow);
            }
        });
    }

    /** A Video details row: icon, what it is, then the value at the end. */
    private static void addDetail(LinearLayout rows, int icon, int label, CharSequence value) {
        android.content.Context context = rows.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(12 * density);
        row.setPadding(0, pad, 0, pad);
        android.widget.ImageView image = new android.widget.ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
        int size = Math.round(20 * density);
        row.addView(image, new LinearLayout.LayoutParams(size, size));
        TextView name = stat(rows, context.getString(label), 14, R.color.mobile_color_on_surface_secondary, false);
        name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); // by its icon, not centered like a stat
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(Math.round(14 * density));
        row.addView(name, nameLp);
        TextView text = stat(rows, value, 14, R.color.mobile_color_on_surface, true);
        text.setFontFeatureSettings("tnum");
        text.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(text);
        rows.addView(row);
    }

    /** One column: the number big and bold, what it counts small beneath. */
    private static void addStat(LinearLayout row, CharSequence value, @Nullable CharSequence label) {
        LinearLayout column = new LinearLayout(row.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(stat(row, value, 18, R.color.mobile_color_on_surface, true));
        if (!TextUtils.isEmpty(label)) {
            column.addView(stat(row, label, 13, R.color.mobile_color_on_surface_secondary, false));
        }
        row.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private static TextView stat(View row, CharSequence text, int sp, int color, boolean bold) {
        TextView view = new TextView(row.getContext());
        view.setText(text);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ContextCompat.getColor(row.getContext(), color));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }
}
