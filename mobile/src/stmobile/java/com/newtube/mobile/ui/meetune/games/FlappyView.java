/* Adapted from Morphe Manager's FlappyGame (Copyright 2026 Morphe). */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.MotionEvent;

import java.util.ArrayList;

final class FlappyView extends GameView {
    private static final float BIRD_X = 22f;
    private static final float RADIUS = 5f;
    private static final float PIPE_WIDTH = 15f;
    private static final float HALF_GAP = 15f;

    private static final class Pipe {
        float left = 105f;
        final float gapCenter;
        boolean passed;

        Pipe(float gapCenter) {
            this.gapCenter = gapCenter;
        }
    }

    private final ArrayList<Pipe> pipes = new ArrayList<>();
    private final Path beak = new Path();
    private boolean started;
    private float birdY, velocity;

    FlappyView(Context context) {
        super(context, "flappy", 100, 100);
        beak.moveTo(4.25f, -0.9f);
        beak.lineTo(7.75f, 0);
        beak.lineTo(4.25f, 0.9f);
        beak.close();
    }

    @Override
    void resetGame() {
        pipes.clear();
        started = false;
        birdY = 45f;
        velocity = 0f;
    }

    @Override
    boolean onGameTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            started = true;
            velocity = -65f;
        }
        return true;
    }

    @Override
    boolean needsFrames() {
        return started;
    }

    @Override
    void advance(float seconds) {
        velocity += 200f * seconds;
        birdY += velocity * seconds;
        if (birdY < RADIUS || birdY > 100f - RADIUS) {
            finishGame(false);
            return;
        }

        if (pipes.isEmpty() || pipes.get(pipes.size() - 1).left <= 41f) {
            pipes.add(new Pipe(26f + random.nextFloat() * 38f));
        }
        float hitRadius = RADIUS * 0.75f;
        for (int i = pipes.size() - 1; i >= 0; i--) {
            Pipe pipe = pipes.get(i);
            pipe.left -= 32f * seconds;
            float right = pipe.left + PIPE_WIDTH;
            if (right < 0f) {
                pipes.remove(i);
                continue;
            }
            if (!pipe.passed && right < BIRD_X) {
                pipe.passed = true;
                setScore(score + 1);
                tick();
            }
            if (BIRD_X + hitRadius > pipe.left && BIRD_X - hitRadius < right
                    && (birdY - hitRadius < pipe.gapCenter - HALF_GAP
                    || birdY + hitRadius > pipe.gapCenter + HALF_GAP)) {
                finishGame(false);
                return;
            }
        }
    }

    @Override
    void drawGame(Canvas canvas) {
        canvas.drawColor(tint(accent, 0.12f));
        paint.setColor(tint(ink, 0.18f));
        canvas.drawRect(0, 94, 100, 100, paint);
        for (int i = 0; i < pipes.size(); i++) {
            Pipe pipe = pipes.get(i);
            float right = pipe.left + PIPE_WIDTH;
            float top = pipe.gapCenter - HALF_GAP;
            float bottom = pipe.gapCenter + HALF_GAP;
            paint.setColor(blend(accent, ink, 0.28f));
            canvas.drawRect(pipe.left, 0, right, top, paint);
            canvas.drawRect(pipe.left, bottom, right, 100, paint);
            paint.setColor(blend(accent, ink, 0.55f));
            canvas.drawRect(pipe.left - 2.2f, top - 3.5f, right + 2.2f, top, paint);
            canvas.drawRect(pipe.left - 2.2f, bottom, right + 2.2f, bottom + 3.5f, paint);
        }

        canvas.save();
        canvas.translate(BIRD_X, birdY);
        canvas.rotate(Math.max(-25f, Math.min(90f, velocity * 0.5f)));
        paint.setColor(ink);
        canvas.drawPath(beak, paint);
        canvas.drawCircle(0f, 0f, RADIUS, paint);
        paint.setColor(accent);
        canvas.drawCircle(0f, 0f, RADIUS - 0.45f, paint);
        paint.setColor(surface);
        canvas.drawCircle(1.5f, -0.75f, 1.9f, paint);
        paint.setColor(ink);
        canvas.drawCircle(1.9f, -0.75f, 1f, paint);
        canvas.restore();
    }
}
