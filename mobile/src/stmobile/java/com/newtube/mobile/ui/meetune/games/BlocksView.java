/*
 * Adapted from Morphe Manager's BlocksGame.kt, Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Arrays;

final class BlocksView extends GameView {
    private static final int COLS = 11, ROWS = 18, CELL = 20, HEADER = 22;
    private static final int[] BOX = {4, 2, 3, 3, 3, 3, 3};
    // Cell indices in a 4-column grid. Turn each shape inside its own bounding square.
    private static final int[][] SPAWN = {
            {4, 5, 6, 7}, {0, 1, 4, 5}, {1, 4, 5, 6}, {1, 2, 4, 5},
            {0, 1, 5, 6}, {0, 4, 5, 6}, {2, 4, 5, 6}
    };
    private static final int[] KICKS = {0, -1, 1, -2, 2};
    private static final int[] LINE_POINTS = {0, 100, 300, 500, 800};
    private static final int NO_AXIS = 0, HORIZONTAL = 1, VERTICAL = 2;

    private final int[][] board = new int[ROWS][COLS];
    private final boolean[] full = new boolean[ROWS];
    private final String startLabel;
    private String stats;
    private int type, turn, pieceX, pieceY, lines, clearing;
    private float elapsed;
    private boolean started, holding;
    private int axis;
    private float downX, downY, lastX, lastY, travel;

    BlocksView(Context context) {
        super(context, "blocks", COLS * CELL, HEADER + ROWS * CELL);
        startLabel = context.getString(R.string.meetune_games_tap_start);
    }

    @Override
    void resetGame() {
        for (int[] row : board) Arrays.fill(row, 0);
        Arrays.fill(full, false);
        clearing = lines = 0;
        updateStats();
        started = false;
        spawn();
    }

    private int level() {
        return 1 + lines / 10;
    }

    private void updateStats() {
        stats = getContext().getString(R.string.meetune_games_blocks_stats, level(), lines);
    }

    private void spawn() {
        type = random.nextInt(SPAWN.length);
        pieceX = (COLS - BOX[type]) / 2;
        pieceY = turn = 0;
        elapsed = 0;
        cancelGesture();
        if (blocked(pieceX, pieceY, turn)) finishGame(false);
    }

    private int rotated(int cell, int turns) {
        for (int i = 0; i < turns; i++) {
            int x = cell % 4, y = cell / 4;
            cell = BOX[type] - 1 - y + x * 4;
        }
        return cell;
    }

    private boolean blocked(int left, int top, int turns) {
        for (int cell : SPAWN[type]) {
            int p = rotated(cell, turns);
            int x = left + p % 4, y = top + p / 4;
            if (x < 0 || x >= COLS || y >= ROWS || board[y][x] != 0) return true;
        }
        return false;
    }

    private void rotatePiece() {
        int next = (turn + 1) % 4;
        for (int kick : KICKS) {
            if (!blocked(pieceX + kick, pieceY, next)) {
                turn = next;
                pieceX += kick;
                return;
            }
        }
    }

    private void lockPiece() {
        for (int cell : SPAWN[type]) {
            int p = rotated(cell, turn);
            board[pieceY + p / 4][pieceX + p % 4] = type + 1;
        }
        for (int row = 0; row < ROWS; row++) {
            int filled = 0;
            for (int value : board[row]) if (value != 0) filled++;
            full[row] = filled == COLS;
            if (full[row]) clearing++;
        }
        cancelGesture();
        tick();
        if (clearing == 0) spawn();
        else elapsed = 0;
    }

    private void clearRows() {
        int dest = ROWS - 1;
        for (int row = ROWS - 1; row >= 0; row--) {
            if (!full[row]) {
                if (dest != row) System.arraycopy(board[row], 0, board[dest], 0, COLS);
                dest--;
            }
        }
        while (dest >= 0) Arrays.fill(board[dest--], 0);
        lines += clearing;
        updateStats();
        setScore(score + LINE_POINTS[clearing] * level());
        clearing = 0;
        Arrays.fill(full, false);
        spawn();
    }

    @Override
    boolean needsFrames() {
        return started;
    }

    @Override
    void advance(float seconds) {
        elapsed += seconds;
        if (clearing > 0) {
            if (elapsed >= 0.180f) clearRows();
            return;
        }
        float interval = Math.max(0.110f, 0.550f - (level() - 1) * 0.045f);
        if (elapsed >= interval) {
            elapsed -= interval;
            if (blocked(pieceX, pieceY + 1, turn)) lockPiece();
            else pieceY++;
        }
    }

    @Override
    void cancelGesture() {
        holding = false;
    }

    @Override
    boolean onGameTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            holding = clearing == 0;
            axis = NO_AXIS;
            travel = 0;
            downX = lastX = x;
            downY = lastY = y;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelGesture();
        } else if (holding && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            float dx = x - downX, dy = y - downY;
            if (axis == NO_AXIS && dx * dx + dy * dy >= CELL * CELL * 0.36f) {
                axis = Math.abs(dy) > Math.abs(dx) ? VERTICAL : HORIZONTAL;
                started = true;
                lastX = downX;
                lastY = downY;
            }
            if (axis == HORIZONTAL) {
                travel += x - lastX;
                while (Math.abs(travel) >= CELL) {
                    int step = travel > 0 ? 1 : -1;
                    if (!blocked(pieceX + step, pieceY, turn)) pieceX += step;
                    travel -= step * CELL;
                }
            } else if (axis == VERTICAL) {
                travel = Math.max(0f, travel + y - lastY);
                int moved = 0;
                while (travel >= CELL) {
                    if (!blocked(pieceX, pieceY + 1, turn)) {
                        pieceY++;
                        moved++;
                    }
                    travel -= CELL;
                }
                if (moved > 0) setScore(score + moved);
            }
            lastX = x;
            lastY = y;
            if (action == MotionEvent.ACTION_UP) {
                if (axis == NO_AXIS) {
                    started = true;
                    rotatePiece();
                } else if (axis == VERTICAL && dy > CELL * 4) {
                    int from = pieceY;
                    while (!blocked(pieceX, pieceY + 1, turn)) pieceY++;
                    setScore(score + (pieceY - from) * 2);
                    lockPiece();
                }
                cancelGesture();
            }
        }
        return true;
    }

    @Override
    void drawGame(Canvas canvas) {
        paint.setColor(ink);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(13);
        canvas.drawText(stats, 0, 15, paint);
        canvas.save();
        canvas.translate(0, HEADER);
        paint.setColor(tint(ink, 0.09f));
        paint.setStrokeWidth(0.5f);
        for (int col = 0; col <= COLS; col++) {
            canvas.drawLine(col * CELL, 0, col * CELL, ROWS * CELL, paint);
        }
        for (int row = 0; row <= ROWS; row++) {
            canvas.drawLine(0, row * CELL, COLS * CELL, row * CELL, paint);
        }
        for (int row = 0; row < ROWS; row++) {
            if (full[row]) {
                paint.setColor(ink);
                canvas.drawRect(0, row * CELL, COLS * CELL, (row + 1) * CELL, paint);
                continue;
            }
            for (int col = 0; col < COLS; col++) {
                if (board[row][col] > 0) drawBlock(canvas, col, row, board[row][col] - 1);
            }
        }
        if (clearing == 0) {
            for (int cell : SPAWN[type]) {
                int p = rotated(cell, turn);
                drawBlock(canvas, pieceX + p % 4, pieceY + p / 4, type);
            }
        }
        if (!started) {
            paint.setColor(ink);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(15);
            canvas.drawText(startLabel, COLS * CELL / 2f, ROWS * CELL / 2f, paint);
        }
        canvas.restore();
    }

    private void drawBlock(Canvas canvas, int col, int row, int shape) {
        float x = col * CELL, y = row * CELL;
        int color = blend(accent, ink, 0.18f + shape * 0.10f);
        paint.setColor(color);
        canvas.drawRoundRect(x + 1.6f, y + 1.6f, x + CELL - 1.6f, y + CELL - 1.6f, 5, 5, paint);
        paint.setColor(blend(color, surface, 0.25f));
        canvas.drawRoundRect(x + 6, y + 6, x + CELL - 6, y + CELL - 6, 2, 2, paint);
    }
}
