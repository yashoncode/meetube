package com.newtube.mobile.ui.channel;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.newtube.mobile.ui.playback.AmbientGlow;

import java.security.MessageDigest;

/** MEETUBE(playlist page): full-width cover wash follows the header, including under the toolbar. */
final class PlaylistBackdrop {
    private final View mHost;
    private final ImageView mImage;
    private String mUrl;

    PlaylistBackdrop(View host, ImageView image) {
        mHost = host;
        mImage = image;
        image.setColorFilter(0x66000000, PorterDuff.Mode.SRC_ATOP);
        if (Build.VERSION.SDK_INT >= 31) {
            float blur = 32 * host.getResources().getDisplayMetrics().density;
            image.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        }
    }

    void setCover(String url) {
        if (TextUtils.equals(mUrl, url)) {
            return;
        }
        if (destroyed()) {
            return;
        }
        mUrl = url;
        if (Build.VERSION.SDK_INT >= 31) {
            Glide.with(mImage).load(url).override(320, 180).centerCrop().into(mImage);
        } else {
            Glide.with(mImage).asBitmap().load(url).override(96, 54).transform(new TinyBlur()).into(mImage);
        }
    }

    void follow(View header, int toolbarHeight, int dy, boolean visible) {
        mHost.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        if (header != null) {
            int height = header.getHeight() + toolbarHeight
                    + Math.round(120 * mHost.getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams params = mHost.getLayoutParams();
            if (height > toolbarHeight && params.height != height) {
                params.height = height;
                mHost.setLayoutParams(params);
            }
            mHost.setTranslationY(header.getTop() - toolbarHeight);
        } else {
            mHost.setTranslationY(mHost.getTranslationY() - dy);
        }
    }

    void clear() {
        mUrl = null;
        if (!destroyed()) {
            Glide.with(mImage).clear(mImage);
        }
        mImage.setImageDrawable(null);
        mHost.setTranslationY(0);
        mHost.setVisibility(View.GONE);
    }

    /** Glide.with() throws for a destroyed activity, and a late header bind or refresh can land then. */
    private boolean destroyed() {
        android.content.Context context = mImage.getContext();
        return context instanceof android.app.Activity && ((android.app.Activity) context).isDestroyed();
    }

    /** MEETUBE(playlist page): blur on Glide's worker at 48x27, reusing the player's box blur. */
    private static final class TinyBlur extends BitmapTransformation {
        @Override
        protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap source, int width, int height) {
            Bitmap tiny = pool.get(48, 27, Bitmap.Config.ARGB_8888);
            new Canvas(tiny).drawBitmap(source, null, new Rect(0, 0, 48, 27), new Paint(Paint.FILTER_BITMAP_FLAG));
            AmbientGlow.blur(tiny);
            return tiny;
        }

        @Override
        public void updateDiskCacheKey(@NonNull MessageDigest digest) {
            digest.update("meetube-playlist-cover-blur-v1".getBytes(CHARSET));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TinyBlur;
        }

        @Override
        public int hashCode() {
            return TinyBlur.class.getName().hashCode();
        }
    }
}
