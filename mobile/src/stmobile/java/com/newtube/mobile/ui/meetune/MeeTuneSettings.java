package com.newtube.mobile.ui.meetune;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.audiofx.AudioEffect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.AccentCard;
import com.newtube.mobile.ui.common.GlassChipTrack;
import com.newtube.mobile.ui.common.GlassSlider;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.common.Motion;

import java.util.Arrays;
import java.util.Locale;

/**
 * MEETUNE: MeeTune's settings and its Equalizer ({@link MeeTuneEq}), as pages of MeeTune's own screen,
 * drawn in MeeTube's settings look: the same row, header and note layouts on the same glass cards.
 * A page is one view that builds itself again when a value changes what it shows.
 */
final class MeeTuneSettings {
    static final int SETTINGS = 1;
    static final int EQUALIZER = 2;
    static final int CUSTOMIZE = 3;

    interface Host {
        /** Pushes {@link #EQUALIZER} or {@link #CUSTOMIZE}. */
        void open(int which);
    }

    private final Activity mActivity;
    private final Host mHost;
    private final int mWhich;
    private final LayoutInflater mInflater;
    private final LinearLayout mRoot;

    private MeeTuneSettings(Activity activity, int which, Host host) {
        mActivity = activity;
        mWhich = which;
        mHost = host;
        mInflater = LayoutInflater.from(activity);
        mRoot = new LinearLayout(activity);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(0, 0, 0, dp(24));
    }

    static View page(Activity activity, int which, Host host) {
        MeeTuneSettings page = new MeeTuneSettings(activity, which, host);
        page.build();
        return page.mRoot;
    }

    static String title(Context context, int which) {
        return context.getString(which == EQUALIZER ? R.string.meetune_eq
                : which == CUSTOMIZE ? R.string.meetune_set_customize : R.string.meetune_settings);
    }

    private void rebuild() {
        mRoot.removeAllViews();
        build();
    }

    private void build() {
        if (mWhich == EQUALIZER) {
            equalizer();
        } else if (mWhich == CUSTOMIZE) {
            customize();
        } else {
            settings();
        }
    }

    // ------------------------------------------------------------------------------ pages

    private void settings() {
        // Accounts live in Home's corner menu.
        LinearLayout card = card();
        row(card, R.drawable.ic_settings_customize, getString(R.string.meetune_set_customize),
                getString(R.string.meetune_set_customize_summary), null, true, v -> mHost.open(CUSTOMIZE));
        header(R.string.meetune_eq_sound);
        card = card();
        row(card, R.drawable.ic_player_equalizer, getString(R.string.meetune_eq), summary(), null, true,
                v -> mHost.open(EQUALIZER));
        String[] streams = {getString(R.string.meetune_stream_high), getString(R.string.meetune_stream_normal),
                getString(R.string.meetune_stream_low)};
        String[] streamNotes = {getString(R.string.meetune_stream_high_summary),
                getString(R.string.meetune_stream_normal_summary), getString(R.string.meetune_stream_low_summary)};
        int stream = MeeTune.streamQuality(mActivity);
        card.addView(divider());
        row(card, R.drawable.ic_mobile_tune, getString(R.string.meetune_set_stream), streams[stream], null, true,
                v -> choose(getString(R.string.meetune_set_stream), streams, streamNotes, stream, picked -> {
                    MeeTune.setStreamQuality(mActivity, picked);
                    com.newtube.mobile.ui.playback.MiniPlayerBridge.applyMusicQuality(); // the playing song too
                }));

        // MeeTube's download folder for music and its download qualities; a song's own default.
        header(R.string.header_downloads);
        card = card();
        if (com.newtube.mobile.downloads.DownloadFolder.isSupported()) {
            row(card, R.drawable.ic_watch_download, getString(R.string.mobile_download_folder_audio),
                    com.newtube.mobile.downloads.DownloadFolder.label(mActivity, true).toString(), null, true, v -> {
                        android.app.Dialog dialog = com.newtube.mobile.downloads.DownloadFolder.showOptions(mActivity, true);
                        if (dialog != null) dialog.setOnDismissListener(d -> mRoot.postDelayed(this::rebuild, 300));
                    });
            card.addView(divider());
        }
        // Songs download as sound only: the two AAC qualities YouTube has for a song.
        int[] values = {com.newtube.mobile.downloads.AutoDownload.AUDIO, com.newtube.mobile.downloads.DownloadPicker.AUDIO_LOW};
        String[] qualityLabels = {getString(R.string.meetune_stream_high), getString(R.string.meetune_stream_low)};
        String[] qualityNotes = {getString(R.string.meetune_download_high_summary), getString(R.string.meetune_download_low_summary)};
        int quality = MeeTune.downloadQuality(mActivity) == values[1] ? 1 : 0;
        row(card, R.drawable.ic_mobile_tune, getString(R.string.mobile_settings_download_quality), qualityLabels[quality],
                null, true, v -> choose(getString(R.string.mobile_settings_download_quality), qualityLabels, qualityNotes,
                        quality, picked -> MeeTune.setDownloadQuality(mActivity, values[picked])));
        card.addView(divider());
        row(card, R.drawable.ic_watch_lyrics, getString(R.string.meetune_download_lyrics),
                getString(R.string.meetune_download_lyrics_summary), MeeTune.downloadLyrics(mActivity), false,
                v -> MeeTune.setDownloadLyrics(mActivity, !MeeTune.downloadLyrics(mActivity)));
        note(R.string.meetune_downloads_note);
    }

