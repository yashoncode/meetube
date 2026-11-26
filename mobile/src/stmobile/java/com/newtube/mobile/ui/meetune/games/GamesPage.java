package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.function.Supplier;

/** The music player's picker and the one round currently on screen. */
public final class GamesPage extends LinearLayout {
    private final int surface, ink;
    private int accent;
    private LinearLayout tiles;
    private FrameLayout field;
    private View picker, pause, overlay, scores, card;
    private TextView title, hint, result, resultScore, resume, restart;
    private GameView game;
    private Runnable onSelectionChanged;

    public GamesPage(Context context, AttributeSet attrs) {
        super(context, attrs);
        surface = MaterialColors.getColor(this, R.attr.colorSurface);
        ink = MaterialColors.getColor(this, R.attr.colorOnSurface);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        tiles = findViewById(R.id.game_tiles);
        picker = findViewById(R.id.game_picker);
        // Its own bounds clip the scrolled tiles: the page leaves its children unclipped (the back ripple).
        picker.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        picker.setClipToOutline(true);
        field = findViewById(R.id.game_field);
        title = findViewById(R.id.game_title);
        hint = findViewById(R.id.game_hint);
        scores = findViewById(R.id.game_scores);
        pause = findViewById(R.id.game_pause);
        overlay = findViewById(R.id.game_overlay);
        result = findViewById(R.id.game_result);
        resultScore = findViewById(R.id.game_result_score);
        resume = findViewById(R.id.game_resume);
        restart = findViewById(R.id.game_restart);
        card = findViewById(R.id.game_card);
        pause.setOnClickListener(v -> game.pause());
        resume.setOnClickListener(v -> game.resumeRound());
        restart.setOnClickListener(v -> game.restart());
        Context c = getContext();
        addTile(R.string.meetune_game_2048, R.string.meetune_2048_sub, R.string.meetune_2048_hint,
                R.drawable.ic_game_merge, () -> new MergeView(c));
        addTile(R.string.meetune_game_flappy, R.string.meetune_flappy_sub, R.string.meetune_flappy_hint,
                R.drawable.ic_game_flappy, () -> new FlappyView(c));
        addTile(R.string.meetune_game_snake, R.string.meetune_snake_sub, R.string.meetune_snake_hint,
                R.drawable.ic_game_snake, () -> new SnakeView(c));
        addTile(R.string.meetune_game_runner, R.string.meetune_runner_sub, R.string.meetune_runner_hint,
                R.drawable.ic_game_runner, () -> new RunnerView(c));
        addTile(R.string.meetune_game_blocks, R.string.meetune_blocks_sub, R.string.meetune_blocks_hint,
                R.drawable.ic_game_blocks, () -> new BlocksView(c));
        addTile(R.string.meetune_game_bricks, R.string.meetune_bricks_sub, R.string.meetune_bricks_hint,
                R.drawable.ic_game_bricks, () -> new BricksView(c));
        addTile(R.string.meetune_game_mines, R.string.meetune_mines_sub, R.string.meetune_mines_hint,
                R.drawable.ic_game_mines, () -> new MinesView(c));
        addTile(R.string.meetune_game_pairs, R.string.meetune_pairs_sub, R.string.meetune_pairs_hint,
                R.drawable.ic_game_pairs, () -> new PairsView(c));
    }

    private void addTile(int name, int subtitle, int instructions, int icon, Supplier<GameView> create) {
        LinearLayout row = (LinearLayout) tiles.getChildAt(tiles.getChildCount() - 1);
        if (row == null || row.getChildCount() == 2) {
            row = new LinearLayout(getContext());
            tiles.addView(row, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        }
        View tile = LayoutInflater.from(getContext()).inflate(R.layout.meetune_game_tile, row, false);
        row.addView(tile);
        tile.setBackground(rounded(GameView.tint(ink, 0.10f), 12));
        tile.setClipToOutline(true);
        ((ImageView) tile.findViewById(R.id.game_icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.game_name)).setText(name);
        ((TextView) tile.findViewById(R.id.game_subtitle)).setText(subtitle);
        tile.setOnClickListener(v -> {
            game = create.get();
            game.accent = accent;
            game.setContentDescription(getResources().getString(instructions));
            title.setText(name);
            hint.setText(instructions);
            picker.setVisibility(GONE);
            hint.setVisibility(VISIBLE);
            scores.setVisibility(VISIBLE);
            field.setVisibility(VISIBLE);
            field.addView(game, 0, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            game.start(this::updateRound);
            onSelectionChanged.run();
        });
    }

    public void setOnSelectionChanged(Runnable listener) {
        onSelectionChanged = () -> {
            findViewById(R.id.game_back).setContentDescription(getResources().getString(
                    game != null ? R.string.meetune_games_return_picker : R.string.meetune_games_return_song));
            listener.run();
        };
    }

    public void setOnBack(Runnable listener) {
        findViewById(R.id.game_back).setOnClickListener(v -> listener.run());
    }

    public void setAccent(int color) {
        // Bright album art can yield a near-white accent even when the app surface is light.
        accent = color;
        while (ColorUtils.calculateContrast(accent, surface) < 3 && accent != ink) {
            accent = GameView.blend(accent, ink, 0.2f);
        }
        for (int i = 0; i < tiles.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) tiles.getChildAt(i);
            for (int j = 0; j < row.getChildCount(); j++) {
                ImageView icon = row.getChildAt(j).findViewById(R.id.game_icon);
                icon.setColorFilter(accent);
                icon.setBackground(rounded(GameView.tint(accent, 0.18f), 10));
            }
        }
        if (game != null) {
            game.accent = accent;
            game.invalidate();
            if (overlay.getVisibility() == VISIBLE) paintCard();
        }
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radius * getResources().getDisplayMetrics().density);
        shape.setColor(color);
        return shape;
    }

