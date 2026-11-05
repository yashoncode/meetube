package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;

// MEETUBE: TV Home carries the account's Shorts picks; the WEB reel feed is anonymous with TV auth.
// The feed serves the account's shorts 16 at a time from memory, keeps the next two pages fetched ahead,
// and falls back to the stock Shorts feed once they run out (signed out: the stock feed only).
public final class PersonalShortsService implements MediaServiceManager.AccountChangeListener {
    private static final String TAG = PersonalShortsService.class.getSimpleName();
    private static final String PAGE_KEY = "meetube-home-shorts:";
    private static final int PAGE_SIZE = 16;
    private static final int AHEAD_PAGES = 2; // the account's shorts kept fetched past the last page served
    private static final int HOME_ROUNDS = 6;
    private static final int EMPTY_STOCK_PAGES = 3;
    private static PersonalShortsService sInstance;
    private final ContentService mContent = YouTubeServiceManager.instance().getContentService();
    private final Object mLock = new Object();
    // Everything below is guarded by mLock; network calls never run under it.
    private final Map<String, MediaItem> mShorts = new LinkedHashMap<>(); // the account's shorts by video id, in order
    private final ArrayDeque<MediaGroup> mRows = new ArrayDeque<>(); // Shorts rows with more behind them, taking turns
    private final Set<String> mRowKeys = new HashSet<>();
    private final Scheduler mWorker = io.reactivex.schedulers.Schedulers.from(Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "meetube-home-shorts");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        return thread;
    }));
    private int mGeneration;
    private int mHomeLoads;
    private int mHomeRounds;
    private int mRoundAdded;
    private int mAheadTarget;

    private PersonalShortsService() {
        MediaServiceManager.instance().addAccountListener(this);
    }

    public static synchronized PersonalShortsService instance() {
        if (sInstance == null) {
            sInstance = new PersonalShortsService();
        }
        return sInstance;
    }

    public Observable<List<MediaGroup>> getHomeObserve() {
        return Observable.defer(() -> {
            int generation;
            boolean firstWalk;
            synchronized (mLock) {
                generation = mGeneration;
                firstWalk = mHomeRounds == 0 && isSignedIn();
                mHomeLoads++;
                mHomeRounds = Math.max(1, mHomeRounds);
            }
            // MEETUBE: this upstream observable already walks ALL Home pages, including nested rows.
            // Collect before Browse filters/hides Shorts. No second launch walk or extra Home request.
            Observable<List<MediaGroup>> home = mContent.getHomeObserve()
                    .takeWhile(groups -> isCurrent(generation))
                    .doOnNext(groups -> collectHome(groups, generation))
                    .doFinally(() -> {
                        synchronized (mLock) {
                            if (generation == mGeneration) {
                                mHomeLoads--;
                                mLock.notifyAll();
                            }
                        }
                    });
            // MEETUBE: keep the launch's existing walk collecting when the user leaves Home early.
            // Retain only one batch (and errors while off-screen); later refreshes cancel normally.
            return firstWalk ? home.replay(1).autoConnect(1) : home;
        });
    }

    public Observable<MediaGroup> getShortsObserve() {
        return Observable.defer(() -> {
            Session session;
            FeedPage page;
            synchronized (mLock) {
                session = new Session(mGeneration);
                page = personalPage(session, 0);
            }
            if (page == null) {
                return stockFeed(session, 0); // MEETUBE: never wait for Home here
            }
            fetchAhead(page);
            return Observable.just(page);
        });
    }

    public Observable<MediaGroup> continueGroupObserve(MediaGroup group) {
        if (!(group instanceof FeedPage)) {
            // MEETUBE: preserve upstream concrete group types and continuation behaviour elsewhere.
            return mContent.continueGroupObserve(group);
        }
        FeedPage page = (FeedPage) group;
        Session session = page.session;
        return Observable.defer(() -> {
            if (!isCurrent(session.generation) || page.getNextPageKey() == null) {
                return Observable.empty();
            }
            if (page.source != null) {
                FeedPage personal;
                synchronized (mLock) {
                    personal = personalPage(session, page.personalCount);
                }
                if (personal != null) { // the account's shorts arrived while the stock feed was showing
                    fetchAhead(personal);
                    return Observable.just(personal);
                }
                return stockNext(page.source, session, page.personalCount, EMPTY_STOCK_PAGES);
            }
            return Maybe.fromCallable(() -> nextPersonalPage(session, page.personalCount))
                    .subscribeOn(mWorker)
                    .toObservable()
                    .cast(MediaGroup.class)
                    .switchIfEmpty(stockFeed(session, page.personalCount))
                    .observeOn(AndroidSchedulers.mainThread())
                    .filter(more -> isCurrent(session.generation));
        });
    }

    @Override
    public void onAccountChanged(Account account) {
        synchronized (mLock) {
            // MEETUBE: old pages/in-flight Home responses cannot seed a different account's feed.
            mGeneration++;
            mShorts.clear();
            mRows.clear();
            mRowKeys.clear();
            mHomeLoads = mHomeRounds = mRoundAdded = mAheadTarget = 0;
            mLock.notifyAll();
        }
    }

    private void collectHome(List<MediaGroup> groups, int generation) {
        synchronized (mLock) {
            if (generation != mGeneration || groups == null || !isSignedIn()) {
                return;
            }
            for (MediaGroup group : groups) {
                List<MediaItem> items = group != null ? group.getMediaItems() : null;
                if (items == null || items.isEmpty()) {
                    continue;
                }
                boolean shortsRow = true;
                for (MediaItem item : items) {
                    if (!isAd(item) && (item == null || !item.isShorts() || item.getVideoId() == null)) {
                        shortsRow = false;
                        break;
                    }
                }
                if (shortsRow) {
                    addShorts(items);
                    enqueue(group);
                }
            }
        }
    }

    /** Under mLock. */
    private int addShorts(List<MediaItem> items) {
        int added = 0;
        if (items != null) {
            for (MediaItem item : items) {
                if (item != null && item.isShorts() && item.getVideoId() != null && !isAd(item)
                        && !mShorts.containsKey(item.getVideoId())) {
                    mShorts.put(item.getVideoId(), item);
                    added++;
                }
            }
        }
        mRoundAdded += added;
        return added;
    }

    /** Under mLock. */
    private void enqueue(MediaGroup row) {
        if (row.getNextPageKey() != null && mRowKeys.add(row.getNextPageKey())) {
            mRows.add(row);
        }
    }

    /** Up to a page of the account's shorts from {@code from} on, none this feed has shown yet. Under mLock. */
    private FeedPage personalPage(Session session, int from) {
        List<MediaItem> items = new ArrayList<>();
        int index = 0;
        for (MediaItem item : mShorts.values()) {
            if (index++ < from) {
                continue;
            }
            if (session.shown.add(item.getVideoId())) {
                items.add(item);
                if (items.size() == PAGE_SIZE) {
                    break;
                }
            }
        }
        return items.isEmpty() ? null : new FeedPage(items, null, session, index);
    }

    /** On mWorker: the next page, fetching first if fewer than a page is ready. */
    private FeedPage nextPersonalPage(Session session, int from) throws InterruptedException {
        fill(from + PAGE_SIZE, session.generation, false);
        FeedPage page;
        synchronized (mLock) {
            page = session.generation == mGeneration ? personalPage(session, from) : null;
        }
        if (page != null) {
            fetchAhead(page);
        }
        return page; // null: none left, the stock feed takes over
    }

    /** MEETUBE: keeps the next pages fetched in the background, so a scroll or a swipe finds them ready. */
    private void fetchAhead(FeedPage page) {
        int target = page.personalCount + PAGE_SIZE * AHEAD_PAGES;
        int generation = page.session.generation;
        synchronized (mLock) {
            if (generation != mGeneration || target <= mAheadTarget || mShorts.size() >= target) {
                return;
            }
            mAheadTarget = target;
        }
        mWorker.scheduleDirect(() -> {
            try {
                fill(target, generation, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Log.e(TAG, "Shorts fetch ahead: %s", e.getMessage());
            }
        });
    }

    /**
     * On mWorker: fetches until the account has {@code target} shorts or nothing is left, one request at a time:
     * the Shorts rows' continuations in turn, then (signed in) a fresh Home for a new mix, a few rounds at most.
     */
    private void fill(int target, int generation, boolean ahead) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            MediaGroup row;
            synchronized (mLock) {
                if (generation != mGeneration || mShorts.size() >= target) {
                    return;
                }
                if (mHomeLoads > 0) {
                    // Home's own walk is still collecting: fetching ahead leaves it be, and a page that is
                    // asked for takes what is there or waits for the walk.
                    if (ahead) {
                        mAheadTarget = 0; // the next page served asks again
                        return;
                    }
                    if (mShorts.size() > target - PAGE_SIZE) {
                        return;
                    }
                    mLock.wait();
                    continue;
                }
                row = mRows.poll();
                if (row == null) {
                    if (!isSignedIn() || mHomeRounds >= HOME_ROUNDS || mRoundAdded == 0) {
                        return;
                    }
                    mHomeRounds++;
                    mRoundAdded = 0;
                }
            }
            try {
                if (row != null) {
                    MediaGroup more = mContent.continueGroup(row);
                    synchronized (mLock) {
                        if (generation == mGeneration && more != null && addShorts(more.getMediaItems()) > 0) {
                            enqueue(more); // behind the other rows; a page of repeats ends the row
                        }
                    }
                } else {
                    // The public Home API owns page traversal; it exposes no outer continuation key.
                    mContent.getHomeObserve().takeWhile(groups -> isCurrent(generation))
                            .blockingForEach(groups -> collectHome(groups, generation));
                }
            } catch (RuntimeException error) {
                Log.e(TAG, "Shorts fetch: %s", error.getMessage());
                synchronized (mLock) {
                    if (row == null) {
                        mHomeRounds = HOME_ROUNDS;
                    }
                }
            }
        }
        throw new InterruptedException();
    }

    private Observable<MediaGroup> stockFeed(Session session, int personalCount) {
        return Observable.defer(() -> {
            if (!isCurrent(session.generation)) {
                return Observable.empty();
            }
            // MEETUBE: stock may emit a subscriptions group THEN a reel group. Keep both and the
            // reel's real continuation, including for the player's firstElement() consumer.
            return mContent.getShortsObserve().toList().toObservable()
                    .flatMap(groups -> stockPage(groups, session, personalCount, EMPTY_STOCK_PAGES));
        });
    }

    private Observable<MediaGroup> stockNext(MediaGroup source, Session session, int personalCount, int emptyPages) {
        return mContent.continueGroupObserve(source)
                .flatMap(more -> stockPage(java.util.Collections.singletonList(more), session, personalCount, emptyPages));
    }

    private Observable<MediaGroup> stockPage(List<MediaGroup> groups, Session session, int personalCount, int emptyPages) {
        List<MediaItem> items = new ArrayList<>();
        MediaGroup source = null;
        synchronized (mLock) {
            if (session.generation != mGeneration) {
                return Observable.empty();
            }
            for (MediaGroup group : groups) {
                source = group;
                if (group.getMediaItems() != null) {
                    for (MediaItem item : group.getMediaItems()) {
                        if (item != null && item.getVideoId() != null && !isAd(item) && session.shown.add(item.getVideoId())) {
                            items.add(item);
                        }
                    }
                }
            }
        }
        if (source == null) {
            return Observable.empty();
        }
        // MEETUBE: an ad-only (or already shown) page must not strand the player's queue or loop unbounded.
        if (items.isEmpty()) {
            return source.getNextPageKey() != null && emptyPages > 0 ?
                    stockNext(source, session, personalCount, emptyPages - 1) : Observable.empty();
        }
        return Observable.just(new FeedPage(items, source, session, personalCount));
    }

    private boolean isSignedIn() {
        return MediaServiceManager.instance().getSelectedAccount() != null;
    }

    private boolean isCurrent(int generation) {
        synchronized (mLock) {
            return generation == mGeneration;
        }
    }

    private static boolean isAd(MediaItem item) {
        // MEETUBE: reel details expose the uploader through secondTitle on this upstream version.
        return item != null && (startsWithAdChannel(item.getAuthor()) || startsWithAdChannel(item.getSecondTitle())
                || "Ad".equalsIgnoreCase(item.getBadgeText()) || "Sponsored".equalsIgnoreCase(item.getBadgeText()));
    }

    private static boolean startsWithAdChannel(CharSequence text) {
        return text != null && text.toString().startsWith("Video ad upload channel");
    }

    /** One opening of the feed (the Shorts grid or the player's fallback): what it has shown, so nothing repeats. */
    private static final class Session {
        private final int generation;
        private final Set<String> shown = new HashSet<>(); // under mLock

        Session(int generation) {
            this.generation = generation;
        }
    }

    // MEETUBE: keep upstream groups intact for instanceof-based dispatch; only these pages use our key.
    private static final class FeedPage implements MediaGroup {
        private final List<MediaItem> items;
        private final MediaGroup source; // the stock group behind a stock page, null on the account's pages
        private final Session session;
        private final int personalCount; // how far into the account's shorts the feed has got

        FeedPage(List<MediaItem> items, MediaGroup source, Session session, int personalCount) {
            this.items = items;
            this.source = source;
            this.session = session;
            this.personalCount = personalCount;
        }

        @Override public int getType() { return TYPE_SHORTS; }
        @Override public List<MediaItem> getMediaItems() { return items; }
        @Override public String getTitle() { return null; }
        @Override public String getChannelId() { return null; }
        @Override public String getParams() { return null; }
        @Override public String getReloadPageKey() { return null; }
        @Override public String getChannelUrl() { return null; }
        @Override public boolean isEmpty() { return items.isEmpty(); }
        @Override public String getNextPageKey() {
            return source != null && source.getNextPageKey() == null ? null :
                    PAGE_KEY + session.generation + ":" + (source != null ? "reel" : personalCount);
        }
    }
}
