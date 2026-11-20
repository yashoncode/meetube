package com.newtube.mobile.ui.browse;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.FocusTimer;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.UsageStats;
import com.newtube.mobile.ui.settings.MobileSettingsActivity;
import com.newtube.mobile.ui.settings.SettingsPages;

import java.util.Calendar;

/**
 * MEETUBE(greeting): a held brand pill frosts the whole screen (a full-screen glass over the page)
 * with a greeting for the time of day and three glass shortcuts: Settings, Customization and Check
 * for updates, under a card of time spent in MeeTube (UsageStats). A tap anywhere else, or Back, closes it.
 */
final class GreetingOverlay {
    private static final int[] PERIODS = {
            R.string.mobile_greet_morning,
            R.string.mobile_greet_afternoon,
            R.string.mobile_greet_evening,
            R.string.mobile_greet_night,
    };
    /** The last quote shown, so the next open never repeats it. */
    private static int sLastQuote = -1;

    private final ComponentActivity mActivity;
    private final View mOverlay;
    private final View mContent;
    private final TextView mTitle;
    private final TextView mLine;
    private final View mStats;
    private final View mFocus;
    private final Runnable mFocusTick = this::bindFocus;
    private final OnBackPressedCallback mBack = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            hide();
        }
    };

    GreetingOverlay(ComponentActivity activity) {
        mActivity = activity;
        mOverlay = activity.findViewById(R.id.mobile_greet_overlay);
        mContent = activity.findViewById(R.id.mobile_greet_content);
        mTitle = activity.findViewById(R.id.mobile_greet_title);
        mLine = activity.findViewById(R.id.mobile_greet_line);
        mOverlay.setOnClickListener(v -> hide());

        ViewGroup content = (ViewGroup) mContent;
        mStats = LayoutInflater.from(activity).inflate(R.layout.item_mobile_greet_stats, content, false);
        content.addView(mStats);
        frost(mStats);
        mFocus = LayoutInflater.from(activity).inflate(R.layout.item_mobile_greet_focus, content, false);
        content.addView(mFocus);
        frost(mFocus);
        mFocus.findViewById(R.id.focus_label).setOnClickListener(v -> {
            if (FocusTimer.remainingMs() < 0) { // running: the countdown is not a picker
                com.newtube.mobile.ui.common.Haptics.tick(v);
                FocusTimer.nextMinutes();
                bindFocus();
            }
        });
        mFocus.findViewById(R.id.focus_label).setOnLongClickListener(v -> {
            if (FocusTimer.remainingMs() >= 0) {
                return false;
            }
            askMinutes();
            return true;
        });
        mFocus.findViewById(R.id.focus_toggle).setOnClickListener(v -> {
            Motion.nudge(v);
            if (FocusTimer.remainingMs() < 0) {
                FocusTimer.start();
                hide();
                com.newtube.mobile.ui.common.MobileSnackbar.show(mActivity,
                        mActivity.getString(R.string.mobile_focus_started, FocusTimer.minutes()));
            } else {
                FocusTimer.stop();
                bindFocus();
            }
        });
        addOption(content, R.drawable.ic_mobile_settings, R.string.header_settings,
                () -> open(null));
        addOption(content, R.drawable.ic_settings_customize, R.string.mobile_settings_customize,
                () -> open(SettingsPages.CUSTOMIZE));
        addOption(content, R.drawable.ic_mobile_update, R.string.check_for_updates, () -> {
            if (AppUpdatePresenter.isInAppUpdatesDisabled()) {
                open(SettingsPages.ABOUT); // store build: no updater, the version lives in About
            } else {
                AppUpdatePresenter.instance(mActivity).start(true);
            }
        });
    }

    private void addOption(ViewGroup parent, @DrawableRes int icon, @StringRes int label, Runnable action) {
        View option = LayoutInflater.from(mActivity).inflate(R.layout.item_mobile_greet_option, parent, false);
        TextView text = option.findViewById(R.id.mobile_greet_option_label);
        text.setText(label);
        text.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        frost(option);
        option.setOnClickListener(v -> {
            hide();
            action.run();
        });
        parent.addView(option);
    }

    /** MEETUBE(stats): its own heavier frost of the page, so a card or button reads as frosted glass on the greeting's. */
    private void frost(View glass) {
        ((com.newtube.mobile.ui.common.GlassView) glass).setBlur(28f);
        com.newtube.mobile.ui.common.Glass.blur(glass, mActivity.findViewById(R.id.mobile_blur_target));
    }

    private void open(String pageId) {
        mActivity.startActivity(MobileSettingsActivity.intent(mActivity, pageId));
    }

    void show() {
        mTitle.setText(PERIODS[period(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))]);
        String[] quotes = mActivity.getResources().getStringArray(R.array.mobile_greet_quotes);
        sLastQuote = nextQuote(quotes.length, sLastQuote, new java.util.Random());
        mLine.setText(quotes[sLastQuote]);
        bindStats();
        bindFocus();

        mBack.remove(); // re-added last, so it is the callback Back reaches first
        mActivity.getOnBackPressedDispatcher().addCallback(mBack);

        mOverlay.animate().cancel();
        mContent.animate().cancel();
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.setAlpha(0f);
        mOverlay.animate().alpha(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        mContent.setScaleX(0.92f);
        mContent.setScaleY(0.92f);
        mContent.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
    }

    private void hide() {
        mBack.remove();
        mFocus.removeCallbacks(mFocusTick);
        if (mOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        mOverlay.animate().cancel();
        mOverlay.animate().alpha(0f).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> mOverlay.setVisibility(View.GONE)).start();
    }

    /** MEETUBE(focus): a held Timer pill asks for any length in minutes. */
    private void askMinutes() {
        float dp = mActivity.getResources().getDisplayMetrics().density;
        android.widget.EditText input = new android.widget.EditText(mActivity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(3)});
        input.setHint(R.string.mobile_focus_custom_hint);
        input.setText(String.valueOf(FocusTimer.minutes()));
        input.setSelectAllOnFocus(true);
        android.widget.FrameLayout box = new android.widget.FrameLayout(mActivity);
        box.setPadding(Math.round(24 * dp), Math.round(8 * dp), Math.round(24 * dp), 0);
        box.addView(input);
        androidx.appcompat.app.AlertDialog dialog = FocusTimer.frosted(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                mActivity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_focus_custom_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        FocusTimer.setMinutes(Integer.parseInt(input.getText().toString().trim()));
                    } catch (NumberFormatException ignored) {
                        return; // empty: keep the old length
                    }
                    bindFocus();
                })
                .setNegativeButton(android.R.string.cancel, null));
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        dialog.show();
    }

    /** MEETUBE(focus): the picked minutes, or the countdown (ticking each second while shown). */
    private void bindFocus() {
        mFocus.removeCallbacks(mFocusTick);
        long left = FocusTimer.remainingMs();
        TextView label = mFocus.findViewById(R.id.focus_label);
        android.widget.ImageButton toggle = mFocus.findViewById(R.id.focus_toggle);
        boolean running = left >= 0;
        label.setText(running
                ? mActivity.getString(R.string.mobile_focus_running,
                        String.format(java.util.Locale.getDefault(), "%d:%02d", left / 60_000, left / 1000 % 60))
                : mActivity.getString(R.string.mobile_focus_idle, FocusTimer.minutes()));
        toggle.setImageResource(running ? R.drawable.ic_mobile_focus_stop : R.drawable.ic_player_play);
        toggle.setContentDescription(mActivity.getString(running ? R.string.mobile_focus_stop : R.string.mobile_focus_start));
        if (running) {
            mFocus.postDelayed(mFocusTick, left == 0 ? 500 : left % 1000 + 1);
        }
    }

    /**
     * MEETUBE(stats): today's time in MeeTube, big; today's watch time and videos and the 7-day daily
     * average under it; the 7 days as bars under their total. A tapped bar lights up and its day is spelled out.
     */
    private void bindStats() {
        android.content.Context context = mActivity;
        float dp = context.getResources().getDisplayMetrics().density;
        long week = 0;
        long max = 1;
        long[] days = new long[7];
        for (int i = 0; i < 7; i++) {
            days[i] = UsageStats.appMs(i);
            week += days[i];
            max = Math.max(max, days[i]);
        }

        ((TextView) mStats.findViewById(R.id.stats_hero)).setText(UsageStats.duration(days[0]));
        ViewGroup numbers = mStats.findViewById(R.id.stats_numbers);
        numbers.removeAllViews();
        number(numbers, UsageStats.duration(UsageStats.watchMs(0)), R.string.mobile_stats_watched);
        number(numbers, String.valueOf(UsageStats.videos(0)), R.string.mobile_stats_videos);
        number(numbers, UsageStats.duration(week / 7), R.string.mobile_stats_avg);
        ((TextView) mStats.findViewById(R.id.stats_week_title)).setText(
                context.getString(R.string.mobile_stats_week_title, UsageStats.duration(week)));

        int accent = com.google.android.material.color.MaterialColors.getColor(mStats,
                androidx.appcompat.R.attr.colorPrimary);
        ViewGroup bars = mStats.findViewById(R.id.stats_bars);
        ViewGroup labels = mStats.findViewById(R.id.stats_days);
        bars.removeAllViews();
        labels.removeAllViews();
        for (int i = 6; i >= 0; i--) {
            // The bar fills a full-height, clickable column, so a short bar is as easy to tap as a tall one.
            android.widget.FrameLayout column = new android.widget.FrameLayout(context);
            View bar = new View(context);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(6 * dp);
            shape.setColor(accent);
            bar.setBackground(shape);
            column.addView(bar, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(Math.round(4 * dp), Math.round(72 * dp * days[i] / max)), android.view.Gravity.BOTTOM));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMarginStart(Math.round(4 * dp));
            lp.setMarginEnd(Math.round(4 * dp));
            int daysAgo = i;
            column.setOnClickListener(v -> {
                com.newtube.mobile.ui.common.Haptics.tick(v);
                selectDay(daysAgo);
            });
            bars.addView(column, lp);

            TextView label = new TextView(context);
            label.setText(UsageStats.weekday(i));
            label.setGravity(android.view.Gravity.CENTER);
            label.setTextSize(11);
            labels.addView(label, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        selectDay(-1);
    }

    /** Lights {@code daysAgo}'s bar and label and spells its day out under the bars; -1: no pick yet, a hint. */
    private void selectDay(int daysAgo) {
        android.content.Context context = mActivity;
        ViewGroup bars = mStats.findViewById(R.id.stats_bars);
        ViewGroup labels = mStats.findViewById(R.id.stats_days);
        int lit = daysAgo < 0 ? 0 : daysAgo; // no pick: today's bar stands out
        for (int k = 0; k < bars.getChildCount(); k++) {
            boolean on = 6 - k == lit; // bars run oldest (6 days ago) to today
            View bar = ((ViewGroup) bars.getChildAt(k)).getChildAt(0);
            bar.animate().alpha(on ? 1f : 0.4f).setDuration(Motion.ENTER_MS).start();
            ((TextView) labels.getChildAt(k)).setTextColor(androidx.core.content.ContextCompat.getColor(context,
                    on ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
        }
        TextView footer = mStats.findViewById(R.id.stats_footer);
        if (daysAgo < 0) {
            footer.setText(R.string.mobile_stats_tap);
            return;
        }
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo);
        String name = daysAgo == 0 ? context.getString(R.string.mobile_stats_today)
                : new java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(day.getTime());
        footer.setText(context.getString(R.string.mobile_stats_day, name,
                UsageStats.duration(UsageStats.appMs(daysAgo)), UsageStats.duration(UsageStats.watchMs(daysAgo)),
                String.valueOf(UsageStats.videos(daysAgo))));
    }

    private void number(ViewGroup row, String value, @StringRes int label) {
        android.widget.LinearLayout column = new android.widget.LinearLayout(mActivity);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        TextView big = new TextView(mActivity);
        big.setText(value);
        big.setTextSize(20);
        big.setTypeface(big.getTypeface(), android.graphics.Typeface.BOLD);
        big.setTextColor(androidx.core.content.ContextCompat.getColor(mActivity, R.color.mobile_color_on_surface));
        TextView small = new TextView(mActivity);
        small.setText(label);
        small.setTextSize(12);
        small.setTextColor(androidx.core.content.ContextCompat.getColor(mActivity, R.color.mobile_color_on_surface_secondary));
        big.setGravity(android.view.Gravity.CENTER); // a column's children fill its width: centre the text
        small.setGravity(android.view.Gravity.CENTER);
        column.addView(big);
        column.addView(small);
        row.addView(column, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** A random quote index other than {@code last} (when there is more than one). */
    static int nextQuote(int count, int last, java.util.Random random) {
        if (count < 2) {
            return 0;
        }
        int next = random.nextInt(count - 1);
        return next >= last && last >= 0 ? next + 1 : next;
    }

    /** Index into PERIODS: morning 5-11, afternoon 12-16, evening 17-20, night 21-4. */
    static int period(int hour) {
        return hour >= 5 && hour < 12 ? 0 : hour >= 12 && hour < 17 ? 1 : hour >= 17 && hour < 21 ? 2 : 3;
    }
}
