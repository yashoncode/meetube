/* Adapted from Morphe Manager's Game2048.kt, Copyright 2026 Morphe. */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;

import androidx.core.graphics.ColorUtils;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Arrays;

final class MergeView extends GameView {
    private static final int SIDE = 4;
    private static final float GAP = 8.5f;
    private static final float TILE = (360 - 5 * GAP) / SIDE;
    private static final float FADE = 0.12f;
    private final int[] board = new int[SIDE * SIDE];
    private final int[] previous = new int[board.length];
    private boolean reachedTarget, dragging;
    private float fadeLeft, downX, downY;

    MergeView(Context context) {
        super(context, "2048", 360, 384);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override void resetGame() {
        Arrays.fill(board, 0);
        spawnTile();
        spawnTile();
        reachedTarget = false;
        fadeLeft = 0;
        dragging = false;
    }

    private void spawnTile() {
        int available = 0;
        int choice = -1;
        for (int i = 0; i < board.length; i++) {
            if (board[i] == 0 && random.nextInt(++available) == 0) choice = i;
        }
        if (choice >= 0) board[choice] = random.nextInt(10) == 0 ? 4 : 2;
    }

    private static int index(int line, int offset, boolean vertical, boolean reverse) {
        int position = reverse ? SIDE - 1 - offset : offset;
        return vertical ? position * SIDE + line : line * SIDE + position;
    }

    private void slide(boolean vertical, boolean reverse) {
        int[] next = new int[board.length];
        int[] packed = new int[SIDE];
        int gained = 0;
        for (int line = 0; line < SIDE; line++) {
            int count = 0;
            for (int pos = 0; pos < SIDE; pos++) {
                int value = board[index(line, pos, vertical, reverse)];
                if (value > 0) packed[count++] = value;
            }
            int destination = 0;
            for (int pos = 0; pos < count; pos++) {
                int value = packed[pos];
                if (pos + 1 < count && packed[pos + 1] == value) {
                    pos++;
                    value *= 2;
                    gained += value;
                    if (value == 2048) reachedTarget = true;
                }
                next[index(line, destination++, vertical, reverse)] = value;
            }
        }
        if (Arrays.equals(next, board)) return;
        System.arraycopy(board, 0, previous, 0, board.length);
        System.arraycopy(next, 0, board, 0, board.length);
        spawnTile();
        fadeLeft = FADE;
        if (gained > 0) {
            setScore(score + gained);
            tick();
        }
        for (int i = 0; i < board.length; i++) {
            if (board[i] == 0
                    || (i % SIDE > 0 && board[i] == board[i - 1])
                    || (i >= SIDE && board[i] == board[i - SIDE])) return;
        }
        finishGame(reachedTarget);
    }

    @Override boolean onGameTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = x >= 0 && x < 360 && y >= 0 && y < 360;
            downX = x;
            downY = y;
        } else if (action == MotionEvent.ACTION_UP && dragging) {
            dragging = false;
            float dx = x - downX, dy = y - downY;
            float scale = Math.min(getWidth() / 360f, getHeight() / 384f);
            if (Math.max(Math.abs(dx), Math.abs(dy)) * scale >= 14 * getResources().getDisplayMetrics().density) {
                boolean vertical = Math.abs(dy) > Math.abs(dx);
                slide(vertical, (vertical ? dy : dx) > 0);
            }
        }
        return true;
    }

    @Override void cancelGesture() { dragging = false; }

    @Override boolean needsFrames() { return fadeLeft > 0; }

    @Override void advance(float seconds) { fadeLeft = Math.max(0, fadeLeft - seconds); }

    private int tileColor(int value) {
        if (value == 0) return tint(ink, 0.12f);
        int power = Integer.numberOfTrailingZeros(value);
        return tint(accent, Math.min(1, 0.17f + power * 0.075f));
    }

    @Override void drawGame(Canvas canvas) {
        paint.setColor(tint(ink, 0.06f));
        canvas.drawRoundRect(0, 0, 360, 360, 12, 12, paint);
        for (int i = 0; i < board.length; i++) {
            float left = GAP + (i % SIDE) * (TILE + GAP);
            float top = GAP + (i / SIDE) * (TILE + GAP);
            int fill = tileColor(board[i]);
            if (fadeLeft > 0) fill = blend(fill, tileColor(previous[i]), fadeLeft / FADE);
            paint.setColor(fill);
            canvas.drawRoundRect(left, top, left + TILE, top + TILE, 7, 7, paint);
            if (board[i] > 0) {
                // As seen over the dark page; blendARGB rounds the alpha to 254, which contrast refuses.
                int background = ColorUtils.setAlphaComponent(blend(surface,
                        ColorUtils.setAlphaComponent(fill, 255), android.graphics.Color.alpha(fill) / 255f), 255);
                paint.setColor(ColorUtils.calculateContrast(ink, background) >=
                        ColorUtils.calculateContrast(surface, background) ? ink : surface);
                paint.setTextSize(board[i] < 100 ? 29 : board[i] < 1000 ? 25 : 20);
                float baseline = top + (TILE - paint.ascent() - paint.descent()) / 2;
                canvas.drawText(String.valueOf(board[i]), left + TILE / 2, baseline, paint);
            }
        }
        if (reachedTarget) {
            paint.setColor(ink);
            paint.setTextSize(15);
            canvas.drawText(getResources().getString(R.string.meetune_games_2048_win), 180, 379, paint);
        }
    }
}
