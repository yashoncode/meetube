package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.AudioEffects;
import com.newtube.mobile.player.AudioPreferences;
import com.newtube.mobile.player.HeadphoneEq;
import com.newtube.mobile.ui.common.FocusRing;
import com.newtube.mobile.ui.common.GlassDrawable;
import com.newtube.mobile.ui.common.GlassSlider;
import com.newtube.mobile.ui.common.GlassSwitch;
import com.newtube.mobile.ui.common.MobileSheets;

import java.text.DecimalFormat;
import java.util.Locale;

/** MEETUBE(issue #177): the phone's device equalizer, applied live to the playing session. */
public final class EqualizerSheet {
    /** MEETUBE(eq bands): the bands stand upright side by side, as on a mixing desk; else rows. */
    private static final String KEY_VERTICAL = "equalizer_vertical";
    private final Context mContext;
    private final LayoutInflater mInflater;
    /** Glass pills, as Search's chips; the picked one a solid pill. */
    private final ViewGroup mPresets;
    private final LinearLayout mBandRows;
    private final View mBandsHeader;
    @Nullable private AudioEffects.Bands mBands;

    private EqualizerSheet(Context context, View content) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mPresets = content.findViewById(R.id.equalizer_presets);
        mBandRows = content.findViewById(R.id.equalizer_bands);
        mBandsHeader = content.findViewById(R.id.equalizer_bands_header);
        View rotate = content.findViewById(R.id.equalizer_rotate);
        GlassDrawable.applyPage(rotate, null, 0);
        rotate.setOnClickListener(v -> {
            prefs(context).edit().putBoolean(KEY_VERTICAL, !isVertical(context)).apply();
            v.animate().rotationBy(isVertical(context) ? 90f : -90f).setDuration(260).start();
            showBands();
        });
        mBands = AudioEffects.bands(context);

        GlassSwitch enabled = content.findViewById(R.id.equalizer_enabled);
        enabled.setChecked(AudioPreferences.isEqualizerEnabled(context));
        enabled.setOnCheckedChangeListener((button, on) -> AudioPreferences.setEqualizerEnabled(context, on));
        content.findViewById(R.id.equalizer_enabled_row).setOnClickListener(v -> enabled.toggle());

        TextView autoEq = content.findViewById(R.id.equalizer_autoeq_summary);
        Runnable showAutoEq = () -> {
            String name = HeadphoneEq.name(context);
            autoEq.setText(name.isEmpty() ? context.getString(R.string.mobile_settings_autoeq_summary) : name);
        };
        showAutoEq.run();
        content.findViewById(R.id.equalizer_autoeq_row).setOnClickListener(v -> HeadphonePicker.show(context, showAutoEq));

        if (mBands != null) {
            for (int preset = 0; preset < mBands.presets.length; preset++) {
                addPreset(mBands.presets[preset], preset);
            }
        }
        addPreset(context.getString(R.string.mobile_settings_equalizer_custom), AudioPreferences.CUSTOM);
        selectPreset();
        showBands();

