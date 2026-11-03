package com.liskovsoft.smartyoutubetv2.tv.ui.common;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.BackgroundManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import androidx.annotation.Nullable;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseBackdropTransformation;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class UriBackgroundManager {
    private static final String TAG = UriBackgroundManager.class.getSimpleName();
    private static final int BACKGROUND_UPDATE_DELAY_MS = 300;
    private Uri mBackgroundURI;
    private Drawable mDefaultBackground;
    private DisplayMetrics mMetrics;
    private Runnable mBackgroundTask;
    private BackgroundManager mBackgroundManager;
    private final Activity mActivity;
    private final Handler mHandler;
    private int mBackgroundColor = -1;
    private boolean mBrowseGlass;
    private boolean mStopped;
    private int mBrowseGeneration;
    private CustomTarget<Bitmap> mBrowseTarget;

    /** Explicitly enabled by BrowseFragment, including restoration of the original Settings backdrop. */
    public void setBrowseGlassEnabled(boolean enabled) {
        if (mBrowseGlass == enabled) {
            return;
        }
        cancelBrowseLoad();
        mBackgroundURI = null;
        mBrowseGlass = enabled;
        mBackgroundManager.setBackgroundContinuityEnabled(!enabled);
        mBackgroundManager.setBackgroundChangeTiming(enabled ? 300 : 500, enabled ? 300 : 500);
        setDefaultBackground();
    }

    private void cancelBrowseLoad() {
        mBrowseGeneration++;
        mHandler.removeCallbacks(mBackgroundTask);
        if (mBrowseTarget != null) {
            Glide.with(mActivity.getApplicationContext()).clear(mBrowseTarget);
            mBrowseTarget = null;
        }
    }

    public UriBackgroundManager(Activity activity) {
        mActivity = activity;
        mHandler = new Handler();
        prepareBackgroundManager();
        setDefaultBackground();
    }

    private void prepareBackgroundManager() {
        mBackgroundManager = BackgroundManager.getInstance(mActivity);
        mBackgroundManager.attach(mActivity.getWindow());
        mDefaultBackground = ContextCompat.getDrawable(mActivity, Helpers.getThemeAttr(mActivity, R.attr.shelfBackground));
        mBackgroundTask = new UpdateBackgroundTask();
        mMetrics = new DisplayMetrics();
        mActivity.getWindowManager().getDefaultDisplay().getMetrics(mMetrics);
    }

    private void startBackgroundTimer(Uri backgroundURI) {
        mBackgroundURI = backgroundURI;
        mHandler.removeCallbacks(mBackgroundTask);
        mHandler.postDelayed(mBackgroundTask, BACKGROUND_UPDATE_DELAY_MS);
    }

    public void startBackgroundTimer(String bgImageUrl) {
        if (bgImageUrl != null) {
            startBackgroundTimer(Uri.parse(bgImageUrl));
        }
    }

    public void setBackgroundFrom(Video item) {
        if (!mBrowseGlass || mStopped) {
            return;
        }
        String url = item != null ? item.cardImageUrl : null;
        Uri uri = url != null ? Uri.parse(url) : null;
        if (uri != null && uri.equals(mBackgroundURI)) {
            return;
        }
        cancelBrowseLoad();
        if (uri == null) {
            mBackgroundURI = null;
            setDefaultBackground();
        } else {
            startBackgroundTimer(uri);
        }
    }

    public void onStart() {
        mStopped = false;
        if (mBackgroundURI != null) {
            showBackground(mBackgroundURI.toString());
        } else if (mBackgroundColor != -1) {
            setBackgroundColor(mBackgroundColor);
        } else {
            setDefaultBackground();
        }
    }

    public void onStop() {
        mStopped = true;
        if (mBrowseGlass) {
            cancelBrowseLoad();
        }
        mBackgroundManager.release();
    }

    public void onDestroy() {
        if (mBrowseGlass) {
            cancelBrowseLoad();
        }
        mHandler.removeCallbacks(mBackgroundTask);
        mBackgroundManager = null;
    }

    public void removeBackground() {
        mBackgroundManager.setDrawable(null);
    }

    public void setDefaultBackground() {
        mBackgroundManager.setDrawable(mBrowseGlass ? new ColorDrawable(Color.BLACK) : mDefaultBackground);
    }

    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
        mBackgroundManager.setColor(color);
    }

    private class UpdateBackgroundTask implements Runnable {
        @Override
        public void run() {
            if (mBackgroundURI != null) {
                showBackground(mBackgroundURI.toString());
            }
        }
    }

    public BackgroundManager getBackgroundManager() {
        return mBackgroundManager;
    }

    public void showBackground(String uri) {
        if (mBrowseGlass) {
            showBrowseBackground(uri);
            return;
        }
        View videoView = mActivity.findViewById(R.id.video_surface);

        if (videoView != null) {
            videoView.setVisibility(uri == null ? View.VISIBLE : View.INVISIBLE);
        }

        if (uri == null) {
            removeBackground();
            return;
        }

        int width = mMetrics.widthPixels;
        int height = mMetrics.heightPixels;

        RequestOptions options = ViewUtil.glideOptions()
                .centerCrop()
                .error(mDefaultBackground);

        Glide.with(mActivity)
                .asBitmap()
                .load(uri)
                .apply(options)
                .into(new SimpleTarget<Bitmap>(width, height) {
                    @Override
                    public void onResourceReady(
                            @NonNull Bitmap resource,
                            Transition<? super Bitmap> transition) {
                        mBackgroundManager.setBitmap(resource);
                    }
                });
    }

    public void showBackgroundColor(int colorResId) {
        View videoView = mActivity.findViewById(R.id.video_surface);

        if (videoView != null) {
            videoView.setVisibility(colorResId == -1 ? View.VISIBLE : View.INVISIBLE);
        }

        if (colorResId == -1) {
            removeBackground();
            return;
        }

        if (mBackgroundManager != null) {
            mBackgroundManager.setColor(ContextCompat.getColor(mActivity, colorResId));
        }
    }

    private void showBrowseBackground(String uri) {
        if (mStopped || mActivity.isDestroyed() || mBackgroundManager == null) {
            return;
        }
        cancelBrowseLoad();
        if (uri == null) {
            setDefaultBackground();
            return;
        }
        final int generation = mBrowseGeneration;
        mBrowseTarget = new CustomTarget<Bitmap>(32, 18) {
            @Override
            public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                if (generation == mBrowseGeneration && mBrowseGlass && !mStopped && mBackgroundManager != null) {
                    // BackgroundManager retains its bitmap across fades. Give it a 2KB owned copy,
                    // so clearing Glide's target cannot recycle an image still being displayed.
                    mBackgroundManager.setBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, false));
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (generation == mBrowseGeneration && mBrowseGlass && !mStopped && mBackgroundManager != null) {
                    setDefaultBackground();
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                // The window displays an owned bitmap, independent of this request's lifetime.
            }
        };
        Glide.with(mActivity.getApplicationContext()).asBitmap().load(uri)
                // The shared card options intentionally disable caches for animated previews.
                // This static, tiny backdrop should instead reuse its transformed image.
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .override(32, 18)
                .transform(new CenterCrop(), new BrowseBackdropTransformation())
                .into(mBrowseTarget);
    }
}
