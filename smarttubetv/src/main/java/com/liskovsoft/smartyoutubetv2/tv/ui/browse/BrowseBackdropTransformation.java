package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

/** Runs on Glide's worker thread and is cached with the thumbnail, never on a draw callback. */
public final class BrowseBackdropTransformation extends BitmapTransformation {
    private static final byte[] CACHE_KEY = "meetube.browse.backdrop.32x18.blur2.scrim60.v1".getBytes(CHARSET);

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap source, int outWidth, int outHeight) {
        final int width = 32;
        final int height = 18;
        Bitmap result = pool.get(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(source, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.setBitmap(null);
        int[] pixels = new int[width * height];
        int[] blurred = new int[pixels.length];
        result.getPixels(pixels, 0, width, 0, 0, width, height);
        // Two tiny box-blur passes suppress detail before bilinear enlargement to the window.
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int red = 0, green = 0, blue = 0;
                    for (int dy = -2; dy <= 2; dy++) {
                        int row = Math.max(0, Math.min(height - 1, y + dy)) * width;
                        for (int dx = -2; dx <= 2; dx++) {
                            int color = pixels[row + Math.max(0, Math.min(width - 1, x + dx))];
                            red += (color >> 16) & 255;
                            green += (color >> 8) & 255;
                            blue += color & 255;
                        }
                    }
                    blurred[y * width + x] = 0xFF000000 | (red / 25 << 16) | (green / 25 << 8) | blue / 25;
                }
            }
            int[] swap = pixels;
            pixels = blurred;
            blurred = swap;
        }
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            // Bake in the 60% black scrim so the window needs no extra translucent scrim layer.
            pixels[i] = 0xFF000000 | (((color >> 16) & 255) * 2 / 5 << 16)
                    | (((color >> 8) & 255) * 2 / 5 << 8) | ((color & 255) * 2 / 5);
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BrowseBackdropTransformation;
    }

    @Override
    public int hashCode() {
        return 0x4D544247;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest digest) {
        digest.update(CACHE_KEY);
    }
}
