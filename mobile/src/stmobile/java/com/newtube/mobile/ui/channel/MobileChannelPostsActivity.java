package com.newtube.mobile.ui.channel;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.liskovsoft.mediaserviceinterfaces.CommunityService;
import com.liskovsoft.mediaserviceinterfaces.data.CommunityPost;
import com.liskovsoft.mediaserviceinterfaces.data.CommunityPostPage;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileGrid;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.common.RefreshFeedback;
import com.newtube.mobile.ui.playback.CommentsPanel;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.signin.MobileWebSignInActivity;
import com.newtube.mobile.ui.signin.WebSignInSheet;
import com.newtube.mobile.ui.signin.WebSignInState;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import io.reactivex.rxjava3.disposables.Disposable;

/** A public WEB feed, independent of the channel presenter's TV tabs and sign-in state. */
public class MobileChannelPostsActivity extends MobileActivity implements MiniPlayerBridge.MiniHost {
    private static final String CHANNEL_ID = "posts_channel_id";
    private static final String CHANNEL_NAME = "posts_channel_name";
    private CommunityService mService;
    private CommunityPostAdapter mAdapter;
    private RecyclerView mList;
    private LinearLayoutManager mLayout;
    private SwipeRefreshLayout mSwipe;
    private ProgressBar mProgress;
    private PageLoadState mState;
    private LoadMoreFailureAdapter mFooter;
    private MobileMiniPlayerController mMiniPlayer;
    private Disposable mRequest;
    private String mChannelId;
    private String mNext;
    private boolean mLoading;
    private int mGeneration;
    private final Set<String> mLoadedTokens = new HashSet<>();
    private CommentsPanel mComments;
    /** The post whose comments are open: a comment posted there raises its card's count. */
    private CommunityPost mCommentsPost;
    /** Signed in on the web: the page is fetched again as that account, so its likes work. */
    private final androidx.activity.result.ActivityResultLauncher<Intent> mWebSignIn = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) load(true);
            });

    private void openComments(CommunityPost post) {
        if (post.commentsParams == null) return;
        mCommentsPost = post;
        mComments.setSource(post.id, "post:detail:" + post.commentsParams, null, post.replyCountText);
        mComments.open();
    }

    private void like(CommunityPost post) {
        if (!WebSignInState.isSignedIn(this)) {
            WebSignInSheet.show(this, () -> mWebSignIn.launch(new Intent(this, MobileWebSignInActivity.class)));
            return;
        }
        boolean liked = mAdapter.isLiked(post);
        String action = liked ? post.unlikeAction : post.likeAction;
        if (action == null) {
            MobileSnackbar.show(this, R.string.mobile_posts_like_failed);
            return;
        }
        com.newtube.mobile.ui.common.Haptics.click(getWindow().getDecorView());
        mAdapter.setLiked(post, !liked);
        RxHelper.execute(mService.postActionObserve(action), ignored -> { }, error -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            mAdapter.setLiked(post, liked);
            MobileSnackbar.show(this, R.string.mobile_posts_like_failed);
        }));
    }

    static void open(Context context, String channelId, String name) {
        context.startActivity(new Intent(context, MobileChannelPostsActivity.class)
                .putExtra(CHANNEL_ID, channelId).putExtra(CHANNEL_NAME, name));
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_mobile_channel_posts);
        // The mini player reorders its last host through ViewManager.
        // With a parent: without one, addTop() clears the back stack and Back closes the app.
        ViewManager.instance(this).register(MobileChannelPostsActivity.class, MobileChannelPostsActivity.class,
                com.newtube.mobile.ui.browse.MobileBrowseActivity.class);
        registerBackHandler(this::finish);
        mChannelId = getIntent().getStringExtra(CHANNEL_ID);
        String name = getIntent().getStringExtra(CHANNEL_NAME);
        ((TextView) findViewById(R.id.mobile_posts_title)).setText(getString(R.string.mobile_posts_title,
                name != null ? name : getString(R.string.header_channels)));
        findViewById(R.id.mobile_posts_back).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        mService = YouTubeServiceManager.instance().getCommunityService();
        mList = findViewById(R.id.mobile_posts_list);
        mSwipe = findViewById(R.id.mobile_posts_swipe);
        mProgress = findViewById(R.id.mobile_posts_progress);
        mState = new PageLoadState(findViewById(R.id.mobile_page_load_state), () -> load(true));
        mFooter = new LoadMoreFailureAdapter(() -> load(false));
        mAdapter = new CommunityPostAdapter(this::openLink, this::openComments, this::like);
        mComments = new CommentsPanel(this, findViewById(R.id.mobile_comments_panel), new CommentsPanel.Host() {
            @Override public void onCommentTimestamp(long positionMs) { }
            @Override public void onCommentVideoLink(String videoId) {
                PlaybackPresenter.instance(MobileChannelPostsActivity.this).openVideo(videoId);
            }
            @Override public void onCommentsPanelShown(boolean shown) { }
            @Override public void onCommentsCountChanged(String count) {
                if (mCommentsPost != null) mAdapter.setReplies(mCommentsPost, count);
            }
        });
        mComments.setReadOnly();
        mLayout = new LinearLayoutManager(this);
        mList.setLayoutManager(mLayout);
        mList.setAdapter(new ConcatAdapter(mAdapter, mFooter));
        mList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // Keep a vertical feed at the width of one sibling grid column on tablets.
            int columns = MobileGrid.computeSpanCount(this);
            int spacing = getResources().getDimensionPixelSize(R.dimen.mobile_card_spacing);
            int padding = ((r - l) - (r - l) / columns) / 2 + spacing;
            if (mList.getPaddingLeft() != padding) {
                mList.setPadding(padding, mList.getPaddingTop(), padding, mList.getPaddingBottom());
            }
        });
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                maybeLoadMore();
            }
        });
        mSwipe.setOnRefreshListener(() -> {
            RefreshFeedback.play(mSwipe);
            load(true);
        });
        mMiniPlayer = new MobileMiniPlayerController(this);
        load(true);
    }

    private void load(boolean first) {
        if (!first && (mLoading || mNext == null)) return;
        if (mRequest != null) mRequest.dispose();
        int generation = ++mGeneration;
        String token = first ? null : mNext;
        mLoading = true;
        mFooter.setFailed(false);
        mState.hide();
        mList.setVisibility(View.VISIBLE);
        mProgress.setVisibility(mSwipe.isRefreshing() ? View.GONE : View.VISIBLE);
        if (mChannelId == null || mChannelId.isEmpty()) {
            failed(first, LoadFailure.ERROR);
            return;
        }
        mRequest = RxHelper.execute(first ? mService.getChannelPostsObserve(mChannelId)
                        : mService.continueChannelPostsObserve(token),
                page -> runOnUiThread(() -> {
                    if (generation != mGeneration || isDestroyed()) return;
                    loaded(first, token, page);
                }), error -> runOnUiThread(() -> {
                    if (generation != mGeneration || isDestroyed()) return;
                    failed(first, LoadFailure.classify(this, error));
                }));
    }

    private void loaded(boolean first, String token, CommunityPostPage page) {
        int generation = mGeneration;
        mProgress.setVisibility(View.GONE);
        mSwipe.setRefreshing(false);
        String name = getIntent().getStringExtra(CHANNEL_NAME);
        if (first && !page.posts.isEmpty() && (name == null || name.startsWith("@")
                || name.equals(getString(R.string.header_channels)))) {
            String author = page.posts.get(0).author;
            if (author != null) ((TextView) findViewById(R.id.mobile_posts_title))
                    .setText(getString(R.string.mobile_posts_title, author));
        }
        if (first) mLoadedTokens.clear();
        if (token != null) mLoadedTokens.add(token);
        mNext = mLoadedTokens.contains(page.nextPageToken) ? null : page.nextPageToken;
        ArrayList<CommunityPost> posts = first ? new ArrayList<>() : new ArrayList<>(mAdapter.getCurrentList());
        Set<String> ids = new HashSet<>();
        for (CommunityPost post : posts) ids.add(post.id);
        for (CommunityPost post : page.posts) if (ids.add(post.id)) posts.add(post);
        mAdapter.submitList(posts, () -> {
            if (generation != mGeneration || isDestroyed()) return;
            mLoading = false;
            if (posts.isEmpty() && mNext == null) showState(LoadFailure.EMPTY);
            mList.post(this::maybeLoadMore);
        });
    }

    private void failed(boolean first, int state) {
        mLoading = false;
        mProgress.setVisibility(View.GONE);
        mSwipe.setRefreshing(false);
        if (!first) {
            mFooter.setFailed(true);
        } else if (mAdapter.getItemCount() == 0) {
            showState(state);
        } else {
            MobileSnackbar.show(this, state == LoadFailure.NO_CONNECTION
                    ? R.string.mobile_empty_no_connection : R.string.mobile_refresh_error);
        }
    }

    private void showState(int state) {
        mList.setVisibility(View.GONE);
        mState.show(state);
        if (state == LoadFailure.EMPTY) {
            ((TextView) findViewById(R.id.mobile_page_load_state_message)).setText(R.string.mobile_posts_empty);
        }
    }

    private void maybeLoadMore() {
        if (mLoading || mNext == null || mFooter.isFailed()) return;
        if (mLayout.findLastVisibleItemPosition() >= mAdapter.getItemCount() - 4) {
            // A skipped repost page must still reach its continuation. Repeated tokens stop in loaded().
            load(false);
        }
    }

    private void openLink(CommunityPost.TextRun run) {
        if (run.videoId != null) {
            PlaybackPresenter.instance(this).openVideo(run.videoId);
        } else if (run.channelId != null && (run.channelId.startsWith("UC") || run.channelId.startsWith("@"))) {
            ChannelPresenter.instance(this).openChannel(run.channelId);
        } else if (run.url != null) {
            Uri uri = Uri.parse(run.url.startsWith("//") ? "https:" + run.url
                    : run.url.startsWith("/") ? "https://www.youtube.com" + run.url : run.url);
            String host = uri.getHost();
            boolean youtube = "youtube.com".equals(host) || (host != null && host.endsWith(".youtube.com"));
            if (youtube && "/redirect".equals(uri.getPath()) && uri.getQueryParameter("q") != null) {
                openLink(new CommunityPost.TextRun(run.text, uri.getQueryParameter("q"), null, null));
                return;
            }
            String videoId = youtube ? uri.getQueryParameter("v")
                    : "youtu.be".equals(host) ? uri.getLastPathSegment() : null;
            if (videoId == null && youtube && uri.getPathSegments().size() >= 2) {
                String type = uri.getPathSegments().get(0);
                if ("shorts".equals(type) || "live".equals(type) || "embed".equals(type)) {
                    videoId = uri.getPathSegments().get(1);
                }
            }
            if (videoId != null) {
                PlaybackPresenter.instance(this).openVideo(videoId);
            } else if (youtube && uri.getPathSegments().size() >= 2 && "channel".equals(uri.getPathSegments().get(0))) {
                ChannelPresenter.instance(this).openChannel(uri.getPathSegments().get(1));
            } else if (youtube && !uri.getPathSegments().isEmpty() && uri.getPathSegments().get(0).startsWith("@")) {
                ChannelPresenter.instance(this).openChannel(uri.getPathSegments().get(0));
            } else if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                // URLSpan uses ACTION_VIEW as well, matching links in the description sheet.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (android.content.ActivityNotFoundException ignored) { }
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        MiniPlayerBridge.registerMiniHost(this);
        if (mMiniPlayer != null) mMiniPlayer.sync(false);
    }

    @Override protected void onPause() {
        if (mMiniPlayer != null) mMiniPlayer.onHostPause();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        if (mMiniPlayer != null) mMiniPlayer.onHostStop();
    }

    @Override protected void onDestroy() {
        ++mGeneration;
        if (mRequest != null) mRequest.dispose();
        if (mComments != null) mComments.release();
        MiniPlayerBridge.unregisterMiniHost(this);
        super.onDestroy();
    }

    @Override public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        return mMiniPlayer != null && mMiniPlayer.prepareForHandoff(onDrawn);
    }

    @Override public Class<?> getMiniHostViewClass() { return MobileChannelPostsActivity.class; }
    @Override public int getMiniCardBottomOffsetPx() { return 0; }
}
