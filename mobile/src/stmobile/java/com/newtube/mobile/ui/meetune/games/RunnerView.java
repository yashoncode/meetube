/* Adapted from Morphe Manager's DinoGame (Copyright 2026 Morphe). */
package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.util.ArrayList;

final class RunnerView extends GameView {
    private static final float GROUND = 78f;
    private static final float DINO_X = 12f;
    private static final float DINO_W = 7f;
    private static final float DINO_H = 13f;
    private static final float CACTUS_W = 6f;

    private final ArrayList<RectF> cacti = new ArrayList<>();
    private final ArrayList<RectF> clouds = new ArrayList<>();
    private final ArrayList<PointF> gulls = new ArrayList<>();
    private float jump, velocity, distance, elapsed;
    private float lastCactus, lastCloud, lastGull;
    private boolean started;
    private LinearGradient sky;
    private int skyTop, skyHorizon;

    RunnerView(Context context) {
        super(context, "runner", 100, 100);
    }

    @Override
    void resetGame() {
        cacti.clear();
        gulls.clear();
        clouds.clear();
        addCloud(25f);
        addCloud(68f);
        jump = velocity = distance = elapsed = 0f;
        lastCactus = lastCloud = lastGull = 0f;
        started = false;
    }

    @Override
    boolean onGameTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            started = true;
            if (jump == 0f) velocity = 105f;
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
        float travel = Math.min(80f, 35f + elapsed * 3f) * seconds;
        distance += travel;
        int nextScore = (int) distance;
        if (nextScore / 100 > score / 100) tick();
        if (nextScore != score) setScore(nextScore);

