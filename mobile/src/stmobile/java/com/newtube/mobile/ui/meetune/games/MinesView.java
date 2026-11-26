/* Adapted from Morphe Manager's MinerGame.kt, Copyright 2026 Morphe. */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Arrays;

final class MinesView extends GameView {
    private static final int SIDE = 9, CELLS = SIDE * SIDE;
    private static final int TILE = 40, TOP = 28;
    private final boolean[] mines = new boolean[CELLS];
    private final boolean[] flags = new boolean[CELLS];
    private final boolean[] open = new boolean[CELLS];
    private final int[] counts = new int[CELLS];
    private final Path pennant = new Path();
    private int mineCount, flagCount, opened;
    private int pressed = -1;
    private long flagTime;
    private boolean seeded;
    private String flagLabel;

    MinesView(Context context) {
        super(context, "mines", SIDE * TILE, TOP + SIDE * TILE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        pennant.moveTo(-6, -10);
        pennant.lineTo(9, -6);
        pennant.lineTo(-6, 0);
        pennant.close();
    }

    @Override void resetGame() {
        mineCount = 10;
        clearField();
    }

    private void clearField() {
        Arrays.fill(mines, false);
        Arrays.fill(flags, false);
        Arrays.fill(open, false);
        Arrays.fill(counts, 0);
        seeded = false;
        pressed = -1;
        opened = 0;
        flagCount = 0;
        updateFlagLabel();
    }

    private void updateFlagLabel() {
        flagLabel = getResources().getString(R.string.meetune_games_flags, flagCount, mineCount);
    }

    private static boolean adjacent(int a, int b) {
        return Math.abs(a / SIDE - b / SIDE) <= 1 && Math.abs(a % SIDE - b % SIDE) <= 1;
    }

    private void seed(int firstCell) {
        int[] candidates = new int[CELLS];
        int remaining = 0;
        for (int i = 0; i < CELLS; i++) {
            if (!adjacent(i, firstCell)) candidates[remaining++] = i;
        }
        for (int i = 0; i < mineCount; i++) {
            int choice = random.nextInt(remaining);
            int mine = candidates[choice];
            candidates[choice] = candidates[--remaining];
            mines[mine] = true;
            for (int j = 0; j < CELLS; j++) {
                if (adjacent(j, mine)) counts[j]++;
            }
        }
        seeded = true;
    }

    private void reveal(int cell) {
        if (flags[cell] || open[cell]) return;
        if (!seeded) seed(cell);
        if (mines[cell]) {
            for (int i = 0; i < CELLS; i++) open[i] |= mines[i];
            finishGame(false);
            return;
        }
        int[] queue = new int[CELLS];
        queue[0] = cell;
        open[cell] = true;
        int tail = 1;
        for (int head = 0; head < tail; head++) {
            int current = queue[head];
            if (counts[current] != 0) continue;
            for (int row = Math.max(0, current / SIDE - 1); row <= Math.min(SIDE - 1, current / SIDE + 1); row++) {
                for (int col = Math.max(0, current % SIDE - 1); col <= Math.min(SIDE - 1, current % SIDE + 1); col++) {
                    int neighbor = row * SIDE + col;
                    if (!open[neighbor] && !flags[neighbor]) {
                        open[neighbor] = true;
                        queue[tail++] = neighbor;
                    }
                }
            }
        }
        opened += tail;
        int points = tail * 5;
        if (opened == CELLS - mineCount) {
            points += 50;
            mineCount = Math.min(18, mineCount + 1);
            clearField();
            tick();
        }
        setScore(score + points);
    }

    private void toggleFlag() {
        flags[pressed] = !flags[pressed];
        flagCount += flags[pressed] ? 1 : -1;
        pressed = -1;
        updateFlagLabel();
        tick();
    }

    private static int cellAt(float x, float y) {
        if (x < 0 || x >= SIDE * TILE || y < TOP || y >= TOP + SIDE * TILE) return -1;
        return (int) ((y - TOP) / TILE) * SIDE + (int) (x / TILE);
    }

    @Override boolean onGameTouch(int action, float x, float y) {
        int cell = cellAt(x, y);
        if (action == MotionEvent.ACTION_DOWN) {
            pressed = cell >= 0 && !open[cell] ? cell : -1;
            flagTime = SystemClock.uptimeMillis() + ViewConfiguration.getLongPressTimeout();
        } else if (action == MotionEvent.ACTION_CANCEL || cell != pressed) {
            cancelGesture();
        } else if (action == MotionEvent.ACTION_UP && pressed >= 0) {
            if (SystemClock.uptimeMillis() >= flagTime) toggleFlag();
            else reveal(pressed);
            pressed = -1;
        }
        return true;
    }

    @Override void cancelGesture() { pressed = -1; }

    @Override boolean needsFrames() { return pressed >= 0; }

    @Override void advance(float seconds) {
        if (pressed >= 0 && SystemClock.uptimeMillis() >= flagTime) toggleFlag();
    }

    @Override void drawGame(Canvas canvas) {
        paint.setTextSize(16);
        paint.setColor(ink);
        if (flagLabel != null) canvas.drawText(flagLabel, SIDE * TILE / 2f, 19, paint);
        int hidden = tint(blend(accent, ink, 0.3f), 0.32f);
        int revealed = tint(ink, 0.065f);
        for (int i = 0; i < CELLS; i++) {
            float x = (i % SIDE) * TILE, y = TOP + (i / SIDE) * TILE;
            paint.setColor(open[i] ? revealed : hidden);
            if (i == pressed) paint.setColor(blend(hidden, ink, 0.14f));
            canvas.drawRoundRect(x + 1, y + 1, x + TILE - 1, y + TILE - 1, 5, 5, paint);
            float cx = x + TILE / 2f, cy = y + TILE / 2f;
            if (open[i] && mines[i]) {
                paint.setColor(ink);
                canvas.drawCircle(cx, cy, 7, paint);
                paint.setStrokeWidth(2);
                canvas.drawLine(cx - 10, cy, cx + 10, cy, paint);
                canvas.drawLine(cx, cy - 10, cx, cy + 10, paint);
                canvas.drawLine(cx - 7, cy - 7, cx + 7, cy + 7, paint);
                canvas.drawLine(cx - 7, cy + 7, cx + 7, cy - 7, paint);
            } else if (open[i] && counts[i] > 0) {
                paint.setTextSize(21);
                paint.setColor(blend(accent, ink, Math.min(1, 0.5f + counts[i] * 0.08f)));
                canvas.drawText(String.valueOf(counts[i]), cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
            } else if (flags[i]) {
                paint.setColor(ink);
                canvas.save();
                canvas.translate(cx, cy);
                canvas.drawPath(pennant, paint);
                paint.setStrokeWidth(2.5f);
                canvas.drawLine(-6, -10, -6, 10, paint);
                canvas.restore();
            }
        }
    }
}
