package com.newtube.mobile.ui.meetune;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Outline;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.YouTubeMode;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;

import java.util.Objects;

/**
 * MeeTune's mini player: a song minimized from the music player docks as a glass pill (cover,
 * title, artist, play/pause) instead of MeeTube's video card. It floats above the bar; scrolling
 * down folds the bar into the open tab's circle and Search's with the pill between them, and
 * scrolling up (or tapping the tab's circle) unfolds it. OG Mode's docked bar never folds.
 */
final class MeeTuneMiniPill {
    private final Activity mActivity;
    private final View mPill;
    private final View mTab;
    private final View mSearch;
    private final View mNavGlass;
    private final BottomNavigationView mNav;
    private final RecyclerView mList;
    private final ImageView mArt;
    private final TextView mTitle;
    private final TextView mArtist;
    private final ImageView mPlay;
    private final View mNext;
    private final View mTabIcon;
    private final View mSearchIcon;
    private final int mListPad;
    private final int mDp;
    private final Player.Listener mListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            refresh();
        }
    };
    @Nullable
    private ExoPlayer mPlayer;
    @Nullable
    private String mArtUrl;
    private float mFold; // 0 bar open, 1 folded
    private float mFoldTo;
    @Nullable
    private ValueAnimator mFolding;
    private int mScrolled;
    private boolean mFlight; // the music player is minimizing onto the pill

    MeeTuneMiniPill(Activity activity, View navGlass, BottomNavigationView nav, RecyclerView list, Runnable search) {
        mActivity = activity;
        mNavGlass = navGlass;
        mNav = nav;
        mList = list;
        mPill = activity.findViewById(R.id.meetune_mini);
        mTab = activity.findViewById(R.id.meetune_mini_tab);
        mSearch = activity.findViewById(R.id.meetune_mini_search);
        mArt = activity.findViewById(R.id.meetune_mini_art);
        mTitle = activity.findViewById(R.id.meetune_mini_title);
        mArtist = activity.findViewById(R.id.meetune_mini_artist);
        mPlay = activity.findViewById(R.id.meetune_mini_play);
        mNext = activity.findViewById(R.id.meetune_mini_next);
        mTabIcon = activity.findViewById(R.id.meetune_mini_tab_icon);
        mSearchIcon = activity.findViewById(R.id.meetune_mini_search_icon);
        mNext.setOnClickListener(v -> {
            Haptics.click(v);
            Motion.pop(v);
            MiniPlayerBridge.next();
        });
        mListPad = list.getPaddingBottom();
        mDp = MeeTuneAdapter.dp(activity, 1);
        mArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 8 * mDp);
            }
        });
        mArt.setClipToOutline(true);

        mPill.setOnTouchListener(new SwipeAway());
        mPill.setOnClickListener(v -> {
            if (!MiniPlayerBridge.isActive()) return;
            Haptics.click(v);
            Motion.nudge(v);
            MiniPlayerBridge.setMiniBounds(null); // the music player fades in, it doesn't grow from the pill
            MiniPlayerBridge.expand(mActivity);
        });
        mPlay.setOnClickListener(v -> {
            ExoPlayer player = MiniPlayerBridge.getPlayer();
            if (player == null) return;
            Haptics.click(v);
            Motion.pop(v);
            player.setPlayWhenReady(!player.getPlayWhenReady());
            refresh();
        });
        mTab.setOnClickListener(v -> {
            Haptics.click(v);
            Motion.pop(v);
            fold(false);
        });
        mSearch.setOnClickListener(v -> {
            Haptics.click(v);
            Motion.pop(v);
            fold(false);
            search.run();
        });
        navGlass.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::place));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy == 0) return;
                if (!view.canScrollVertically(-1)) {
                    fold(false);
                    return;
                }
                mScrolled = Integer.signum(dy) == Integer.signum(mScrolled) ? mScrolled + dy : dy;
                if (Math.abs(mScrolled) > 24 * mDp) fold(mScrolled > 0);
            }
        });
    }

    /** Shows the pill while a MeeTune song is the mini session; true when it does. */
    boolean sync() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player != mPlayer) {
            if (mPlayer != null) mPlayer.removeListener(mListener);
            mPlayer = player;
            if (player != null) player.addListener(mListener);
        }
        return refresh();
    }

    void release() {
        MeeTune.setPill(null, null, null);
        if (mPlayer != null) mPlayer.removeListener(mListener);
        mPlayer = null;
    }

    /** A new page: the bar comes back. */
    void unfold() {
        fold(false);
    }

    /** The minimize handoff: runs {@code onDrawn} once the pill has been drawn (100 ms at most). */
    void whenDrawn(Runnable onDrawn) {
        ViewTreeObserver observer = mPill.getViewTreeObserver();
        class Gate implements ViewTreeObserver.OnDrawListener, Runnable {
            private boolean mDone;

            @Override
            public void onDraw() {
                mPill.post(this);
            }

            @Override
            public void run() {
                if (mDone) return;
                mDone = true;
                if (observer.isAlive()) observer.removeOnDrawListener(this);
                mPill.postOnAnimation(onDrawn);
            }
        }
        Gate gate = new Gate();
        observer.addOnDrawListener(gate);
        mPill.postDelayed(gate, 100);
        mPill.invalidate();
    }

    /**
     * The music player minimizing at {@code f}: the pill comes up after the finger (fades and rises
     * into place), its cover and name hidden while the player's own fly onto them ({@code carried}).
     * 0 ends it: back up, or the handoff done.
     */
    void fly(float f, @Nullable Video video, boolean playing, boolean carried) {
        if (f <= 0f || video == null) {
            land();
            refresh();
            return;
        }
        if (!mFlight) {
            mFlight = true;
            fill(video, playing);
            if (mPill.getVisibility() != View.VISIBLE) {
                mPill.setTranslationX(0f);
                mPill.setVisibility(View.VISIBLE);
                place();
                padList();
            }
        }
        float shown = Math.min(1f, f);
        mPill.setAlpha(Math.min(1f, shown * 1.6f));
        mPill.setTranslationY((1f - shown) * 32 * mDp);
        // The pill's own cover and name fade in over the flight's last fifth as the player's fade out
        // (MeeTune#handoff): their sizes differ a little (14/12sp against 22/20sp scaled), so a hard swap jumped.
        float own = carried ? MeeTune.handoff(shown) : 1f;
        mArt.setAlpha(own);
        ((View) mTitle.getParent()).setAlpha(own);
    }

    /** The flight is over: the pill's own cover and name take over from the player's. */
    void land() {
        if (!mFlight) return;
        mFlight = false;
        mPill.setAlpha(1f);
        mPill.setTranslationY(0f);
        mArt.setAlpha(1f);
        ((View) mTitle.getParent()).setAlpha(1f);
    }

    private void fill(Video video, boolean playing) {
        mTitle.setText(video.title);
        mArtist.setText(video.author);
        String art = MeeTune.art(video);
        if (!Objects.equals(art, mArtUrl)) {
            mArtUrl = art;
            Glide.with(mArt).load(art).into(mArt);
        }
        mPlay.setImageResource(playing ? R.drawable.ic_meetune_pause : R.drawable.ic_meetune_play);
    }

    private boolean refresh() {
        if (mFlight) return true; // the player is landing here: fly() keeps the pill up
        Video video = MiniPlayerBridge.getVideo();
        boolean music = video != null && MeeTune.isMusic(video) && MeeTune.musicPlayer(mActivity);
        if (!music) {
            if (mPill.getVisibility() == View.VISIBLE) {
                fold(false);
                mPill.setVisibility(View.GONE);
                padList();
            }
            return false;
        }
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        fill(video, player != null && player.getPlayWhenReady());
        MeeTune.remember(mActivity, video); // History, even when the song changed with the player minimized
        if (mPill.getVisibility() != View.VISIBLE) {
            mPill.setTranslationX(0f);
            mPill.setVisibility(View.VISIBLE); // no fade: the player's cover has just landed right here
            place();
            padList();
        }
        return true;
    }

    private void padList() {
        boolean above = mPill.getVisibility() == View.VISIBLE;
        mList.setPadding(mList.getPaddingLeft(), mList.getPaddingTop(), mList.getPaddingRight(),
                mListPad + (above ? 64 * mDp : 0));
    }

    private void fold(boolean fold) {
        if (fold && YouTubeMode.isOn(mActivity)) return;
        float to = fold ? 1f : 0f;
        if (mFolding != null && mFolding.isRunning()) {
            if (mFoldTo == to) return;
            mFolding.cancel();
        } else if (mFold == to) {
            return;
        }
        mFoldTo = to;
        mScrolled = 0;
        if (fold) {
            MenuItem open = mNav.getMenu().findItem(mNav.getSelectedItemId());
            ((ImageView) mActivity.findViewById(R.id.meetune_mini_tab_icon))
                    .setImageDrawable(open != null ? open.getIcon() : null);
            mTab.setContentDescription(open != null ? open.getTitle() : null);
        }
        mFolding = ValueAnimator.ofFloat(mFold, to);
        mFolding.setDuration(320);
        mFolding.setInterpolator(Motion.STANDARD);
        mFolding.addUpdateListener(a -> {
            mFold = (float) a.getAnimatedValue();
            place();
        });
        mFolding.start();
    }

    /** Lays the pill, the bar and the two circles out for {@link #mFold}. */
    private void place() {
        View parent = (View) mNavGlass.getParent();
        if (mNavGlass.getHeight() == 0 || parent.getWidth() == 0) return;
        float f = mFold;
        int size = 56 * mDp;
        int gap = 8 * mDp;
        int left = mNavGlass.getLeft();
        int right = parent.getWidth() - mNavGlass.getRight();
        int navBottom = parent.getHeight() - mNavGlass.getBottom();
        int rowBottom = navBottom + (mNavGlass.getHeight() - size) / 2;
        int openBottom = navBottom + mNavGlass.getHeight() + gap;
        int pillMarginLeft = Math.round(left + (size + gap) * f);
        int pillMarginRight = Math.round(right + (size + gap) * f);
        margins(mPill, pillMarginLeft, pillMarginRight, Math.round(openBottom + (rowBottom - openBottom) * f));
        // Where the music player's cover and name land as it minimizes: this pill's, open bar or folded.
        int[] at = new int[2];
        parent.getLocationOnScreen(at);
        int pillLeft = at[0] + pillMarginLeft;
        int pillTop = at[1] + parent.getHeight() - Math.round(openBottom + (rowBottom - openBottom) * f) - size;
        int pillWidth = parent.getWidth() - pillMarginLeft - pillMarginRight;
        int textRight = pillLeft + pillWidth - (6 + 44 + (f >= 1f ? 0 : 44)) * mDp;
        MeeTune.setPill(this,
                new android.graphics.Rect(pillLeft + 8 * mDp, pillTop + 8 * mDp, pillLeft + 48 * mDp, pillTop + 48 * mDp),
                new android.graphics.Rect(pillLeft + 58 * mDp, pillTop, Math.max(pillLeft + 58 * mDp, textRight), pillTop + size));

        // The bar splits into its two ends: each half shrinks, still glass, into a circle at its side
        // (the open tab's and Search's), the labels fading off as the icons come in.
        int navWidth = mNavGlass.getWidth();
        int navHeight = mNavGlass.getHeight();
        float open = 1f - f;
        int circleWidth = Math.round(size + (navWidth / 2f - size) * open);
        int circleHeight = Math.round(size + (navHeight - size) * open);
        int circleBottom = navBottom + (navHeight - circleHeight) / 2;
        size(mTab, circleWidth, circleHeight);
        size(mSearch, circleWidth, circleHeight);
        margins(mTab, left, 0, circleBottom);
        margins(mSearch, 0, right, circleBottom);
        float swap = Math.min(1f, f / 0.2f); // the bar hands over to its halves at once
        mNavGlass.setAlpha(1f - swap);
        mNavGlass.setVisibility(swap >= 1f ? View.INVISIBLE : View.VISIBLE);
        float icon = Math.max(0f, Math.min(1f, (f - 0.35f) / 0.5f));
        for (View dot : new View[] {mTab, mSearch}) {
            dot.setAlpha(swap);
            dot.setVisibility(f > 0f ? View.VISIBLE : View.GONE);
        }
        mTabIcon.setAlpha(icon);
        mSearchIcon.setAlpha(icon);
        // Next only on the open bar's wide pill; folded between the circles it's pause alone.
        mNext.setAlpha(1f - f);
        mNext.setVisibility(f >= 1f ? View.GONE : View.VISIBLE);
    }

    /**
     * Swipe the pill sideways to stop the song, as the video card swipes away: it follows the
     * finger fading, and past a third of its width (or flung) flies off and closes the session;
     * short of that it springs back. A tap stays a tap until the finger moves past the slop.
     */
    private final class SwipeAway implements View.OnTouchListener {
        private float mDownX;
        private boolean mDragging;
        @Nullable
        private android.view.VelocityTracker mVelocity;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    mDownX = event.getRawX();
                    mDragging = false;
                    v.animate().cancel();
                    if (mVelocity != null) mVelocity.recycle();
                    mVelocity = android.view.VelocityTracker.obtain();
                    mVelocity.addMovement(event);
                    return false; // the pill's own press and click carry on
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (mVelocity != null) mVelocity.addMovement(event);
                    float dx = event.getRawX() - mDownX;
                    if (!mDragging) {
                        if (Math.abs(dx) < android.view.ViewConfiguration.get(mActivity).getScaledTouchSlop()) {
                            return false;
                        }
                        mDragging = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                        cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        v.onTouchEvent(cancel); // no click when the finger lets go
                        cancel.recycle();
                    }
                    v.setTranslationX(dx);
                    v.setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / v.getWidth()));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!mDragging) return false;
                    mDragging = false;
                    float dx = event.getRawX() - mDownX;
                    float speed = 0f;
                    if (mVelocity != null) {
                        mVelocity.addMovement(event);
                        mVelocity.computeCurrentVelocity(1000);
                        speed = mVelocity.getXVelocity();
                        mVelocity.recycle();
                        mVelocity = null;
                    }
                    boolean away = event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            && (Math.abs(dx) > v.getWidth() / 3f
                            || (Math.abs(speed) > 1000 * mDp && Math.signum(speed) == Math.signum(dx)));
                    if (away) {
                        Haptics.click(v);
                        v.animate().translationX(Math.signum(dx) * v.getWidth() * 1.2f).alpha(0f)
                                .setDuration(Motion.EXIT_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                                .withEndAction(() -> {
                                    MiniPlayerBridge.close();
                                    refresh(); // the session is gone: the pill goes
                                    v.setTranslationX(0f);
                                    v.setAlpha(1f);
                                }).start();
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(Motion.ENTER_MS)
                                .setInterpolator(Motion.STANDARD).start();
                    }
                    return true;
                }
                default:
                    return false;
            }
        }
    }

    private static void size(View view, int width, int height) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            view.setLayoutParams(lp);
        }
    }

    private static void margins(View view, int left, int right, int bottom) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (lp.leftMargin != left || lp.rightMargin != right || lp.bottomMargin != bottom) {
            lp.leftMargin = left;
            lp.rightMargin = right;
            lp.bottomMargin = bottom;
            view.setLayoutParams(lp);
        }
    }
}
