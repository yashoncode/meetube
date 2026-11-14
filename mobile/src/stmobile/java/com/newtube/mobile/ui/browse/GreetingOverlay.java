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
    private static final int[][] PERIODS = {
            {R.string.mobile_greet_morning, R.string.mobile_greet_morning_line},
            {R.string.mobile_greet_afternoon, R.string.mobile_greet_afternoon_line},
            {R.string.mobile_greet_evening, R.string.mobile_greet_evening_line},
            {R.string.mobile_greet_night, R.string.mobile_greet_night_line},
    };

    private final ComponentActivity mActivity;
    private final View mOverlay;
    private final View mContent;
    private final TextView mTitle;
    private final TextView mLine;
    private final View mStats;
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
        option.setOnClickListener(v -> {
            hide();
            action.run();
        });
        parent.addView(option);
    }

    private void open(String pageId) {
        mActivity.startActivity(MobileSettingsActivity.intent(mActivity, pageId));
    }

    void show() {
        int[] period = PERIODS[period(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))];
        mTitle.setText(period[0]);
        mLine.setText(period[1]);
        bindStats();

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
        if (mOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        mOverlay.animate().cancel();
        mOverlay.animate().alpha(0f).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> mOverlay.setVisibility(View.GONE)).start();
    }

    /** MEETUBE(stats): today, the last 7 days and videos today; the 7 days as bars; watch time and average. */
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

        ViewGroup numbers = mStats.findViewById(R.id.stats_numbers);
        numbers.removeAllViews();
        number(numbers, UsageStats.duration(days[0]), R.string.mobile_stats_today);
        number(numbers, UsageStats.duration(week), R.string.mobile_stats_week);
        number(numbers, String.valueOf(UsageStats.videos(0)), R.string.mobile_stats_videos);

        int accent = com.google.android.material.color.MaterialColors.getColor(mStats,
                androidx.appcompat.R.attr.colorPrimary);
        ViewGroup bars = mStats.findViewById(R.id.stats_bars);
        ViewGroup labels = mStats.findViewById(R.id.stats_days);
        bars.removeAllViews();
        labels.removeAllViews();
        for (int i = 6; i >= 0; i--) {
            View bar = new View(context);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(6 * dp);
            shape.setColor(accent);
            bar.setBackground(shape);
            bar.setAlpha(i == 0 ? 1f : 0.45f);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                    Math.max(Math.round(4 * dp), Math.round(72 * dp * days[i] / max)), 1f);
            lp.setMarginStart(Math.round(4 * dp));
            lp.setMarginEnd(Math.round(4 * dp));
            bars.addView(bar, lp);

            TextView label = new TextView(context);
            label.setText(UsageStats.weekday(i));
            label.setGravity(android.view.Gravity.CENTER);
            label.setTextSize(11);
            label.setTextColor(androidx.core.content.ContextCompat.getColor(context,
                    i == 0 ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
            labels.addView(label, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        ((TextView) mStats.findViewById(R.id.stats_footer)).setText(context.getString(R.string.mobile_stats_footer,
                UsageStats.duration(UsageStats.watchMs(0)), UsageStats.duration(week / 7)));
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
        column.addView(big);
        column.addView(small);
        row.addView(column, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Index into PERIODS: morning 5-11, afternoon 12-16, evening 17-20, night 21-4. */
    static int period(int hour) {
        return hour >= 5 && hour < 12 ? 0 : hour >= 12 && hour < 17 ? 1 : hour >= 17 && hour < 21 ? 2 : 3;
    }
}