    /** MeeTube's choice sheet (its settings' radio list in the frosted bottom sheet); a pick rebuilds the page. */
    private void choose(String title, String[] labels, @Nullable String[] notes, int current,
                        java.util.function.IntConsumer onPick) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = Haptics.watch(
                new com.google.android.material.bottomsheet.BottomSheetDialog(mActivity));
        View content = mInflater.inflate(R.layout.sheet_mobile_speed, null);
        ((TextView) content.findViewById(R.id.speed_sheet_title)).setText(title);
        content.findViewById(R.id.speed_sheet_more).setVisibility(View.GONE);
        ViewGroup list = content.findViewById(R.id.speed_sheet_list);
        for (int i = 0; i < labels.length; i++) {
            View view = mInflater.inflate(R.layout.item_mobile_settings_choice, list, false);
            ((android.widget.RadioButton) view.findViewById(R.id.settings_choice_radio)).setChecked(i == current);
            ((TextView) view.findViewById(R.id.settings_choice_label)).setText(labels[i]);
            TextView description = view.findViewById(R.id.settings_choice_description);
            description.setText(notes != null ? notes[i] : null);
            description.setVisibility(notes != null ? View.VISIBLE : View.GONE);
            view.findViewById(R.id.settings_choice_icon).setVisibility(View.GONE);
            int which = i;
            view.setOnClickListener(v -> {
                sheet.dismiss();
                if (which == current) return;
                onPick.accept(which);
                rebuild();
            });
            list.addView(view);
        }
        sheet.setContentView(content);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
    }

    private void customize() {
        LinearLayout card = card();
        row(card, 0, getString(R.string.meetune_set_you), getString(R.string.meetune_set_you_summary),
                MeeTune.youButton(mActivity), false, v -> MeeTune.setYouButton(mActivity, !MeeTune.youButton(mActivity)));
        row(card, 0, getString(R.string.meetune_set_brand_name), getString(R.string.meetune_set_brand_name_summary),
                MeeTune.brandName(mActivity), false, v -> MeeTune.setBrandName(mActivity, !MeeTune.brandName(mActivity)));
        // MeeTube's own Search glow and Typing glow (Settings > Effects there): the same switches.
        row(card, 0, getString(R.string.mobile_settings_search_glow), getString(R.string.mobile_settings_search_glow_summary),
                com.newtube.mobile.ui.search.SiriGlow.enabled(mActivity), false,
                v -> com.newtube.mobile.ui.search.SiriGlow.setEnabled(mActivity,
                        !com.newtube.mobile.ui.search.SiriGlow.enabled(mActivity)));
        row(card, 0, getString(R.string.mobile_settings_typing_glow), getString(R.string.mobile_settings_typing_glow_summary),
                com.newtube.mobile.ui.search.GlowEditText.enabled(mActivity), false,
                v -> com.newtube.mobile.ui.search.GlowEditText.setEnabled(mActivity,
                        !com.newtube.mobile.ui.search.GlowEditText.enabled(mActivity)));
    }

    /** "Off", "Dynamic", or the Manual preset's name. */
    private String summary() {
        if (!MeeTuneEq.isEnabled(mActivity)) {
            return getString(R.string.meetune_eq_off);
        }
        return MeeTuneEq.mode(mActivity) == MeeTuneEq.DYNAMIC ? getString(R.string.meetune_eq_dynamic) : presetName();
    }

    private String presetName() {
        int preset = MeeTuneEq.preset(mActivity);
        String[] names = mActivity.getResources().getStringArray(R.array.meetune_eq_presets);
        return preset >= 0 && preset < names.length ? names[preset] : getString(R.string.meetune_eq_custom);
    }

    private void equalizer() {
        LinearLayout card = card();
        row(card, R.drawable.ic_player_equalizer, getString(R.string.meetune_eq), null,
                MeeTuneEq.isEnabled(mActivity), false, v -> MeeTuneEq.setEnabled(mActivity, !MeeTuneEq.isEnabled(mActivity)));
        note(R.string.meetune_eq_note);
        modes();

        if (MeeTuneEq.mode(mActivity) == MeeTuneEq.DYNAMIC) {
            header(R.string.meetune_eq_tone);
            tone();
            note(MeeTuneEq.isFocused(mActivity) ? R.string.meetune_eq_tone_note_focused : R.string.meetune_eq_tone_note_broad);
        } else {
            header(R.string.meetune_eq_preset);
            GlassChipTrack presets = presets();
            header(R.string.meetune_eq_bands);
            BandSliders sliders = bands(presets);
            mPresetsPicked = index -> {
                Haptics.click(presets);
                String[] names = mActivity.getResources().getStringArray(R.array.meetune_eq_presets);
                if (index < names.length) {
                    MeeTuneEq.setPreset(mActivity, index);
                } else {
                    MeeTuneEq.setBands(mActivity, MeeTuneEq.bands(mActivity)); // the preset's curve, now your own
                }
                sliders.show(MeeTuneEq.bands(mActivity));
            };
            note(R.string.meetune_eq_bands_note);
        }

        header(R.string.meetune_eq_balance);
        balance();
        note(R.string.meetune_eq_balance_note);

        card = card();
        row(card, R.drawable.ic_player_equalizer, getString(R.string.meetune_eq_system),
                getString(R.string.meetune_eq_system_summary), null, true, v -> {
                    try {
                        mActivity.startActivity(new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mActivity.getPackageName())
                                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC));
                    } catch (RuntimeException e) {
                        MobileSnackbar.show(mActivity, R.string.meetune_eq_system_none);
                    }
                });
        note(R.string.meetune_eq_system_note);
    }

    // ------------------------------------------------------------------------------ controls

    /** Dynamic | Manual, MeeTube's glass segmented control; the page changes once the pill has slid. */
    private void modes() {
        FrameLayout frame = new FrameLayout(mActivity);
        frame.setPadding(dp(14), dp(8), dp(14), dp(8));
        frame.setClipToPadding(false); // the track swells and stretches as the pill runs: never cut flat
        GlassChipTrack track = new GlassChipTrack(mActivity);
        track.setGlassSource(null);
        track.setChips(Arrays.asList(getString(R.string.meetune_eq_dynamic), getString(R.string.meetune_eq_manual)));
        track.setSelected(MeeTuneEq.mode(mActivity));
        track.setOnPicked(index -> {
            if (index == MeeTuneEq.mode(mActivity)) {
                return;
            }
            Haptics.click(track);
            MeeTuneEq.setMode(mActivity, index);
            track.postDelayed(this::rebuild, 240);
        });
        frame.addView(track, new FrameLayout.LayoutParams(dp(240), dp(38), Gravity.CENTER_HORIZONTAL));
        mRoot.addView(frame);
    }

    @Nullable
    private GlassChipTrack.Picked mPresetsPicked;

    /** The presets as a sideways row of glass pills, Custom last: a tap sets the bands under it at once. */
    private GlassChipTrack presets() {
        String[] names = mActivity.getResources().getStringArray(R.array.meetune_eq_presets);
        java.util.List<String> labels = new java.util.ArrayList<>(Arrays.asList(names));
        labels.add(getString(R.string.meetune_eq_custom));
        FrameLayout frame = new FrameLayout(mActivity);
        frame.setPadding(dp(14), dp(4), dp(14), dp(8));
        frame.setClipToPadding(false);
        GlassChipTrack track = new GlassChipTrack(mActivity);
        track.setGlassSource(null);
        track.setChips(labels);
        int preset = MeeTuneEq.preset(mActivity);
        track.setSelected(preset == MeeTuneEq.CUSTOM ? names.length : preset);
        track.setOnPicked(index -> {
            if (mPresetsPicked != null) {
                mPresetsPicked.onPicked(index);
            }
        });
        frame.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        mRoot.addView(frame);
        return track;
    }

    private void tone() {
        LinearLayout card = card();
        card.setPadding(0, 0, 0, 0);
        TonePad pad = new TonePad(mActivity);
        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270));
        padParams.setMargins(dp(16), dp(16), dp(16), 0);
        card.addView(pad, padParams);

        TextView readout = text(15, R.color.mobile_color_on_surface);
        readout.setGravity(Gravity.CENTER);
        readout.setPadding(0, dp(6), 0, dp(14));
        card.addView(readout);
        Runnable show = () -> {
            readout.setText(mActivity.getString(R.string.meetune_eq_tone_readout, signed(pad.mX), signed(pad.mY)));
            pad.setContentDescription(mActivity.getString(R.string.meetune_eq_tone_desc, signed(pad.mX), signed(pad.mY)));
        };
        pad.set(MeeTuneEq.toneX(mActivity), MeeTuneEq.toneY(mActivity));
        show.run();
        pad.mMoved = () -> {
            MeeTuneEq.setTone(mActivity, pad.mX, pad.mY);
            show.run();
        };

        // Broad / Focused, ticked like a choice dialog's rows, inside the pad's card.
        boolean focused = MeeTuneEq.isFocused(mActivity);
        int[] labels = {R.string.meetune_eq_broad, R.string.meetune_eq_focused};
        for (int i = 0; i < 2; i++) {
            card.addView(divider());
            boolean value = i == 1;
            LinearLayout option = new LinearLayout(mActivity);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setMinimumHeight(dp(54));
            option.setPadding(dp(20), 0, dp(20), 0);
            option.setBackgroundResource(selectable());
            TextView label = text(16, R.color.mobile_color_on_surface);
            label.setText(labels[i]);
            option.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView check = new ImageView(mActivity);
            check.setImageResource(R.drawable.ic_mobile_check);
            check.setImageTintList(ContextCompat.getColorStateList(mActivity, R.color.mobile_color_on_surface));
            check.setVisibility(value == focused ? View.VISIBLE : View.INVISIBLE);
            option.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
            option.setOnClickListener(v -> {
                if (MeeTuneEq.isFocused(mActivity) == value) {
                    return;
                }
                Haptics.click(v);
                MeeTuneEq.setFocused(mActivity, value);
                rebuild(); // the ticks, and the note under the card says what it does
            });
            card.addView(option);
        }
    }

    private BandSliders bands(GlassChipTrack presets) {
        LinearLayout card = card();
        card.setPadding(0, 0, 0, 0);
        BandSliders sliders = new BandSliders(mActivity, MeeTuneEq.bands(mActivity));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270));
        params.setMargins(dp(8), dp(12), dp(8), dp(8));
        card.addView(sliders, params);
        sliders.mChanged = done -> {
            MeeTuneEq.setBands(mActivity, sliders.mGains);
            if (done) {
                presets.setSelected(MeeTuneEq.PRESETS.length); // a band moved: the curve is now Custom
            }
        };

        card.addView(divider());
        TextView reset = text(16, R.color.mobile_color_on_surface);
        reset.setText(R.string.meetune_eq_reset);
        reset.setGravity(Gravity.CENTER);
        reset.setMinHeight(dp(54));
        reset.setBackgroundResource(selectable());
        reset.setOnClickListener(v -> {
            Haptics.click(v);
            MeeTuneEq.setPreset(mActivity, 0); // Flat
            presets.setSelected(0);
            sliders.show(MeeTuneEq.bands(mActivity));
        });
        card.addView(reset);
        return sliders;
    }

    private void balance() {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout labels = new LinearLayout(mActivity);
        labels.setPadding(dp(8), 0, dp(8), 0);
        TextView left = text(17, R.color.mobile_color_on_surface);
        left.setText(R.string.meetune_eq_left);
        TextView value = text(17, R.color.mobile_color_on_surface_secondary);
        value.setGravity(Gravity.CENTER);
        TextView right = text(17, R.color.mobile_color_on_surface);
        right.setText(R.string.meetune_eq_right);
        labels.addView(left);
        labels.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(right);
        card.addView(labels);

        GlassSlider slider = new GlassSlider(mActivity);
        slider.setMax(200);
        slider.setProgress(Math.round((MeeTuneEq.balance(mActivity) + 1) * 100));
        slider.setContentDescription(getString(R.string.meetune_eq_balance));
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderParams.topMargin = dp(6);
        card.addView(slider, sliderParams);
        Runnable show = () -> value.setText(String.format(Locale.US, "%.2f", (slider.getProgress() - 100) / 100f));
        show.run();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                if (progress != 100 && Math.abs(progress - 100) <= 3) { // the centre holds the thumb a little
                    s.setProgress(100);
                    Haptics.tick(s);
                }
                show.run();
                MeeTuneEq.setBalance(mActivity, (s.getProgress() - 100) / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    // ------------------------------------------------------------------------------ MeeTube's settings look

    /** A run of rows on MeeTube's settings glass card, inset and rounded as its cards are. */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new AccentCard(mActivity));
        card.setPadding(0, dp(6), 0, dp(6));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(26));
            }
        });
        card.setClipToOutline(true); // the rows' press ripple stays inside the rounded corners
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(14), dp(6), dp(14), dp(6));
        mRoot.addView(card, params);
        return card;
    }

    /** A settings row: icon, title, summary, then a switch ({@code checked} not null) or a chevron. */
    private void row(LinearLayout card, int icon, String title, @Nullable String summary, @Nullable Boolean checked,
                     boolean chevron, View.OnClickListener onClick) {
        View row = mInflater.inflate(R.layout.item_mobile_settings_row, card, false);
        ImageView iconView = row.findViewById(R.id.settings_row_icon);
        if (icon != 0) {
            iconView.setImageResource(icon);
            iconView.setVisibility(View.VISIBLE);
        }
        ((TextView) row.findViewById(R.id.settings_row_title)).setText(title);
        TextView summaryView = row.findViewById(R.id.settings_row_summary);
        summaryView.setText(summary);
        summaryView.setVisibility(summary != null ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.settings_row_chevron).setVisibility(chevron ? View.VISIBLE : View.GONE);
        CompoundButton toggle = row.findViewById(R.id.settings_row_switch);
        if (checked != null) {
            toggle.setVisibility(View.VISIBLE);
            toggle.setChecked(checked);
        }
        row.setOnClickListener(v -> {
            Haptics.click(v);
            if (checked != null) {
                toggle.toggle();
            }
            onClick.onClick(v);
        });
        card.addView(row);
    }

    private void header(int text) {
        TextView header = (TextView) mInflater.inflate(R.layout.item_mobile_settings_header, mRoot, false);
        header.setText(text);
        mRoot.addView(header);
    }

    private void note(int text) {
        TextView note = (TextView) mInflater.inflate(R.layout.item_mobile_settings_note, mRoot, false);
        note.setText(text);
        mRoot.addView(note);
    }

    private View divider() {
        View line = new View(mActivity);
        line.setBackgroundColor(ContextCompat.getColor(mActivity, R.color.mobile_color_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        params.setMarginStart(dp(20));
        line.setLayoutParams(params);
        return line;
    }

    private TextView text(int sp, int color) {
        TextView text = new TextView(mActivity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(ContextCompat.getColor(mActivity, color));
        return text;
    }

    private int selectable() {
        TypedValue value = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private String getString(int res) {
        return mActivity.getString(res);
    }

    private int dp(float dp) {
        return Math.round(dp * mActivity.getResources().getDisplayMetrics().density);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String signed(float db) {
        String number = db == Math.round(db) ? String.valueOf(Math.round(db)) : String.format(Locale.US, "%.1f", db);
        return db > 0 ? "+" + number : number;
    }

    private static int ink(Context context, int color, int alpha) {
        return (ContextCompat.getColor(context, color) & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * A round glass knob, drawn as MeeTube's sliders draw theirs: white with a soft shadow at rest;
     * held ({@code lift} 1), a clear lens with a bright rim and a glint, what's under it showing through.
     */
    private static void glassKnob(Canvas canvas, Paint paint, Paint shadow, RectF oval, float cx, float cy,
                                  float radius, float lift, float dp) {
        float glass = Math.max(0f, Math.min(1f, lift));
        shadow.setStyle(Paint.Style.FILL);
        shadow.setColor(Color.TRANSPARENT);
        shadow.setShadowLayer((3 + 5 * glass) * dp, 0, (1 + 2 * glass) * dp,
                Color.argb(Math.round(255 * (0.18f + 0.08f * glass)), 0, 0, 0));
        canvas.drawCircle(cx, cy, radius, shadow);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(255 - 215 * glass), 255, 255, 255));
        canvas.drawCircle(cx, cy, radius, paint);
        if (glass > 0f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.9f * dp);
            paint.setColor(Color.argb(Math.round(235 * glass), 255, 255, 255));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStrokeWidth(1.6f * dp);
            paint.setColor(Color.argb(Math.round(110 * glass), 255, 255, 255));
            oval.set(cx - radius + 3 * dp, cy - radius + 3 * dp, cx + radius - 3 * dp, cy + radius - 3 * dp);
            canvas.drawArc(oval, 200, 80, false, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** The knob lifting into glass under a finger (to 1) and settling back to white (to 0). */
    private static ValueAnimator lift(float from, float to, ValueAnimator.AnimatorUpdateListener update) {
        ValueAnimator animator = ValueAnimator.ofFloat(from, to).setDuration(to > from ? 220 : 260);
        animator.setInterpolator(to > from ? new android.view.animation.OvershootInterpolator(1.6f) : Motion.STANDARD_DECELERATE);
        animator.addUpdateListener(update);
        animator.start();
        return animator;
    }

    // ------------------------------------------------------------------------------ views

    /**
     * Dynamic's pad: a grid of dots, -5..5 each way, the centre row and column a shade brighter, and a
     * round puck that follows the finger and settles on the nearest dot. A tick per dot crossed.
     */
    private static final class TonePad extends View {
        private static final int DOTS = MeeTuneEq.TONE_STEPS * 2 + 1;
        int mX;
        int mY;
        Runnable mMoved;
        private float mPuckX = Float.NaN;
        private float mPuckY = Float.NaN;
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPuck = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mOval = new RectF();
        private final int mFaint;
        private final int mStrong;
        private final float mDp;
        private ValueAnimator mSettle;
        private ValueAnimator mLiftAnimator;
        private float mLift;

        TonePad(Context context) {
            super(context);
            mDp = context.getResources().getDisplayMetrics().density;
            mFaint = ink(context, R.color.mobile_color_on_surface, 0x40);
            mStrong = ink(context, R.color.mobile_color_on_surface, 0x99);
            setFocusable(true);
        }

        private void lift(float to) {
            if (mLiftAnimator != null) {
                mLiftAnimator.cancel();
            }
            mLiftAnimator = MeeTuneSettings.lift(mLift, to, a -> {
                mLift = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        void set(int x, int y) {
            mX = x;
            mY = y;
            mPuckX = Float.NaN;
            invalidate();
        }

        /** Room round the grid for the knob at its biggest, shadow and all: it never meets the edge. */
        private float inset() {
            return 32 * mDp;
        }

        private float cellX() {
            return (getWidth() - 2 * inset()) / (DOTS - 1);
        }

        private float cellY() {
            return (getHeight() - 2 * inset()) / (DOTS - 1);
        }

        private float dotX(int step) {
            return inset() + (step + MeeTuneEq.TONE_STEPS) * cellX();
        }

        private float dotY(int step) {
            return inset() + (MeeTuneEq.TONE_STEPS - step) * cellY(); // up is +
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            for (int x = -MeeTuneEq.TONE_STEPS; x <= MeeTuneEq.TONE_STEPS; x++) {
                for (int y = -MeeTuneEq.TONE_STEPS; y <= MeeTuneEq.TONE_STEPS; y++) {
                    mDot.setColor(x == 0 || y == 0 ? mStrong : mFaint);
                    canvas.drawCircle(dotX(x), dotY(y), 2.2f * mDp, mDot);
                }
            }
            float px = Float.isNaN(mPuckX) ? dotX(mX) : mPuckX;
            float py = Float.isNaN(mPuckY) ? dotY(mY) : mPuckY;
            glassKnob(canvas, mPuck, mShadow, mOval, px, py, (16 + 6 * mLift) * mDp, mLift, mDp);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if (mSettle != null) {
                        mSettle.cancel();
                    }
                    lift(1f);
                    // fall through
                case MotionEvent.ACTION_MOVE: {
                    mPuckX = Math.max(inset(), Math.min(getWidth() - inset(), event.getX()));
                    mPuckY = Math.max(inset(), Math.min(getHeight() - inset(), event.getY()));
                    int x = Math.round((mPuckX - inset()) / cellX()) - MeeTuneEq.TONE_STEPS;
                    int y = MeeTuneEq.TONE_STEPS - Math.round((mPuckY - inset()) / cellY());
                    if (x != mX || y != mY) {
                        mX = x;
                        mY = y;
                        Haptics.tick(this);
                        if (mMoved != null) {
                            mMoved.run();
                        }
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lift(0f);
                    settle();
                    return true;
                default:
                    return true;
            }
        }

        /** The puck glides onto its dot. */
        private void settle() {
            float fromX = mPuckX;
            float fromY = mPuckY;
            if (Float.isNaN(fromX)) {
                return;
            }
            mSettle = ValueAnimator.ofFloat(0f, 1f).setDuration(180);
            mSettle.setInterpolator(Motion.STANDARD_DECELERATE);
            mSettle.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                mPuckX = fromX + (dotX(mX) - fromX) * f;
                mPuckY = fromY + (dotY(mY) - fromY) * f;
                if (f >= 1f) {
                    mPuckX = Float.NaN;
                    mPuckY = Float.NaN;
                }
                invalidate();
            });
            mSettle.start();
        }
    }

    /** Manual's seven bands as upright sliders: the gain above each, its frequency below. */
    private static final class BandSliders extends View {
        interface Changed {
            void changed(boolean done);
        }

        final float[] mGains;
        Changed mChanged;
        private int mBand = -1;
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mOval = new RectF();
        /** The band whose knob is lifted into glass (it settles back after the finger leaves). */
        private int mLiftBand = -1;
        private float mLift;
        private ValueAnimator mLiftAnimator;
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mPrimary;
        private final int mSecondary;
        private final float mDp;

        BandSliders(Context context, float[] gains) {
            super(context);
            mGains = gains;
            mDp = context.getResources().getDisplayMetrics().density;
            mPrimary = ContextCompat.getColor(context, R.color.mobile_color_on_surface);
            mSecondary = ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary);
            mTrack.setColor(ink(context, R.color.mobile_color_on_surface, 0x26));
            mTrack.setStrokeCap(Paint.Cap.ROUND);
            mTrack.setStrokeWidth(5 * mDp);
            mFill.setColor(mPrimary);
            mFill.setStrokeCap(Paint.Cap.ROUND);
            mFill.setStrokeWidth(5 * mDp);
            mText.setTextAlign(Paint.Align.CENTER);
            mText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, context.getResources().getDisplayMetrics()));
            describe();
        }

        /** New gains (a preset picked): the knobs jump there. */
        void show(float[] gains) {
            System.arraycopy(gains, 0, mGains, 0, mGains.length);
            describe();
            invalidate();
        }

        private float top() {
            return 44 * mDp;
        }

        private float bottom() {
            return getHeight() - 44 * mDp;
        }

        private void lift(float to) {
            if (mLiftAnimator != null) {
                mLiftAnimator.cancel();
            }
            mLiftAnimator = MeeTuneSettings.lift(mLift, to, a -> {
                mLift = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        private float zero() {
            return (top() + bottom()) / 2;
        }

        private float y(float db) {
            return zero() - db / MeeTuneEq.MAX_DB * (bottom() - top()) / 2;
        }

        private float x(int band) {
            return getWidth() * (band + 0.5f) / mGains.length;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            for (int i = 0; i < mGains.length; i++) {
                float x = x(i);
                canvas.drawLine(x, top(), x, bottom(), mTrack);
                float knob = y(mGains[i]);
                if (mGains[i] != 0) {
                    canvas.drawLine(x, zero(), x, knob, mFill);
                }
                float lift = i == mLiftBand ? mLift : 0f;
                glassKnob(canvas, mKnob, mShadow, mOval, x, knob, (12 + 4 * lift) * mDp, lift, mDp);
                boolean set = mGains[i] != 0;
                mText.setColor(set ? mPrimary : mSecondary);
                mText.setTypeface(set ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                canvas.drawText(signed(mGains[i]), x, top() - 18 * mDp, mText);
                mText.setColor(mSecondary);
                mText.setTypeface(Typeface.DEFAULT);
                canvas.drawText(MeeTuneEq.LABELS[i], x, bottom() + 30 * mDp, mText);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    mBand = Math.max(0, Math.min(mGains.length - 1, (int) (event.getX() / getWidth() * mGains.length)));
                    if (mLiftBand != mBand) {
                        mLift = 0f;
                    }
                    mLiftBand = mBand;
                    lift(1f);
                    // fall through
                case MotionEvent.ACTION_MOVE: {
                    if (mBand < 0) {
                        return true;
                    }
                    float db = (zero() - event.getY()) / ((bottom() - top()) / 2) * MeeTuneEq.MAX_DB;
                    db = Math.max(-MeeTuneEq.MAX_DB, Math.min(MeeTuneEq.MAX_DB, Math.round(db * 2) / 2f));
                    if (db != mGains[mBand]) {
                        if (Math.floor(db) != Math.floor(mGains[mBand])) {
                            Haptics.tick(this);
                        }
                        mGains[mBand] = db;
                        describe();
                        if (mChanged != null) {
                            mChanged.changed(false);
                        }
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mBand >= 0) {
                        mBand = -1;
                        lift(0f);
                        if (mChanged != null) {
                            mChanged.changed(true);
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }

        private void describe() {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < mGains.length; i++) {
                text.append(MeeTuneEq.LABELS[i]).append(": ").append(signed(mGains[i])).append(" dB. ");
            }
            setContentDescription(text);
        }
    }
}