        if (jump > 0f || velocity > 0f) {
            velocity -= 280f * seconds;
            jump = Math.max(0f, jump + velocity * seconds);
            if (jump == 0f) velocity = 0f;
        }
        if (elapsed - lastCactus >= Math.max(1.4f, 2.8f - elapsed * 0.03f)) {
            cacti.add(new RectF(102f, GROUND - 10f - random.nextFloat() * 5f, 102f + CACTUS_W, GROUND));
            lastCactus = elapsed;
        }
        for (int i = cacti.size() - 1; i >= 0; i--) {
            RectF cactus = cacti.get(i);
            cactus.offset(-travel, 0f);
            if (cactus.right <= 0f) cacti.remove(i);
            if (DINO_X + DINO_W * 0.9f > cactus.left && DINO_X + DINO_W * 0.1f < cactus.right
                    && GROUND - jump - DINO_H * 0.1f > cactus.top) {
                finishGame(false);
                return;
            }
        }
        for (int i = clouds.size() - 1; i >= 0; i--) {
            RectF cloud = clouds.get(i);
            cloud.offset(-travel * 0.15f, 0f);
            if (cloud.right + cloud.width() < 0f) clouds.remove(i);
        }
        if (elapsed - lastCloud >= 7f) {
            addCloud(108f);
            lastCloud = elapsed;
        }
        for (int i = gulls.size() - 1; i >= 0; i--) {
            PointF gull = gulls.get(i);
            gull.x -= travel * 0.65f;
            if (gull.x < -12f) gulls.remove(i);
        }
        if (elapsed - lastGull >= Math.max(2.5f, 5f - elapsed * 0.020f)) {
            gulls.add(new PointF(105f, 4f + random.nextFloat() * 28f));
            lastGull = elapsed;
        }
    }

    private void addCloud(float x) {
        float y = 4f + random.nextFloat() * 20f;
        float r = 5.5f * (0.65f + random.nextFloat() * 0.45f);
        clouds.add(new RectF(x - r, y - r, x + r, y + r));
    }

    @Override
    void drawGame(Canvas canvas) {
        int top = tint(accent, 0.38f);
        int horizon = tint(accent, 0.10f);
        if (sky == null || top != skyTop || horizon != skyHorizon) {
            skyTop = top;
            skyHorizon = horizon;
            sky = new LinearGradient(0, 0, 0, GROUND, top, horizon, Shader.TileMode.CLAMP);
        }
        paint.setShader(sky);
        canvas.drawRect(0, 0, 100, GROUND, paint);
        paint.setShader(null);
        paint.setColor(tint(ink, 0.14f));
        canvas.drawRect(0, GROUND, 100, 100, paint);
        paint.setColor(tint(ink, 0.38f));
        canvas.drawRect(0, GROUND, 100, GROUND + 0.55f, paint);

        paint.setColor(tint(ink, 0.10f));
        for (int i = 0; i < clouds.size(); i++) {
            RectF cloud = clouds.get(i);
            float r = cloud.width() / 2f;
            float x = cloud.centerX();
            float y = cloud.centerY();
            canvas.drawCircle(x, y, r, paint);
            canvas.drawCircle(x - r * 0.85f, y + r * 0.15f, r * 0.72f, paint);
            canvas.drawCircle(x + r * 0.85f, y + r * 0.15f, r * 0.72f, paint);
        }
        paint.setColor(tint(ink, 0.48f));
        paint.setStrokeWidth(0.55f);
        for (int i = 0; i < gulls.size(); i++) {
            PointF gull = gulls.get(i);
            float phase = (elapsed * 2.5f + gull.y) % 1f;
            float tipY = gull.y - 3f * Math.min(phase, 1f - phase);
            canvas.drawLine(gull.x - 2.8f, tipY, gull.x, gull.y, paint);
            canvas.drawLine(gull.x, gull.y, gull.x + 2.8f, tipY, paint);
        }
        paint.setColor(blend(accent, ink, 0.40f));
        for (int i = 0; i < cacti.size(); i++) {
            RectF cactus = cacti.get(i);
            canvas.save();
            canvas.translate(cactus.left, cactus.top);
            canvas.scale(CACTUS_W, cactus.height());
            canvas.drawRect(0.35f, 0, 0.65f, 1, paint);
            canvas.drawRect(0, 0.28f, 0.14f, 0.55f, paint);
            canvas.drawRect(0, 0.44f, 0.4f, 0.59f, paint);
            canvas.drawRect(0.62f, 0.55f, 1, 0.7f, paint);
            canvas.drawRect(0.86f, 0.38f, 1, 0.68f, paint);
            canvas.restore();
        }
        drawDino(canvas);
    }

    private void drawDino(Canvas canvas) {
        canvas.save();
        canvas.translate(DINO_X, GROUND - jump - DINO_H);
        canvas.scale(DINO_W, DINO_H);
        paint.setColor(ink);
        canvas.drawRect(0, 0.32f, 0.14f, 0.59f, paint);       // tail
        canvas.drawRect(0.08f, 0.44f, 0.67f, 0.79f, paint);   // torso
        canvas.drawRect(0.38f, 0.23f, 0.60f, 0.50f, paint);   // neck
        canvas.drawRect(0.34f, 0, 1, 0.30f, paint);            // head
        canvas.drawRect(0.59f, 0.28f, 1, 0.37f, paint);        // jaw
        canvas.drawRect(0.61f, 0.40f, 0.88f, 0.46f, paint);
        canvas.drawRect(0.60f, 0.55f, 0.81f, 0.63f, paint);   // arm
        canvas.drawRect(0.74f, 0.62f, 0.82f, 0.70f, paint);

        boolean leftFootUp = ((int) (elapsed / 0.15f) & 1) != 0;
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? 0.13f : 0.40f;
            boolean raised = i == 0 ? leftFootUp : !leftFootUp;
            float foot = jump == 0f && raised ? 0.88f : 1f;
            canvas.drawRect(x, 0.72f, x + 0.17f, foot, paint);
            canvas.drawRect(x, foot - 0.08f, x + 0.28f, foot, paint);
        }
        paint.setColor(surface);
        canvas.drawOval(0.73f, 0.05f, 0.92f, 0.18f, paint);
        paint.setColor(ink);
        canvas.drawOval(0.81f, 0.09f, 0.88f, 0.15f, paint);
        canvas.restore();
    }
}
