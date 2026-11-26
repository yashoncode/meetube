/*
 * Adapted from Morphe Manager's BricksGame.kt, Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Arrays;

final class BricksView extends GameView {
    private static final int SIZE = 300, ROWS = 5, COLS = 7, LIVES = 3;
    private static final float GRID_LEFT = SIZE * 0.03f, GRID_TOP = SIZE * 0.10f;
    private static final float BRICK_W = (SIZE - 2 * GRID_LEFT) / COLS, BRICK_H = SIZE * 0.05f;
    private static final float HALF_GAP = SIZE * 0.004f;
    private static final float PADDLE_HALF = SIZE * 0.11f, PADDLE_H = SIZE * 0.022f;
    private static final float PADDLE_TOP = SIZE * 0.93f, RADIUS = SIZE * 0.016f;
    private final boolean[] bricks = new boolean[ROWS * COLS];
    private final String launchLabel;
    private int lives, remaining;
    private float speed, paddle, ballX, ballY, vx, vy;
    private boolean launched, holding, dragging, canLaunch;
    private float downX, downY, lastX;

    BricksView(Context context) {
        super(context, "bricks", SIZE, SIZE);
        launchLabel = context.getString(R.string.meetune_games_tap_launch);
    }

    @Override
    void resetGame() {
        lives = LIVES;
        speed = SIZE * 0.62f;
        fillWave();
        resetBall();
    }

    private void fillWave() {
        Arrays.fill(bricks, true);
        remaining = bricks.length;
    }

    private void resetBall() {
        launched = false;
        paddle = ballX = SIZE * 0.5f;
        ballY = PADDLE_TOP - RADIUS;
        vx = vy = 0;
    }

    @Override
    boolean needsFrames() {
        return launched;
    }

    @Override
    void advance(float seconds) {
        // Break a late frame into short steps so the ball cannot cross the paddle in one update.
        while (launched && seconds > 0f) {
            float dt = Math.min(seconds, 1f / 120f);
            seconds -= dt;
            float prevY = ballY;
            ballX += vx * dt;
            ballY += vy * dt;
            if (ballX < RADIUS) {
                ballX = RADIUS;
                vx = Math.abs(vx);
            } else if (ballX > SIZE - RADIUS) {
                ballX = SIZE - RADIUS;
                vx = -Math.abs(vx);
            }
            if (ballY < RADIUS) {
                ballY = RADIUS;
                vy = Math.abs(vy);
            }
            hitBrick(prevY);
            if (!launched) return;
            bouncePaddle();
            if (ballY - RADIUS > SIZE) {
                lives--;
                if (lives == 0) {
                    launched = false;
                    finishGame(false);
                } else {
                    resetBall();
                }
            }
        }
    }

    private void hitBrick(float prevY) {
        if (ballX < GRID_LEFT || ballY < GRID_TOP) return;
        int col = (int) ((ballX - GRID_LEFT) / BRICK_W);
        int row = (int) ((ballY - GRID_TOP) / BRICK_H);
        if (col >= COLS || row >= ROWS) return;
        int cell = row * COLS + col;
        if (!bricks[cell]) return;
        bricks[cell] = false;
        remaining--;
        float top = GRID_TOP + row * BRICK_H;
        if (prevY > top && prevY < top + BRICK_H) vx = -vx;
        else vy = -vy;
        tick();
        if (remaining == 0) {
            setScore(score + 10 + 50);
            speed = Math.min(speed + SIZE * 0.05f, SIZE * 1.05f);
            fillWave();
            resetBall();
        } else {
            setScore(score + 10);
        }
    }

    private void bouncePaddle() {
        if (vy <= 0 || ballY + RADIUS < PADDLE_TOP || ballY - RADIUS > PADDLE_TOP + PADDLE_H) return;
        float offset = (ballX - paddle) / PADDLE_HALF;
        if (Math.abs(offset) > 1f + RADIUS / PADDLE_HALF) return;
        float angle = Math.max(-1f, Math.min(1f, offset)) * 1.05f;
        vx = speed * (float) Math.sin(angle);
        vy = -speed * (float) Math.cos(angle);
        ballY = PADDLE_TOP - RADIUS;
    }

    @Override
    void cancelGesture() {
        holding = false;
    }

    @Override
    boolean onGameTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            holding = true;
            dragging = false;
            canLaunch = !launched;
            downX = lastX = x;
            downY = y;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelGesture();
        } else if (holding && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            float dx = x - downX, dy = y - downY;
            if (!dragging && dx * dx + dy * dy >= 16f) dragging = true;
            if (dragging) {
                paddle = Math.max(PADDLE_HALF, Math.min(SIZE - PADDLE_HALF, paddle + x - lastX));
                if (!launched) ballX = paddle;
                lastX = x;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (!dragging && canLaunch && !launched) {
                    launched = true;
                    vx = speed * 0.45f;
                    vy = -speed * 0.89f;
                }
                cancelGesture();
            }
        }
        return true;
    }

    @Override
    void drawGame(Canvas canvas) {
        for (int row = 0; row < ROWS; row++) {
            paint.setColor(blend(accent, ink, 0.18f + row * 0.13f));
            float top = GRID_TOP + row * BRICK_H;
            for (int col = 0; col < COLS; col++) {
                if (!bricks[row * COLS + col]) continue;
                float left = GRID_LEFT + col * BRICK_W;
                canvas.drawRoundRect(left + HALF_GAP, top + HALF_GAP, left + BRICK_W - HALF_GAP,
                        top + BRICK_H - HALF_GAP, 2.4f, 2.4f, paint);
            }
        }
        for (int i = 0; i < LIVES; i++) {
            paint.setColor(i < lives ? ink : tint(ink, 0.20f));
            canvas.drawCircle(GRID_LEFT + RADIUS + i * RADIUS * 3, GRID_TOP * 0.45f, RADIUS * 0.7f, paint);
        }
        paint.setColor(blend(accent, ink, 0.20f));
        canvas.drawRoundRect(paddle - PADDLE_HALF, PADDLE_TOP, paddle + PADDLE_HALF,
                PADDLE_TOP + PADDLE_H, PADDLE_H / 2, PADDLE_H / 2, paint);
        paint.setColor(ink);
        canvas.drawCircle(ballX, ballY, RADIUS, paint);
        if (!launched && lives > 0) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(16f);
            canvas.drawText(launchLabel, SIZE / 2f, SIZE * 0.58f, paint);
        }
    }
}
