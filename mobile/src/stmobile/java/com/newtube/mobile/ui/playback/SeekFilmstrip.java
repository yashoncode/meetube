package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * MEETUBE(seek preview): YouTube's seek-bar drag. The frame under the finger fills the video (soft:
 * it is the storyboard's small picture scaled up); the time shows in the double-tap seek's glass
 * pill (MobilePlaybackActivity.showSeekTime). Frames come from the video's storyboard sprite
 * sheets; until a sheet is in, or without one (live, or none sent), the paused picture stays.
 *
 * <p>ponytail: sheets stay cached per video and are never evicted (a few 100 KB each, dropped with
 * the view's next video).
 */
public final class SeekFilmstrip extends View {
    private final float mDp = getResources().getDisplayMetrics().density;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final SparseArray<Bitmap> mSheets = new SparseArray<>();
    private final SparseArray<Object> mLoading = new SparseArray<>();
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    @Nullable private MediaItemStoryboard mBoard;
    @Nullable private MediaItemStoryboard.Size mSize;
    @Nullable private Disposable mFetch;
    @Nullable private String mVideoId;
    private long mPosition;

    public SeekFilmstrip(Context context) {
        this(context, null);
    }

    public SeekFilmstrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mFill.setColor(0xFF1A1A1A);
        setClickable(false);
    }

    /** Loads the storyboard of this video (once) when the drag starts. */
    public void prepare(@Nullable String videoId) {
        if (videoId == null || videoId.equals(mVideoId)) {
            return;
        }
        mVideoId = videoId;
        mBoard = null;
        mSize = null;
        mSheets.clear();
        mLoading.clear();
        if (mFetch != null) {
            mFetch.dispose();
        }
        mFetch = YouTubeMediaItemService.instance().getStoryboardObserve(videoId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(board -> {
                    mBoard = board;
                    mSize = board.getGroupSize();
                    invalidate();
                }, error -> { /* no filmstrip, the time still shows */ });
    }

    public void update(long positionMs) {
        mPosition = positionMs;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        MediaItemStoryboard.Size size = mSize;
        if (size == null || size.getDurationEachMS() <= 0 || size.getHeight() <= 0) {
            return;
        }
        // The frame under the finger fills the video, fit inside it; scaled up from the small
        // storyboard picture it is soft, like YouTube's. Black behind it hides the paused picture.
        float scale = Math.min(w / size.getWidth(), h / size.getHeight());
        float fw = size.getWidth() * scale;
        float fh = size.getHeight() * scale;
        int index = (int) (mPosition / size.getDurationEachMS());
        if (hasFrame(size, index)) {
            mFill.setColor(0xFF000000);
            canvas.drawRect(0, 0, w, h, mFill); // not drawColor: the parents do not clip, it would black the page
            mFill.setColor(0xFF1A1A1A);
            draw(canvas, size, index, (w - fw) / 2, (h - fh) / 2, fw, fh);
        }
    }

    /** The frame's sheet is in (it is asked for when not); else the paused picture stays. */
    private boolean hasFrame(MediaItemStoryboard.Size size, int index) {
        int perSheet = Math.max(1, size.getColCount() * size.getRowCount());
        return sheet(index / perSheet) != null;
    }

    private void draw(Canvas canvas, MediaItemStoryboard.Size size, int index, float left, float top,
            float width, float height) {
        mDst.set(left, top, left + width, top + height);
        if (mDst.right < 0 || mDst.left > getWidth()) {
            return;
        }
        int perSheet = Math.max(1, size.getColCount() * size.getRowCount());
        Bitmap sheet = sheet(index / perSheet);
        int inSheet = index % perSheet;
        if (sheet == null) {
            canvas.drawRect(mDst, mFill);
            return;
        }
        int col = inSheet % size.getColCount();
        int row = inSheet / size.getColCount();
        mSrc.set(col * size.getWidth(), row * size.getHeight(),
                (col + 1) * size.getWidth(), (row + 1) * size.getHeight());
        if (mSrc.right > sheet.getWidth() || mSrc.bottom > sheet.getHeight()) {
            canvas.drawRect(mDst, mFill); // a short last sheet
            return;
        }
        canvas.drawBitmap(sheet, mSrc, mDst, mBitmap);
    }

    @Nullable
    private Bitmap sheet(int group) {
        Bitmap bitmap = mSheets.get(group);
        if (bitmap != null || mBoard == null || mLoading.get(group) != null) {
            return bitmap;
        }
        final String videoId = mVideoId;
        final MediaItemStoryboard board = mBoard;
        String url = board.getGroupUrl(group);
        if (url == null) {
            return null;
        }
        mLoading.put(group, this);
        Glide.with(getContext().getApplicationContext()).asBitmap().load(url).into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> t) {
                if (board == mBoard && java.util.Objects.equals(videoId, mVideoId)) {
                    mSheets.put(group, resource);
                    invalidate();
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                // leave the placeholder; a later move will not retry this sheet
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
        return null;
    }
}
