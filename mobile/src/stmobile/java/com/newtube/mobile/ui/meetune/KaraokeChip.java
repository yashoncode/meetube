package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.liskovsoft.smartyoutubetv2.tv.R;

/** A status label carried by the title during the player's minimize animation. */
public final class KaraokeChip extends LinearLayout {
    private final Runnable listener = this::refresh;
    private TextView label;
    private View mic;
    private View progress;

    public KaraokeChip(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        label = findViewById(R.id.karaoke_chip_text);
        mic = findViewById(R.id.karaoke_chip_mic);
        progress = findViewById(R.id.karaoke_chip_progress);
        int ink = label.getCurrentTextColor();
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(MeeTuneAdapter.dp(getContext(), 22));
        pill.setColor(ColorUtils.setAlphaComponent(ink, 46));
        pill.setStroke(1, ColorUtils.setAlphaComponent(ink, 89));
        setBackground(pill);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        MeeTuneKaraoke.addStateListener(listener);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        MeeTuneKaraoke.removeStateListener(listener);
        super.onDetachedFromWindow();
    }

    private void refresh() {
        boolean preparing = MeeTuneKaraoke.isPreparing();
        mic.setVisibility(preparing ? GONE : VISIBLE);
        progress.setVisibility(preparing ? VISIBLE : GONE);
        label.setText(preparing ? R.string.meetune_karaoke_preparing : R.string.meetune_karaoke);
        setVisibility(MeeTuneKaraoke.isOn() ? VISIBLE : GONE);
    }

    int contentWidth() {
        View icon = progress.getVisibility() == VISIBLE ? progress : mic;
        LayoutParams text = (LayoutParams) label.getLayoutParams();
        return getPaddingLeft() + getPaddingRight() + icon.getLayoutParams().width + text.getMarginStart()
                + (int) Math.ceil(label.getPaint().measureText(label.getText().toString()));
    }
}
