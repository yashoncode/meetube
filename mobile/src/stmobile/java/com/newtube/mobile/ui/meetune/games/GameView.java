package com.newtube.mobile.ui.meetune.games;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.Random;

/** A round in board coordinates. Only a game with motion or a pending timer requests frames. */
abstract class GameView extends View implements Choreographer.FrameCallback {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Random random = new Random();
    final int surface;
    final int ink;
    int accent;
    int score;
    boolean paused, over, won;
    private final float boardWidth, boardHeight;
    private final SharedPreferences records;
    private final String recordKey;
    private int record;
    private Runnable changed;
    private float scale, left, top;
    private int pointer = MotionEvent.INVALID_POINTER_ID;
    private boolean framePending;
    private long lastFrame;

    GameView(Context context, String key, float width, float height) {
        super(context);
        boardWidth = width;
        boardHeight = height;
        surface = MaterialColors.getColor(this, R.attr.colorSurface);
        ink = MaterialColors.getColor(this, R.attr.colorOnSurface);
        records = context.getSharedPreferences("meetune", Context.MODE_PRIVATE);
        recordKey = "game_best_" + key;
        record = records.getInt(recordKey, 0);
    }

    abstract void resetGame();
    abstract void drawGame(Canvas canvas);
    abstract boolean onGameTouch(int action, float x, float y);
    boolean needsFrames() { return false; }
    void advance(float seconds) { }
    void cancelGesture() { }

    final void start(Runnable listener) {
        changed = listener;
        restart();
    }

    final void restart() {
        saveRecord();
        stopFrames();
        endGesture();
        paused = over = won = false;
        score = 0;
        resetGame();
        changed.run();
        redraw();
    }

    final void pause() {
        if (paused || over) return;
        paused = true;
        stopFrames();
        endGesture();
        saveRecord();
        changed.run();
    }

    final void resumeRound() {
        if (!paused) return;
        paused = false;
        changed.run();
        redraw();
    }

    final void finishGame(boolean victory) {
        if (over) return;
        over = true;
        won = victory;
        stopFrames();
        endGesture();
        saveRecord();
        Haptics.longPress(this);
        changed.run();
        invalidate();
    }

    final int best() { return Math.max(record, score); }

    private void saveRecord() {
        if (score <= record) return;
        record = Math.max(score, records.getInt(recordKey, 0));
        records.edit().putInt(recordKey, record).apply();
    }

    final void setScore(int value) {
        if (score == value) return;
        score = value;
        changed.run();
    }

    final void tick() { Haptics.tick(this); }

    static int blend(int from, int to, float fraction) {
        return ColorUtils.blendARGB(from, to, fraction);
    }

    /** The colour at this strength, see-through: the board sits on the cover's gradient, not a card. */
    static int tint(int color, float strength) {
        return ColorUtils.setAlphaComponent(color, Math.round(strength * 255));
    }

    private void redraw() {
        invalidate();
        if (!paused && !over && needsFrames() && isAttachedToWindow()) {
            if (!framePending) {
                framePending = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        } else {
            stopFrames();
        }
    }

    @Override
    public final void doFrame(long now) {
        framePending = false;
        if (lastFrame != 0) advance(Math.min((now - lastFrame) / 1_000_000_000f, 0.05f));
        lastFrame = now;
        redraw();
    }

    private void stopFrames() {
        if (framePending) Choreographer.getInstance().removeFrameCallback(this);
        framePending = false;
        lastFrame = 0;
    }

    @Override
    protected final void onDetachedFromWindow() {
        if (changed != null) pause();
        super.onDetachedFromWindow();
    }

    @Override
    protected final void onSizeChanged(int w, int h, int oldW, int oldH) {
        scale = Math.min(w / boardWidth, h / boardHeight);
        left = (w - boardWidth * scale) / 2f;
        top = (h - boardHeight * scale) / 2f;
    }

    @Override
    protected final void onDraw(Canvas canvas) {
        if (scale <= 0) return;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        canvas.clipRect(0, 0, boardWidth, boardHeight);
        drawGame(canvas);
        canvas.restore();
    }

    @Override
    public final boolean onTouchEvent(MotionEvent event) {
        if (paused || over || scale <= 0) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float x = (event.getX() - left) / scale, y = (event.getY() - top) / scale;
            if (x < 0 || x >= boardWidth || y < 0 || y >= boardHeight) return false;
            pointer = event.getPointerId(0);
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            endGesture();
            redraw();
            return true;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            return true;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerId(event.getActionIndex()) != pointer) return true;
            action = MotionEvent.ACTION_UP;
        }
        int index = event.findPointerIndex(pointer);
        if (index < 0) return false;
        boolean handled = onGameTouch(action, (event.getX(index) - left) / scale,
                (event.getY(index) - top) / scale);
        if (action == MotionEvent.ACTION_UP || !handled && action == MotionEvent.ACTION_DOWN) endGesture();
        redraw();
        return handled;
    }

    private void endGesture() {
        cancelGesture();
        pointer = MotionEvent.INVALID_POINTER_ID;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }
}
