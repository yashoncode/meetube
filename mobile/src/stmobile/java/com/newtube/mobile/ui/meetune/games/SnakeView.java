/* Adapted from Morphe Manager's SnakeGame (Copyright 2026 Morphe). */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

import java.util.Arrays;

final class SnakeView extends GameView {
    private static final int SIZE = 20;
    private final int[] body = new int[SIZE * SIZE];
    private final boolean[] occupied = new boolean[SIZE * SIZE];
    private int length, food;
    private int dx, dy, pendingX, pendingY;
    private float swipeX, swipeY, elapsed;
    private boolean started;

    SnakeView(Context context) {
        super(context, "snake", SIZE, SIZE);
    }

    @Override
    void resetGame() {
        Arrays.fill(occupied, false);
        length = 3;
        for (int i = 0; i < length; i++) {
            body[i] = 10 * SIZE + 10 - i;
            occupied[body[i]] = true;
        }
        food = 10 * SIZE + 15;
        dx = pendingX = 1;
        dy = pendingY = 0;
        elapsed = 0f;
        started = false;
    }

    @Override
    boolean onGameTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            swipeX = x;
            swipeY = y;
        } else if (action == MotionEvent.ACTION_UP) {
            float dragX = x - swipeX;
            float dragY = y - swipeY;
            if (Math.max(Math.abs(dragX), Math.abs(dragY)) < 0.7f) return true;
            boolean horizontal = Math.abs(dragX) > Math.abs(dragY);
            int nextX = horizontal ? (int) Math.signum(dragX) : 0;
            int nextY = horizontal ? 0 : (int) Math.signum(dragY);
            // Compare to the last actual step even when several swipes arrive between steps.
            if (nextX != -dx || nextY != -dy) {
                pendingX = nextX;
                pendingY = nextY;
            }
            started = true;
        }
        return true;
    }

    @Override
    boolean needsFrames() {
        return started;
    }

    @Override
    void advance(float seconds) {
        elapsed += seconds;
        float interval = Math.max(0.08f, 0.18f - score * 0.005f);
        if (elapsed < interval) return;
        elapsed -= interval;
        dx = pendingX;
        dy = pendingY;
        int x = body[0] % SIZE + dx;
        int y = body[0] / SIZE + dy;
        int next = y * SIZE + x;
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || occupied[next]) {
            finishGame(false);
            return;
        }

        boolean eating = next == food;
        if (eating) {
            length++;
        } else {
            occupied[body[length - 1]] = false;
        }
        System.arraycopy(body, 0, body, 1, length - 1);
        body[0] = next;
        occupied[next] = true;
        if (eating) {
            setScore(score + 1);
            tick();
            if (length == body.length) {
                food = -1;
                finishGame(true);
                return;
            }
            int choice = random.nextInt(body.length - length);
            for (int i = 0; i < body.length; i++) {
                if (!occupied[i] && choice-- == 0) {
                    food = i;
                    break;
                }
            }
        }
    }

    @Override
    void drawGame(Canvas canvas) {
        canvas.drawColor(tint(ink, 0.05f)); // the field, see-through over the cover
        paint.setColor(tint(ink, 0.12f));
        paint.setStrokeWidth(0.035f);
        for (int i = 0; i <= SIZE; i++) {
            canvas.drawLine(i, 0, i, SIZE, paint);
            canvas.drawLine(0, i, SIZE, i, paint);
        }
        if (food >= 0) {
            paint.setColor(ink);
            canvas.drawCircle(food % SIZE + 0.5f, food / SIZE + 0.5f, 0.40f, paint);
        }
        int bodyColor = blend(accent, ink, 0.35f);
        for (int i = 0; i < length; i++) {
            int x = body[i] % SIZE;
            int y = body[i] / SIZE;
            paint.setColor(i == 0 ? ink : bodyColor);
            canvas.drawRoundRect(x + 0.08f, y + 0.08f, x + 0.92f, y + 0.92f, 0.28f, 0.28f, paint);
        }
        float noseX = body[0] % SIZE + 0.5f + dx * 0.18f;
        float noseY = body[0] / SIZE + 0.5f + dy * 0.18f;
        paint.setColor(surface);
        canvas.drawCircle(noseX - dy * 0.18f, noseY + dx * 0.18f, 0.09f, paint);
        canvas.drawCircle(noseX + dy * 0.18f, noseY - dx * 0.18f, 0.09f, paint);
    }
}
