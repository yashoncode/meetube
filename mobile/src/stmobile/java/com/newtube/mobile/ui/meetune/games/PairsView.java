/* Adapted from Morphe Manager's PairsGame.kt, Copyright 2026 Morphe. */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import androidx.core.graphics.ColorUtils;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Arrays;

final class PairsView extends GameView {
    private static final int SIDE = 4, TILE = 90, TOP = 24;
    private static final int STRIKES = 8;
    private static final int[] SYMBOL_IDS = {
            R.drawable.ic_meetune_star_filled, R.drawable.ic_meetune_heart_filled,
            R.drawable.ic_badge_music, R.drawable.ic_player_headphones,
            R.drawable.ic_mobile_mic, R.drawable.ic_menu_bell,
            R.drawable.ic_player_shuffle, R.drawable.ic_meetune_download
    };
    private final int[] cards = new int[SIDE * SIDE];
    private final boolean[] matched = new boolean[cards.length];
    private final float[] flips = new float[cards.length];
    private final Drawable[] symbols = new Drawable[SYMBOL_IDS.length];
    private int strikes, pairsFound;
    private int first = -1, second = -1, pressed = -1;
    private int symbolAccent, symbolInk;
    private float peekLeft;

    PairsView(Context context) {
        super(context, "pairs", TILE * SIDE, TOP + TILE * SIDE);
        for (int i = 0; i < symbols.length; i++) {
            symbols[i] = context.getDrawable(SYMBOL_IDS[i]).mutate();
            symbols[i].setBounds(-21, -21, 21, 21);
        }
    }

    @Override void resetGame() {
        strikes = STRIKES;
        deal();
    }

    private void deal() {
        first = second = pressed = -1;
        pairsFound = 0;
        peekLeft = 0;
        Arrays.fill(flips, 0f);
        Arrays.fill(matched, false);
        for (int i = 0; i < cards.length; i++) cards[i] = i / 2;
        for (int i = cards.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int old = cards[j];
            cards[j] = cards[i];
            cards[i] = old;
        }
    }

    private void pick(int cell) {
        if (matched[cell] || first == cell || second >= 0) return;
        if (first < 0) first = cell;
        else {
            second = cell;
            peekLeft = 0.8f;
        }
    }

    private void resolve() {
        int a = first, b = second;
        first = second = -1;
        if (cards[a] == cards[b]) {
            matched[a] = matched[b] = true;
            strikes = Math.min(strikes + 1, STRIKES);
            pairsFound++;
            int points = 20;
            if (pairsFound == symbols.length) {
                points += 50;
                deal();
            }
            setScore(score + points);
            tick();
        } else if (--strikes == 0) {
            finishGame(false);
        }
    }

    private static int cellAt(float x, float y) {
        if (x < 0 || x >= TILE * SIDE || y < TOP || y >= TOP + TILE * SIDE) return -1;
        return (int) ((y - TOP) / TILE) * SIDE + (int) (x / TILE);
    }

    @Override boolean onGameTouch(int action, float x, float y) {
        int cell = cellAt(x, y);
        if (action == MotionEvent.ACTION_DOWN) {
            pressed = second < 0 ? cell : -1;
        } else if (action == MotionEvent.ACTION_CANCEL || cell != pressed) {
            cancelGesture();
        } else if (action == MotionEvent.ACTION_UP && pressed >= 0) {
            pick(pressed);
            pressed = -1;
        }
        return true;
    }

    @Override void cancelGesture() { pressed = -1; }

    private boolean faceUp(int i) { return matched[i] || first == i || second == i; }

    @Override boolean needsFrames() {
        if (second >= 0) return true;
        for (int i = 0; i < flips.length; i++) {
            if (flips[i] != (faceUp(i) ? 1f : 0f)) return true;
        }
        return false;
    }

    @Override void advance(float seconds) {
        float step = seconds / 0.26f;
        for (int i = 0; i < flips.length; i++) {
            flips[i] = faceUp(i) ? Math.min(1f, flips[i] + step) : Math.max(0f, flips[i] - step);
        }
        if (second >= 0) {
            peekLeft -= seconds;
            if (peekLeft <= 0) resolve();
        }
    }

    private void tintSymbols() {
        if (accent == symbolAccent && ink == symbolInk) return;
        symbolAccent = accent;
        symbolInk = ink;
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(accent, hsl);
        float hue = hsl[0];
        for (int i = 0; i < symbols.length; i++) {
            hsl[0] = (hue + i * 45) % 360;
            symbols[i].setTint(blend(ColorUtils.HSLToColor(hsl), ink, 0.55f));
        }
    }

    @Override void drawGame(Canvas canvas) {
        tintSymbols();
        for (int i = 0; i < STRIKES; i++) {
            paint.setColor(i < strikes ? blend(accent, ink, 0.3f) : tint(ink, 0.16f));
            canvas.drawCircle(180 + (i - 3.5f) * 16, 11, 4, paint);
        }
        int back = tint(blend(accent, ink, 0.2f), 0.65f);
        int face = tint(ink, 0.09f);
        for (int i = 0; i < cards.length; i++) {
            float cx = (i % SIDE + 0.5f) * TILE, cy = TOP + (i / SIDE + 0.5f) * TILE;
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale((float) Math.abs(Math.cos(Math.PI * flips[i])), 1f);
            boolean front = flips[i] > 0.5f;
            paint.setColor(front ? face : back);
            canvas.drawRoundRect(-41, -41, 41, 41, 13, 13, paint);
            if (front) symbols[cards[i]].draw(canvas);
            canvas.restore();
        }
    }
}