    private void updateRound() {
        stat((TextView) findViewById(R.id.game_score), R.string.meetune_games_score, game.score);
        stat((TextView) findViewById(R.id.game_best), R.string.meetune_games_best, game.best());
        boolean ended = game.paused || game.over;
        pause.setVisibility(ended ? INVISIBLE : VISIBLE);
        overlay.setVisibility(ended ? VISIBLE : GONE);
        if (ended) {
            result.setText(game.paused ? R.string.meetune_games_paused
                    : game.won ? R.string.meetune_games_won : R.string.meetune_games_over);
            resultScore.setText(getResources().getString(R.string.meetune_games_score) + " " + game.score
                    + "  ·  " + getResources().getString(R.string.meetune_games_best) + " " + game.best());
            resume.setVisibility(game.paused ? VISIBLE : GONE);
            paintCard();
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) { // the board frosts behind the card
            game.setRenderEffect(ended ? android.graphics.RenderEffect.createBlurEffect(
                    blur(), blur(), android.graphics.Shader.TileMode.CLAMP) : null);
        }
    }

    private float blur() {
        return 14 * getResources().getDisplayMetrics().density;
    }

    /** Glass in the cover's accent: a tinted scrim, a frosted card, the main action filled with the accent. */
    private void paintCard() {
        overlay.setBackgroundColor(ColorUtils.setAlphaComponent(GameView.blend(surface, accent, 0.3f), 110));
        GradientDrawable glass = rounded(ColorUtils.setAlphaComponent(GameView.blend(surface, accent, 0.22f), 175), 24);
        glass.setStroke(Math.round(getResources().getDisplayMetrics().density), GameView.tint(ink, 0.18f));
        card.setBackground(glass);
        resultScore.setTextColor(GameView.tint(ink, 0.7f));
        TextView main = resume.getVisibility() == VISIBLE ? resume : restart;
        TextView other = main == resume ? restart : resume;
        int onAccent = ColorUtils.calculateContrast(0xFFFFFFFF, accent) >= 3 ? 0xFFFFFFFF : 0xFF000000;
        main.setBackground(pill(accent, onAccent));
        main.setTextColor(onAccent);
        GradientDrawable plain = rounded(GameView.tint(ink, 0.12f), 21);
        plain.setStroke(Math.round(getResources().getDisplayMetrics().density), GameView.tint(ink, 0.2f));
        other.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(GameView.tint(ink, 0.2f)), plain, null));
        other.setTextColor(ink);
    }

    private android.graphics.drawable.Drawable pill(int color, int ripple) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(GameView.tint(ripple, 0.25f)), rounded(color, 21), null);
    }

    /** A small dimmed label over its number. */
    private void stat(TextView view, int label, int value) {
        String name = getResources().getString(label);
        android.text.SpannableString text = new android.text.SpannableString(name + "\n" + value);
        text.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 0, name.length(), 0);
        text.setSpan(new android.text.style.ForegroundColorSpan(ColorUtils.setAlphaComponent(ink, 179)), 0, name.length(), 0);
        view.setText(text);
    }

    public boolean hasGame() { return game != null; }

    public void pauseGame() {
        if (game != null) game.pause();
    }

    public void closeGame() {
        if (game == null) return;
        pauseGame();
        field.removeView(game);
        game = null;
        field.setVisibility(GONE);
        hint.setVisibility(GONE);
        scores.setVisibility(GONE);
        pause.setVisibility(GONE);
        title.setText(R.string.meetune_games);
        picker.setVisibility(VISIBLE);
        onSelectionChanged.run();
    }
}