        View reset = content.findViewById(R.id.equalizer_reset);
        reset.setEnabled(mBands != null);
        reset.setOnClickListener(v -> {
            if (mBands != null) {
                AudioPreferences.setCustomLevels(context, new short[mBands.levels.length]);
                mBands = AudioEffects.bands(context);
                selectPreset();
                showBands();
            }
        });
    }

    static void show(SettingsPageFragment page) {
        BottomSheetDialog sheet = create(page.requireContext());
        sheet.setOnDismissListener(dialog -> page.rebuild());
        page.showDialog(sheet);
        sheet.show();
    }

    /** MEETUBE(issue #236): the same sheet from the player's gear menu; the caller shows it. */
    public static BottomSheetDialog create(Context context) {
        BottomSheetDialog sheet = com.newtube.mobile.ui.common.Haptics.watch(new BottomSheetDialog(context)); // MEETUBE(glass): frosted
        View content = LayoutInflater.from(sheet.getContext()).inflate(R.layout.sheet_mobile_equalizer, null);
        new EqualizerSheet(sheet.getContext(), content);
        sheet.setContentView(content);
        // Surface and elevation overlay come from bottomSheetDialogTheme, like the other sheets.
        sheet.setOnShowListener(dialog -> MobileSheets.expandTo(sheet, 0.85f));
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        FocusRing.install(sheet.getWindow());
        FocusRing.focusFirstRow(sheet.getWindow());
        return sheet;
    }

    public static CharSequence summary(Context context) {
        if (!AudioPreferences.isEqualizerEnabled(context)) {
            return context.getString(R.string.mobile_settings_off);
        }
        String preset = AudioPreferences.getPresetName(context);
        return AudioPreferences.getPreset(context) >= 0 && !preset.isEmpty() ? preset
                : context.getString(R.string.mobile_settings_equalizer_custom);
    }

    private void addPreset(String label, int preset) {
        TextView chip = (TextView) mInflater.inflate(R.layout.mobile_search_chip, mPresets, false);
        ((ViewGroup.MarginLayoutParams) chip.getLayoutParams()).setMarginEnd(0); // the group spaces them
        chip.setElevation(0); // on the sheet's glass, not over a page
        chip.setTag(preset);
        chip.setText(label);
        GlassDrawable.applyPage(chip, null, 0);
        chip.setTag(R.id.equalizer_presets, chip.getBackground());
        chip.setEnabled(mBands != null);
        chip.setAlpha(mBands != null ? 1f : 0.5f);
        chip.setOnClickListener(v -> {
            AudioPreferences.setPreset(mContext, preset, label);
            mBands = AudioEffects.bands(mContext);
            selectPreset();
            showBands();
        });
        mPresets.addView(chip);
    }

    private void selectPreset() {
        int preset = AudioPreferences.getPreset(mContext);
        if (mBands == null || preset < 0 || preset >= mBands.presets.length) {
            preset = AudioPreferences.CUSTOM;
        }
        for (int i = 0; i < mPresets.getChildCount(); i++) {
            TextView chip = (TextView) mPresets.getChildAt(i);
            boolean picked = (int) chip.getTag() == preset;
            chip.setSelected(picked);
            if (picked) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(18 * mContext.getResources().getDisplayMetrics().density);
                bg.setColor(GlassDrawable.pickedPill(mContext));
                chip.setBackground(bg);
            } else {
                chip.setBackground((android.graphics.drawable.Drawable) chip.getTag(R.id.equalizer_presets));
            }
            chip.setTextColor(picked ? GlassDrawable.pickedInk(mContext) : GlassDrawable.pageInk(mContext));
        }
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("meetube_audio", Context.MODE_PRIVATE);
    }

    private static boolean isVertical(Context context) {
        return prefs(context).getBoolean(KEY_VERTICAL, false);
    }

    /** One upright band: its level on top, the slider stood on end (low at the bottom), its frequency below. */
    private View verticalBand() {
        float dp = mContext.getResources().getDisplayMetrics().density;
        LinearLayout column = new LinearLayout(mContext);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(label(R.id.equalizer_band_level, R.color.mobile_color_on_surface_secondary));
        int height = Math.round(220 * dp);
        FrameLayout track = new FrameLayout(mContext);
        GlassSlider slider = new GlassSlider(mContext);
        slider.setId(R.id.equalizer_band_slider);
        slider.setRotation(-90f);
        slider.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true); // an up/down drag is the band's, not the sheet's
            }
            return false;
        });
        track.addView(slider, new FrameLayout.LayoutParams(height, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        column.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        column.addView(label(R.id.equalizer_band_frequency, R.color.mobile_color_on_surface));
        return column;
    }

    private TextView label(int id, int color) {
        TextView label = new TextView(mContext);
        label.setId(id);
        label.setTextSize(12);
        label.setMaxLines(1);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(androidx.core.content.ContextCompat.getColor(mContext, color));
        return label;
    }

    private void showBands() {
        mBandRows.removeAllViews();
        mBandsHeader.setVisibility(mBands == null ? View.GONE : View.VISIBLE);
        boolean vertical = mBands != null && isVertical(mContext);
        mBandRows.setOrientation(vertical ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (mBands == null) {
            TextView unavailable = new TextView(mContext);
            unavailable.setText(R.string.mobile_settings_equalizer_unavailable);
            unavailable.setTextColor(androidx.core.content.ContextCompat.getColor(mContext,
                    R.color.mobile_color_on_surface_secondary));
            mBandRows.addView(unavailable);
            return;
        }
        AudioEffects.Bands bands = mBands;
        for (int band = 0; band < bands.levels.length; band++) {
            int index = band;
            View row = vertical ? verticalBand() : mInflater.inflate(R.layout.item_mobile_equalizer_band, mBandRows, false);
            String frequency = frequencyLabel(bands.frequencies[band]);
            ((TextView) row.findViewById(R.id.equalizer_band_frequency)).setText(frequency);
            TextView level = row.findViewById(R.id.equalizer_band_level);
            level.setText(levelLabel(bands.levels[band]));
            SeekBar slider = row.findViewById(R.id.equalizer_band_slider);
            slider.setContentDescription(frequency);
            slider.setMax(bands.maxLevel - bands.minLevel);
            slider.setProgress(bands.levels[band] - bands.minLevel);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        bands.levels[index] = (short) (bands.minLevel + progress);
                        level.setText(levelLabel(bands.levels[index]));
                        AudioPreferences.setCustomLevels(mContext, bands.levels);
                        selectPreset();
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            mBandRows.addView(row);
        }
    }

    private static String frequencyLabel(int millihertz) {
        float hz = millihertz / 1000f;
        return hz >= 1000 ? new DecimalFormat("0.#").format(hz / 1000f) + " kHz" : Math.round(hz) + " Hz";
    }

    private static String levelLabel(short millibels) {
        return String.format(Locale.getDefault(), "%+.1f dB", millibels / 100f);
    }
}
