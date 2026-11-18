package com.newtube.mobile.ui.meetune;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Glass;
import com.newtube.mobile.ui.common.GlassNavBar;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;
import com.newtube.mobile.ui.signin.MobileWebSignInActivity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * MEETUNE: the music screen. Four tabs on MeeTube's glass bar (Home, Explore, Library, Search);
 * a card opens its album, playlist, artist or mood page over the tab, Back steps out again.
 * A song plays in MeeTube's player in its music look ({@link MeeTune#play}); minimized, it docks
 * here as the usual mini card.
 */
public class MeeTuneActivity extends MobileActivity implements MiniPlayerBridge.MiniHost, MeeTuneAdapter.Host {
    private static final int HOME = 0;
    private static final int EXPLORE = 1;
    private static final int LIBRARY = 2;
    private static final int SEARCH = 3;

    private static final int SORT_ADDED = 0;
    private static final int SORT_DEFAULT = 1;
    private static final int SORT_AZ = 2;
    private static final int SORT_ZA = 3;

    /** A page on the stack: what it loads and, once loaded, its rows and scroll. */
    private static final class Page {
        final int tab;
        String title;
        String browseId;
        String params;
        /** The page's cover, for the blurred backdrop: the tapped card's, then the head's. */
        String art;
        /** MeeTuneSettings.SETTINGS or EQUALIZER; 0 for music. */
        int settings;
        List<MeeTuneAdapter.Row> rows;
        /** Its list's order (SORT_*) and the find box's words; the head's own queue before either. */
        int sort = SORT_DEFAULT;
        String find = "";
        List<MusicApi.Item> queue;
        android.os.Parcelable scroll;
        int generation;

        Page(int tab) {
            this.tab = tab;
        }
    }

    private final Page[] mTabs = {new Page(HOME), new Page(EXPLORE), new Page(LIBRARY), new Page(SEARCH)};
    private final Deque<Page> mStack = new ArrayDeque<>();
    private int mTab = HOME;
    private int mGeneration;
    private RecyclerView mList;
    private MeeTuneAdapter mAdapter;
    private View mProgress;
    private View mMessage;
    private TextView mMessageText;
    private TextView mMessageAction;
    private TextView mTitle;
    private View mBack;
    private View mSearchGlass;
    private EditText mSearchField;
    private View mSettings;
    private View mSort;
    private View mTitleSpace;
    private GlassNavBar mNavBar;
    private BottomNavigationView mNav;
    private MobileMiniPlayerController mMiniPlayer;
    private MeeTuneMiniPill mPill;

    private final ActivityResultLauncher<Intent> mSignIn = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) MeeTune.setNoAccount(this, false); // signed in: speaks as it
                showAvatar();
                for (Page page : mTabs) page.rows = null;
                show(current(), true);
            });

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_meetune);
        com.newtube.mobile.ui.common.Motion.frostIn(findViewById(android.R.id.content)); // comes in out of MeeTube's blur
        // The mini card reorders its last host through ViewManager; Back from here is MeeTube.
        ViewManager.instance(this).register(MeeTuneActivity.class, MeeTuneActivity.class,
                com.newtube.mobile.ui.browse.MobileBrowseActivity.class);
        registerBackHandler(this::back);

        mList = findViewById(R.id.meetune_list);
        mProgress = findViewById(R.id.meetune_progress);
        mMessage = findViewById(R.id.meetune_message);
        mMessageText = findViewById(R.id.meetune_message_text);
        mMessageAction = findViewById(R.id.meetune_message_action);
        mTitle = findViewById(R.id.meetune_title);
        mBack = findViewById(R.id.meetune_back);
        mSearchGlass = findViewById(R.id.meetune_search_glass);
        mSearchField = findViewById(R.id.meetune_search_field);
        mSettings = findViewById(R.id.meetune_settings);
        mSort = findViewById(R.id.meetune_sort);
        mTitleSpace = findViewById(R.id.meetune_title_space);
        mNav = findViewById(R.id.meetune_nav);

        View source = findViewById(R.id.meetune_blur);
        for (int id : new int[] {R.id.meetune_nav_glass, R.id.meetune_back, R.id.meetune_search_glass,
                R.id.meetune_settings, R.id.meetune_sort, R.id.meetune_brand, R.id.meetune_mini, R.id.meetune_mini_tab,
                R.id.meetune_mini_search}) {
            Glass.blur(findViewById(id), source);
        }
        com.newtube.mobile.ui.common.GlassView topFrost = findViewById(R.id.meetune_top_frost);
        topFrost.setBlur(24f);
        Glass.blur(topFrost, source);

        mAdapter = new MeeTuneAdapter(this);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setAdapter(mAdapter);
        mList.addItemDecoration(mAdapter.dividers(this));
        // Home's pull to refresh: a new Home from YouTube Music, spinner under the search bar.
        mSwipe = findViewById(R.id.meetune_swipe);
        blendIntoStatusBar(topFrost);
        mSwipe.setOnRefreshListener(() -> {
            com.newtube.mobile.ui.common.RefreshFeedback.play(mSwipe);
            if (current() == mTabs[HOME]) show(mTabs[HOME], true);
            else mSwipe.setRefreshing(false);
        });
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                followBackdrop();
                // The top bar frosts in over the first 48dp of scroll; at the top of a page it's clear.
                topFrost.setAlpha(Math.min(1f, view.computeVerticalScrollOffset() / (float) MeeTuneAdapter.dp(MeeTuneActivity.this, 48)));
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            float blur = MeeTuneAdapter.dp(this, 40);
            findViewById(R.id.meetune_backdrop).setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP));
        }

        mBack.setOnClickListener(v -> {
            com.newtube.mobile.ui.common.Motion.pop(v);
            back();
        });
        findViewById(R.id.meetune_brand).setOnClickListener(v -> { // Home's brand pill: back to MeeTube
            Haptics.click(v);
            com.newtube.mobile.ui.common.Motion.nudge(v);
            MeeTune.leave(this);
        });
        mSort.setOnClickListener(v -> {
            Haptics.click(v);
            com.newtube.mobile.ui.common.Motion.pop(v);
            showSortMenu();
        });
        mSettings.setOnClickListener(v -> {
            Haptics.click(v);
            com.newtube.mobile.ui.common.Motion.pop(v);
            showAccountMenu();
        });
        View clear = findViewById(R.id.meetune_search_clear);
        clear.setOnClickListener(v -> { // clear the query, keep typing, as MeeTube's Search
            mSearchField.setText(null);
            typeSearch();
        });
        mSuggestions = findViewById(R.id.meetune_suggestions);
        mTagAdapter = new com.newtube.mobile.ui.search.SearchTagAdapter(tag -> {
            mSearchField.setText(tag.tag);
            search(tag.tag);
        }, tag -> false, tag -> { // the NW arrow: into the field to refine, as MeeTube's Search
            mSearchField.setText(tag.tag);
            mSearchField.setSelection(tag.tag.length());
        });
        mSuggestSongs = new MeeTuneAdapter(this); // the songs, albums and artists YouTube Music suggests, under the words
        mSuggestions.setAdapter(new androidx.recyclerview.widget.ConcatAdapter(mTagAdapter, mSuggestSongs));
        // Tapping back into the field brings the suggestions back for its text (MeeTube's Search).
        mSearchField.setOnFocusChangeListener((v, focused) -> {
            if (focused) loadSuggestions();
        });
        mSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                // One suggest call per burst of keys; an emptied field shows recent searches at once.
                mSearchField.removeCallbacks(mSuggestReload);
                if (s.length() == 0) {
                    clearResults();
                    loadSuggestions();
                } else mSearchField.postDelayed(mSuggestReload, 200);
            }
        });
        mSearchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(v.getText().toString().trim());
                return true;
            }
            return false;
        });

        Menu menu = mNav.getMenu();
        menu.add(Menu.NONE, HOME, 0, R.string.meetune_home).setIcon(R.drawable.ic_nav_home);
        menu.add(Menu.NONE, EXPLORE, 1, R.string.meetune_explore).setIcon(R.drawable.ic_meetune_explore);
        menu.add(Menu.NONE, LIBRARY, 2, R.string.meetune_library).setIcon(R.drawable.ic_meetune_library);
        menu.add(Menu.NONE, SEARCH, 3, R.string.meetune_search).setIcon(R.drawable.ic_mobile_search);
        // MeeTube's own bar: its lens, glow, drag and tab-name setting (Settings > Tabs, MeeTube's).
        mNavBar = new GlassNavBar(this, findViewById(R.id.meetune_nav_glass), mNav,
                findViewById(R.id.meetune_nav_lens), new GlassNavBar.Listener() {
                    @Override
                    public void onChosen(int itemId) {
                        switchTab(itemId);
                    }

                    @Override
                    public void onReselected(int itemId) {
                        if (!mStack.isEmpty()) {
                            mStack.clear();
                            show(current(), false);
                        } else {
                            mList.smoothScrollToPosition(0);
                        }
                    }
                });

        mMiniPlayer = new MobileMiniPlayerController(this);
        mPill = new MeeTuneMiniPill(this, findViewById(R.id.meetune_nav_glass), mNav, mList,
                () -> mNav.setSelectedItemId(SEARCH));
        // The card floats over the bar, not under it.
        View nav = findViewById(R.id.meetune_nav_glass);
        nav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeMiniCard());

        show(current(), false);
        MeeTune.sHistoryChanged = mHistoryChanged;
        sOpen = new java.lang.ref.WeakReference<>(this);
    }

    /** MeeTune's screen while it is open, so Convert to video can close it (Back then lands in MeeTube). */
    @Nullable
    private static java.lang.ref.WeakReference<MeeTuneActivity> sOpen;

    /** Closes MeeTune's screen under the player: the video converted to is MeeTube's now, and so is Back. */
    static void closeUnderPlayer() {
        MeeTuneActivity open = sOpen != null ? sOpen.get() : null;
        if (open == null || open.isFinishing() || open.isDestroyed()) return;
        // After the player is up, so MeeTube never flashes between them.
        open.getWindow().getDecorView().postDelayed(() -> {
            if (!open.isFinishing()) open.finish();
        }, 700);
    }

    private void placeMiniCard() {
        View card = findViewById(R.id.mobile_mini_player);
        if (card == null) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        int bottom = getMiniCardBottomOffsetPx();
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom;
            card.setLayoutParams(params);
        }
    }

    /** A song's download was deleted: the Downloads page, when it shows, lists the rest. */
    /** A song just went into History: an open History page shows it, the Library's card counts it. */
    private final Runnable mHistoryChanged = () -> {
        Page page = current();
        if (page == null || isFinishing()) return;
        if (MeeTune.HISTORY.equals(page.browseId)) {
            load(page, true);
        } else if (page.tab == LIBRARY && mStack.isEmpty() && page.rows != null) {
            countLocalCards(page);
            mAdapter.notifyDataSetChanged();
        }
    };

    /** A download moved on: an open Downloads page shows each song's progress and speed as it comes. */
    private final com.newtube.mobile.downloads.DownloadRegistry.Listener mDownloadProgress = () -> {
        Page page = current();
        if (page == null || page.rows == null || isFinishing() || !MeeTune.DOWNLOADS.equals(page.browseId)) return;
        for (MeeTuneAdapter.Row row : page.rows) {
            if (row.item == null || row.item.videoId == null) continue;
            com.newtube.mobile.downloads.DownloadItem download = MeeTune.songDownload(this, row.item.videoId);
            if (download != null) MeeTune.fillDownload(this, row.item, download);
        }
        mAdapter.notifyItemRangeChanged(0, mAdapter.getItemCount(), MeeTuneAdapter.PROGRESS);
    };

    void downloadsChanged() {
        Page page = current();
        if (page != null && MeeTune.DOWNLOADS.equals(page.browseId)) load(page, true);
    }

    private Page current() {
        return mStack.isEmpty() ? mTabs[mTab] : mStack.peek();
    }

    private void switchTab(int tab) {
        saveScroll();
        mStack.clear();
        boolean opening = tab == SEARCH && mTab != SEARCH;
        if (opening) {
            com.newtube.mobile.ui.search.SiriGlow.play(this); // MeeTube's Search glow, as its Search opens
        }
        mTab = tab;
        show(current(), false);
        if (opening) typeSearch(); // straight to typing: the keyboard and the suggestions come up
    }

    /** The search field focused with the keyboard up; its focus brings up the suggestions. */
    private void typeSearch() {
        mSearchField.requestFocus();
        mSearchField.post(() -> { // once the field shows (it was gone off Search)
            InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null) input.showSoftInput(mSearchField, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private com.newtube.mobile.ui.common.HapticSwipeRefreshLayout mSwipe;
    private RecyclerView mSuggestions;
    private com.newtube.mobile.ui.search.SearchTagAdapter mTagAdapter;
    private MeeTuneAdapter mSuggestSongs;
    /** Suggestions arrive out of order on a slow network: only the newest request's show. */
    private int mSuggestGeneration;
    private final Runnable mSuggestReload = this::loadSuggestions;

    private void loadSuggestions() {
        String query = mSearchField.getText().toString().trim();
        int generation = ++mSuggestGeneration;
        MeeTune.run(this, () -> MusicApi.suggestions(this, query), (found, error) -> {
            if (generation != mSuggestGeneration) return;
            List<com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag> tags = new ArrayList<>();
            List<MusicApi.Shelf> songs = new ArrayList<>();
            if (found != null) {
                for (String text : found.words) tags.add(new com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag(text));
                songs.add(found.songs);
            }
            mTagAdapter.setHistoryMode(query.isEmpty());
            mTagAdapter.setTags(tags);
            mSuggestSongs.setRows(MeeTuneAdapter.searchRows(songs));
            // Only while the field is typed in: a late answer never covers results searched since.
            boolean show = (!tags.isEmpty() || mSuggestSongs.getItemCount() > 0)
                    && mSearchField.hasFocus() && mSearchGlass.getVisibility() == View.VISIBLE;
            mSuggestions.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    /** The suggestions and the keyboard away: a search ran, Back, or another page. */
    private void closeSuggestions() {
        mSearchField.removeCallbacks(mSuggestReload);
        mSuggestGeneration++; // an answer on its way is moot now
        mSuggestions.setVisibility(View.GONE);
        hideKeyboard();
        mSearchField.clearFocus();
    }

    private void push(Page page) {
        saveScroll();
        mStack.push(page);
        show(page, false);
    }

    private void back() {
        if (mSuggestions.getVisibility() == View.VISIBLE) { // first Back puts the suggestions away
            closeSuggestions();
        } else if (!mStack.isEmpty()) {
            mStack.pop();
            show(current(), false);
        } else if (mTab != HOME) {
            mNavBar.select(HOME);
            switchTab(HOME);
        } else {
            // Back out of Home leaves the app with the song playing; only the brand pill goes to MeeTube.
            moveTaskToBack(true);
        }
    }

    private void saveScroll() {
        Page page = current();
        if (mList.getLayoutManager() != null) page.scroll = mList.getLayoutManager().onSaveInstanceState();
    }

    // ----------------------------------------------------------------------------- pages

    private void show(Page page, boolean reload) {
        if (mPill != null) mPill.unfold(); // a new page: the bar comes back
        boolean pushed = !mStack.isEmpty();
        mSwipe.setEnabled(!pushed && page.tab == HOME); // only Home pulls to refresh
        if (!reload) mSwipe.setRefreshing(false);
        boolean search = !pushed && page.tab == SEARCH;
        mBack.setVisibility(pushed ? View.VISIBLE : View.GONE);
        mSearchGlass.setVisibility(search ? View.VISIBLE : View.GONE);
        boolean brand = !pushed && page.tab == HOME;
        findViewById(R.id.meetune_brand).setVisibility(brand ? View.VISIBLE : View.GONE);
        if (brand) showBrand();
        syncMeeTubeHint(brand);
        mTitle.setVisibility(search || brand ? View.GONE : View.VISIBLE);
        mTitleSpace.setVisibility(search ? View.GONE : View.VISIBLE);
        mTitle.setText(pushed ? (page.title != null ? page.title : "") : getString(tabTitle(page.tab)));
        boolean home = !pushed && page.tab == HOME;
        mSettings.setVisibility(home || search ? View.VISIBLE : View.GONE); // the avatar beside the search bar too
        mSort.setVisibility(View.GONE); // until a list that sorts has loaded
        if (!search) closeSuggestions();
        showBackdrop(pushed ? page.art : null); // up while the page loads too

        if (page.settings != 0) { // built fresh each time: a value set on the page above shows here
            List<MeeTuneAdapter.Row> rows = new ArrayList<>();
            rows.add(MeeTuneAdapter.viewRow(MeeTuneSettings.page(this, page.settings, mSettingsHost)));
            page.rows = rows;
            render(page);
            return;
        }
        if (MeeTune.HISTORY.equals(page.browseId)) reload = true; // the songs played since it was last open
        if (page.rows != null && !reload && page.tab == LIBRARY && !pushed) countLocalCards(page);
        if (page.rows != null && !reload) {
            render(page);
            return;
        }
        if (search) {
            if (page.rows == null) {
                mAdapter.setRows(new ArrayList<>());
                message(getString(R.string.meetune_search_empty), null, null);
            } else {
                render(page);
            }
            return;
        }
        if (!pushed && page.tab == LIBRARY && !MusicApi.signedIn(this)) {
            mAdapter.setRows(new ArrayList<>());
            message(getString(R.string.meetune_library_signed_out), getString(R.string.meetune_sign_in),
                    () -> mSignIn.launch(new Intent(this, MobileWebSignInActivity.class)));
            return;
        }
        load(page, reload);
    }

    private static int tabTitle(int tab) {
        switch (tab) {
            case EXPLORE: return R.string.meetune_explore;
            case LIBRARY: return R.string.meetune_library;
            case SEARCH: return R.string.meetune_search;
            default: return R.string.meetune_name;
        }
    }

    private void load(Page page, boolean force) {
        int generation = ++mGeneration;
        page.generation = generation;
        mAdapter.setRows(new ArrayList<>());
        mMessage.setVisibility(View.GONE);
        boolean pushed = !mStack.isEmpty();
        int tab = page.tab;
        MeeTune.Result<List<MeeTuneAdapter.Row>> done = (rows, error) -> {
            if (generation != mGeneration) return; // the page was left before it loaded
            mProgress.setVisibility(View.GONE);
            mSwipe.setRefreshing(false);
            if (rows == null) {
                message(getString(R.string.meetune_error), getString(R.string.meetune_retry), () -> show(page, true));
                return;
            }
            page.rows = rows;
            render(page);
        };
        if (!pushed && tab == HOME) { // usually loaded before MeeTune opened: no spinner at all
            boolean[] landed = {false};
            MeeTune.home(this, force, rows -> {
                landed[0] = true;
                done.done(rows, null);
            });
            if (!landed[0] && !mSwipe.isRefreshing()) mProgress.setVisibility(View.VISIBLE);
            return;
        }
        mProgress.setVisibility(View.VISIBLE);
        MeeTune.run(this, () -> {
            if (pushed && MeeTune.HISTORY.equals(page.browseId)) {
                return MeeTuneAdapter.rows(MeeTune.history(this), true, false);
            }
            if (pushed && MeeTune.DOWNLOADS.equals(page.browseId)) {
                return MeeTuneAdapter.rows(MeeTune.downloads(this), true, false);
            }
            if (pushed && MeeTune.CACHED.equals(page.browseId)) {
                return MeeTuneAdapter.rows(MeeTuneSongCache.songs(this), true, false);
            }
            if (pushed) {
                return MeeTuneAdapter.rows(MusicApi.browse(this, page.browseId, page.params), true, false);
            }
            return tab == EXPLORE ? MeeTuneAdapter.rows(MusicApi.browse(this, "FEmusic_explore", null), false, false)
                    : grid(MeeTuneAdapter.rows(library(), false, false));
        }, done);
    }

    /** The Library's card rows as grids: every card on screen, three a row. */
    private static List<MeeTuneAdapter.Row> grid(List<MeeTuneAdapter.Row> rows) {
        for (MeeTuneAdapter.Row row : rows) {
            if (row.type == MeeTuneAdapter.CAROUSEL && row.style == MeeTuneAdapter.TILE) row.style = MeeTuneAdapter.GRID;
        }
        return rows;
    }

    /** The Library from memory: its Downloads and History cards still count what is on the phone now. */
    private void countLocalCards(Page page) {
        for (MeeTuneAdapter.Row row : page.rows) {
            if (row.shelf == null) continue;
            for (MusicApi.Item item : row.shelf.items) {
                if (MeeTune.HISTORY.equals(item.browseId)) {
                    List<MusicApi.Shelf> played = MeeTune.history(this);
                    int n = played.isEmpty() ? 0 : played.get(0).items.size();
                    item.subtitle = getResources().getQuantityString(R.plurals.meetune_history_count, n, n);
                } else if (MeeTune.DOWNLOADS.equals(item.browseId)) {
                    List<MusicApi.Shelf> saved = MeeTune.downloads(this);
                    int n = saved.isEmpty() ? 0 : saved.get(0).items.size();
                    item.subtitle = getResources().getQuantityString(R.plurals.meetune_download_count, n, n);
                } else if (MeeTune.CACHED.equals(item.browseId)) {
                    item.subtitle = getString(R.string.meetune_cached_summary);
                }
            }
        }
    }

    private List<MusicApi.Shelf> library() throws Exception {
        List<MusicApi.Shelf> shelves;
        try {
            shelves = MusicApi.browse(this, "FEmusic_library_landing", null);
        } catch (Exception e) { // a weak spot: the cards on this phone still show, Cached songs among them
            shelves = new ArrayList<>();
        }
        MusicApi.Shelf top = new MusicApi.Shelf();
        MusicApi.Item downloads = new MusicApi.Item(); // the songs downloaded from MeeTune, on this phone
        downloads.kind = MusicApi.CARD;
        downloads.title = getString(R.string.meetune_downloads);
        downloads.browseId = MeeTune.DOWNLOADS;
        downloads.icon = R.drawable.ic_meetune_download;
        List<MusicApi.Shelf> saved = MeeTune.downloads(this);
        int count = saved.isEmpty() ? 0 : saved.get(0).items.size();
        downloads.subtitle = getResources().getQuantityString(R.plurals.meetune_download_count, count, count);
        MusicApi.Item history = new MusicApi.Item(); // the songs played in MeeTune, never MeeTube's History
        history.kind = MusicApi.CARD;
        history.title = getString(R.string.meetune_history);
        history.browseId = MeeTune.HISTORY;
        history.icon = R.drawable.ic_meetune_history;
        List<MusicApi.Shelf> played = MeeTune.history(this);
        int plays = played.isEmpty() ? 0 : played.get(0).items.size();
        history.subtitle = getResources().getQuantityString(R.plurals.meetune_history_count, plays, plays);
        MusicApi.Item cached = new MusicApi.Item(); // the songs whole in the cache: they play with no network
        cached.kind = MusicApi.CARD;
        cached.title = getString(R.string.meetune_cached);
        cached.browseId = MeeTune.CACHED;
        cached.icon = R.drawable.ic_settings_storage;
        cached.subtitle = getString(R.string.meetune_cached_summary);
        MusicApi.Shelf liked = null; // YouTube Music lists its own Liked Music now: no second one
        for (MusicApi.Shelf shelf : shelves) {
            for (MusicApi.Item item : shelf.items) if ("VLLM".equals(item.browseId)) liked = shelf;
        }
        if (liked != null) { // Downloads and History first in Liked Music's row, cards like it
            liked.items.add(0, cached);
            liked.items.add(0, history);
            liked.items.add(0, downloads);
            return shelves;
        }
        top.items.add(downloads);
        top.items.add(history);
        top.items.add(cached);
        {
            MusicApi.Item item = new MusicApi.Item();
            item.kind = MusicApi.CARD;
            item.title = getString(R.string.meetune_liked);
            item.browseId = "VLLM";
            top.items.add(item);
        }
        shelves.add(0, top);
        return shelves;
    }

    private void search(String query) {
        if (query.isEmpty()) return;
        mQuery = query;
        mFilter = 0;
        if (mFilters != null) mFilters.setSelected(0);
        runSearch(query);
    }

    /** An emptied field: the old results go (and a search on its way with them), Search is blank again. */
    private void clearResults() {
        if (mQuery == null) return;
        mQuery = null;
        mGeneration++;
        Page page = mTabs[SEARCH];
        page.rows = null;
        page.scroll = null;
        if (current() == page) {
            mProgress.setVisibility(View.GONE);
            show(page, false);
        }
    }

    @Nullable
    private String mQuery;
    /** Search's chip: an index of {@link MusicApi#FILTERS}. */
    private int mFilter;
    @Nullable
    private com.newtube.mobile.ui.common.GlassChipTrack mFilters;
    @Nullable
    private View mFilterRow;

    /** All, Songs, Videos, Albums, Artists, Playlists: MeeTube's glass segment over the results. */
    private View filterRow() {
        if (mFilterRow == null) {
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.setPadding(MeeTuneAdapter.dp(this, 14), MeeTuneAdapter.dp(this, 4), MeeTuneAdapter.dp(this, 14),
                    MeeTuneAdapter.dp(this, 8));
            frame.setClipToPadding(false); // the track swells as the pill runs: never cut flat
            mFilters = new com.newtube.mobile.ui.common.GlassChipTrack(this);
            mFilters.setGlassSource(null);
            mFilters.setChips(java.util.Arrays.asList(getString(R.string.meetune_filter_all),
                    getString(R.string.meetune_filter_songs), getString(R.string.meetune_filter_videos),
                    getString(R.string.meetune_filter_albums), getString(R.string.meetune_filter_artists),
                    getString(R.string.meetune_filter_playlists)));
            mFilters.setSelected(mFilter);
            // Each search re-lists the results, taking this row off screen for a moment: the track stops
            // its pill wherever it was. Back on screen, the pill runs on to the picked chip.
            mFilters.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mFilters.setSelected(-1);
                    mFilters.setSelected(mFilter);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
            mFilters.setOnPicked(index -> {
                if (index == mFilter || mQuery == null) return;
                Haptics.click(mFilters);
                mFilter = index;
                runSearch(mQuery);
            });
            frame.addView(mFilters, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, MeeTuneAdapter.dp(this, 44)));
            mFilterRow = frame;
        }
        return mFilterRow;
    }

    private void runSearch(String query) {
        closeSuggestions();
        Page page = mTabs[SEARCH];
        int generation = ++mGeneration;
        List<MeeTuneAdapter.Row> waiting = new ArrayList<>();
        waiting.add(MeeTuneAdapter.viewRow(filterRow())); // the chips stay while it loads
        mAdapter.setRows(waiting);
        mMessage.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        String filter = MusicApi.FILTERS[mFilter];
        MeeTune.run(this, () -> MeeTuneAdapter.searchRows(MusicApi.search(this, query, filter)), (rows, error) -> {
            if (generation != mGeneration) return;
            mProgress.setVisibility(View.GONE);
            if (rows == null) {
                message(getString(R.string.meetune_error), getString(R.string.meetune_retry), () -> runSearch(query));
                return;
            }
            rows.add(0, MeeTuneAdapter.viewRow(filterRow()));
            page.rows = rows;
            page.scroll = null;
            if (current() == page) render(page);
        });
    }

    private void showBackdrop(@Nullable String art) {
        ImageView backdrop = findViewById(R.id.meetune_backdrop);
        if (art == null) {
            backdrop.setVisibility(View.GONE);
            Glide.with(backdrop).clear(backdrop);
            return;
        }
        backdrop.setVisibility(View.VISIBLE);
        backdrop.setTranslationY(0f);
        // A small copy scaled up is already soft; Android 12+ blurs it properly on top.
        Glide.with(backdrop).load(art).override(120).into(backdrop);
    }

    /** The backdrop rides with the page's head; gone once the head scrolls away. */
    private void followBackdrop() {
        View backdrop = findViewById(R.id.meetune_backdrop);
        if (backdrop.getVisibility() != View.VISIBLE) return;
        View first = mList.getLayoutManager() != null ? mList.getLayoutManager().findViewByPosition(0) : null;
        backdrop.setTranslationY(first != null ? first.getTop() - mList.getPaddingTop() : -backdrop.getHeight());
    }

    private void render(Page page) {
        com.newtube.mobile.ui.common.Motion.frostIn(mList); // pages come in out of blur, as MeeTube's
        for (MeeTuneAdapter.Row row : page.rows) { // the head's cover is the big one
            if (row.type == MeeTuneAdapter.HEADER && row.item.art != null && !row.item.art.equals(page.art)) {
                page.art = row.item.art;
                showBackdrop(page.art);
                break;
            }
        }
        mProgress.setVisibility(View.GONE);
        mAdapter.setRows(shown(page));
        boolean list = trackRun(page.rows)[1] - trackRun(page.rows)[0] > 1 && head(page.rows) != null;
        mSort.setVisibility(list ? View.VISIBLE : View.GONE);
        if (list) mSettings.setVisibility(View.VISIBLE); // the avatar beside Sort, as on Home
        if (page.rows.isEmpty()) {
            message(getString(R.string.meetune_empty), null, null);
        } else {
            mMessage.setVisibility(View.GONE);
        }
        if (page.scroll != null && mList.getLayoutManager() != null) {
            mList.getLayoutManager().onRestoreInstanceState(page.scroll);
        } else {
            mList.scrollToPosition(0);
        }
        mList.post(this::followBackdrop); // a page come back to mid-scroll
    }

    // ----------------------------------------------------------------------------- a list's order

    @Nullable
    private static MeeTuneAdapter.Row head(List<MeeTuneAdapter.Row> rows) {
        return !rows.isEmpty() && rows.get(0).type == MeeTuneAdapter.HEADER ? rows.get(0) : null;
    }

    /** The page's track list: its first run of song rows, [start, end). */
    private static int[] trackRun(List<MeeTuneAdapter.Row> rows) {
        int start = -1;
        int end = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).type == MeeTuneAdapter.SONG) {
                if (start < 0) start = i;
                end = i + 1;
            } else if (start >= 0) {
                break;
            }
        }
        return new int[] {start, end};
    }

    /** The page as shown: its track list in the chosen order, cut to what the find box matches. */
    private static List<MeeTuneAdapter.Row> shown(Page page) {
        List<MeeTuneAdapter.Row> rows = page.rows;
        MeeTuneAdapter.Row head = head(rows);
        int[] run = trackRun(rows);
        if (head == null || run[0] < 0) return rows;
        if (page.queue == null) page.queue = head.siblings;
        String find = page.find.trim().toLowerCase(java.util.Locale.ROOT);
        if (page.sort == SORT_DEFAULT && find.isEmpty()) {
            head.siblings = page.queue;
            return rows;
        }
        List<MusicApi.Item> songs = new ArrayList<>();
        for (int i = run[0]; i < run[1]; i++) {
            MusicApi.Item item = rows.get(i).item;
            if (find.isEmpty() || has(item.title, find) || has(item.subtitle, find)) songs.add(item);
        }
        java.util.Comparator<MusicApi.Item> byTitle = (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title);
        if (page.sort == SORT_ADDED) {
            // ponytail: the newest first is the list backwards (YouTube Music keeps adds at the end);
            // read each item's added date if a list ever comes in another order.
            java.util.Collections.reverse(songs);
        } else if (page.sort == SORT_AZ) {
            songs.sort(byTitle);
        } else if (page.sort == SORT_ZA) {
            songs.sort(byTitle.reversed());
        }
        List<MeeTuneAdapter.Row> out = new ArrayList<>(rows.subList(0, run[0]));
        for (MusicApi.Item item : songs) {
            MeeTuneAdapter.Row row = new MeeTuneAdapter.Row(MeeTuneAdapter.SONG);
            row.item = item;
            row.siblings = songs;
            out.add(row);
        }
        out.addAll(rows.subList(run[1], rows.size()));
        head.siblings = songs.isEmpty() ? page.queue : songs; // Play and Shuffle go by what shows
        return out;
    }

    private static boolean has(@Nullable String text, String find) {
        return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(find);
    }

    private void showSortMenu() {
        Page page = current();
        if (page.rows == null) return;
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        android.widget.PopupWindow popup = glassPopup(content, 250);
        int[] labels = {R.string.meetune_sort_added, R.string.meetune_sort_default, R.string.meetune_sort_az,
                R.string.meetune_sort_za};
        for (int sort = 0; sort < labels.length; sort++) {
            int chosen = sort;
            popupRow(content, getString(labels[sort]), page.sort == sort, () -> {
                popup.dismiss();
                if (page.sort == chosen) return;
                page.sort = chosen;
                mAdapter.setTail(shown(page));
            });
        }
        popup.showAsDropDown(mSort, 0, MeeTuneAdapter.dp(this, 8), android.view.Gravity.END);
    }

    @Override
    public void find(String query) {
        Page page = current();
        if (page.rows == null || query.equals(page.find)) return;
        page.find = query;
        mAdapter.setTail(shown(page));
    }

    @Override
    public void save(MusicApi.Item head, ImageView icon) {
        if (head.saveId == null) return;
        if (!MusicApi.signedIn(this)) {
            android.widget.Toast.makeText(this, R.string.meetune_save_sign_in, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean save = !head.saved;
        head.saved = save; // at once; put back if YouTube Music says no
        icon.setImageResource(save ? R.drawable.ic_mobile_check : R.drawable.ic_mobile_add);
        String id = head.saveId;
        MeeTune.run(this, () -> MusicApi.save(this, id, save), (done, error) -> {
            if (done != null) return;
            head.saved = !save;
            icon.setImageResource(head.saved ? R.drawable.ic_mobile_check : R.drawable.ic_mobile_add);
            android.widget.Toast.makeText(this, R.string.meetune_save_failed, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void pageMenu(View anchor, MusicApi.Item head) {
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        android.widget.PopupWindow popup = glassPopup(content, 250);
        Page page = current();
        popupRow(content, getString(R.string.mobile_watch_share), false, () -> {
            popup.dismiss();
            String link = head.saveId != null ? MusicApi.ORIGIN + "/playlist?list=" + head.saveId
                    : MusicApi.ORIGIN + "/browse/" + page.browseId;
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, link), null));
        });
        if (head.saveId != null) {
            popupRow(content, getString(head.saved ? R.string.meetune_unsave : R.string.meetune_save), false, () -> {
                popup.dismiss();
                View row = mList.getLayoutManager() != null ? mList.getLayoutManager().findViewByPosition(0) : null;
                ImageView icon = row != null ? row.findViewById(R.id.meetune_add_icon) : null;
                if (icon != null) save(head, icon);
            });
        }
        popup.showAsDropDown(anchor, 0, MeeTuneAdapter.dp(this, 8), android.view.Gravity.END);
    }

    /** A frosted popup like MeeTube's: the page copied as it opens, blurred inside 18dp corners. */
    private android.widget.PopupWindow glassPopup(android.widget.LinearLayout content, int widthDp) {
        return glassPopup(content, widthDp, 18);
    }

    private android.widget.PopupWindow glassPopup(android.widget.LinearLayout content, int widthDp, int cornerDp) {
        return MeeTuneSongMenu.glassPopup(this, content, widthDp, cornerDp);
    }

    private void popupRow(ViewGroup parent, String label, boolean checked, Runnable onClick) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(16);
        row.setTextColor(getColor(R.color.mobile_color_on_surface));
        int h = MeeTuneAdapter.dp(this, 20);
        int v = MeeTuneAdapter.dp(this, 14);
        row.setPadding(h, v, h, v);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        if (checked) {
            android.graphics.drawable.Drawable tick = getDrawable(R.drawable.ic_mobile_check).mutate();
            tick.setTint(getColor(R.color.mobile_color_on_surface));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, tick, null);
        }
        row.setOnClickListener(x -> onClick.run());
        parent.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void message(String text, @Nullable String action, @Nullable Runnable onAction) {
        mProgress.setVisibility(View.GONE);
        mMessage.setVisibility(View.VISIBLE);
        mMessageText.setText(text);
        mMessageAction.setVisibility(action != null ? View.VISIBLE : View.GONE);
        mMessageAction.setText(action);
        mMessageAction.setOnClickListener(onAction != null ? v -> onAction.run() : null);
    }

    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(mSearchField.getWindowToken(), 0);
    }

    // ----------------------------------------------------------------------------- account

    private void showAvatar() {
        com.newtube.mobile.ui.signin.WebSignInSheet.loadAvatar(this, findViewById(R.id.meetune_account_avatar),
                MeeTune.signedIn(this) ? com.newtube.mobile.ui.signin.WebSignInState.accountPhoto(this) : null);
    }

    /** The avatar's popup: the web accounts (a tap switches), Add account (or Sign in), then Settings. */
    private void showAccountMenu() {
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        android.widget.PopupWindow popup = glassPopup(content, 280);
        int pad = MeeTuneAdapter.dp(this, 6);
        // A tap on the ticked account takes the tick off: MeeTune then runs without an account.
        int selected = MeeTune.signedIn(this) ? com.newtube.mobile.ui.signin.WebSignInState.selectedSlot(this) : -1;
        for (com.newtube.mobile.ui.signin.WebSignInState.Account account
                : com.newtube.mobile.ui.signin.WebSignInState.accounts(this)) {
            View row = getLayoutInflater().inflate(R.layout.item_mobile_account_row, content, false);
            ((TextView) row.findViewById(R.id.mobile_account_row_name)).setText(account.name);
            row.findViewById(R.id.mobile_account_row_email).setVisibility(View.GONE);
            row.findViewById(R.id.mobile_account_row_check)
                    .setVisibility(account.slot == selected ? View.VISIBLE : View.INVISIBLE);
            com.newtube.mobile.ui.signin.WebSignInSheet.loadAvatar(this,
                    row.findViewById(R.id.mobile_account_row_avatar), account.photo);
            row.setOnClickListener(v -> {
                popup.dismiss();
                boolean off = account.slot == selected;
                MeeTune.setNoAccount(this, off);
                if (!off) com.newtube.mobile.ui.signin.WebSignInState.select(this, account.slot);
                showAvatar();
                for (Page page : mTabs) page.rows = null; // the music is the new account's
                show(current(), true);
            });
            content.addView(row);
        }
        boolean signedIn = com.newtube.mobile.ui.signin.WebSignInState.isSignedIn(this);
        accountMenuRow(content, R.drawable.ic_mobile_add,
                getString(signedIn ? R.string.meetune_add_account : R.string.meetune_sign_in), () -> {
                    popup.dismiss();
                    mSignIn.launch(signedIn ? com.newtube.mobile.ui.signin.WebSignInSheet.addAccountIntent(this)
                            : new Intent(this, MobileWebSignInActivity.class));
                });
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.mobile_color_on_surface_secondary));
        line.setAlpha(0.2f);
        android.widget.LinearLayout.LayoutParams lineParams =
                new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lineParams.setMargins(MeeTuneAdapter.dp(this, 20), pad, MeeTuneAdapter.dp(this, 20), pad);
        content.addView(line, lineParams);
        accountMenuRow(content, R.drawable.ic_mobile_settings, getString(R.string.meetune_settings), () -> {
            popup.dismiss();
            push(settingsPage(MeeTuneSettings.SETTINGS));
        });
        popup.showAsDropDown(mSettings, 0, MeeTuneAdapter.dp(this, 8), android.view.Gravity.END);
    }

    private void accountMenuRow(ViewGroup parent, int icon, String label, Runnable onClick) {
        View row = getLayoutInflater().inflate(R.layout.item_mobile_account_row, parent, false);
        ImageView glyph = row.findViewById(R.id.mobile_account_row_avatar);
        glyph.setImageResource(icon);
        glyph.setScaleType(ImageView.ScaleType.CENTER);
        glyph.setColorFilter(getColor(R.color.mobile_color_on_surface));
        ((TextView) row.findViewById(R.id.mobile_account_row_name)).setText(label);
        row.findViewById(R.id.mobile_account_row_email).setVisibility(View.GONE);
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
    }

    private Page settingsPage(int which) {
        Page page = new Page(mTab);
        page.settings = which;
        page.title = MeeTuneSettings.title(this, which);
        return page;
    }

    private final MeeTuneSettings.Host mSettingsHost = which -> push(settingsPage(which));

    /** "Tap the icon to switch to MeeTube" under Home's brand pill, until its X or the first switch back. */
    private void syncMeeTubeHint(boolean home) {
        View hint = findViewById(R.id.meetune_meetube_hint);
        if (!home || !MeeTune.meeTubeHint(this)) {
            hint.setVisibility(View.GONE);
            return;
        }
        MeeTune.showSwitchHint(this, hint, findViewById(R.id.meetune_brand), findViewById(R.id.meetune_blur),
                R.id.meetune_meetube_hint_close, () -> MeeTune.leave(this), () -> MeeTune.closeMeeTubeHint(this));
    }

    /** Home's brand pill: the heart and "MeeTune", or the heart alone on a round glass (Customization). */
    private void showBrand() {
        boolean name = MeeTune.brandName(this);
        View label = findViewById(R.id.meetune_brand_name);
        label.setVisibility(name ? View.VISIBLE : View.GONE);
        android.widget.LinearLayout row = (android.widget.LinearLayout) label.getParent();
        View heart = row.getChildAt(0);
        ViewGroup.MarginLayoutParams heartLp = (ViewGroup.MarginLayoutParams) heart.getLayoutParams();
        heartLp.setMarginEnd(name ? MeeTuneAdapter.dp(this, 8) : 0);
        heart.setLayoutParams(heartLp); // a start/end margin only re-resolves on setLayoutParams: the old 8dp stayed, heart off-centre
        row.setGravity(name ? android.view.Gravity.CENTER_VERTICAL : android.view.Gravity.CENTER);
        row.setPaddingRelative(name ? MeeTuneAdapter.dp(this, 14) : 0, 0, name ? MeeTuneAdapter.dp(this, 16) : 0, 0);
        row.getLayoutParams().width = name ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        View brand = findViewById(R.id.meetune_brand);
        brand.getLayoutParams().width = name ? ViewGroup.LayoutParams.WRAP_CONTENT : MeeTuneAdapter.dp(this, 44);
        brand.requestLayout();
    }

    // ----------------------------------------------------------------------------- MeeTuneAdapter.Host

    @Override
    public void open(MusicApi.Item item, List<MusicApi.Item> siblings) {
        Haptics.click(mList);
        if (mSuggestions.getVisibility() == View.VISIBLE) closeSuggestions(); // a suggested song or artist picked
        if (item.playable()) {
            MeeTune.play(this, item);
            Page here = current();
            if (MeeTune.CACHED.equals(here.browseId) && here.rows != null) { // the cached songs after it play on
                boolean after = false;
                for (MeeTuneAdapter.Row row : here.rows) {
                    if (row.shelf == null) continue;
                    for (MusicApi.Item song : row.shelf.items) {
                        if (after && song.playable()) MeeTune.queue(this, song, false);
                        if (song == item) after = true;
                    }
                }
            }
            return;
        }
        if (item.browseId == null) return;
        Page page = new Page(mTab);
        page.browseId = item.browseId;
        page.params = item.params;
        page.title = item.kind == MusicApi.MOOD || MeeTune.DOWNLOADS.equals(item.browseId)
                || MeeTune.CACHED.equals(item.browseId)
                || MeeTune.HISTORY.equals(item.browseId) ? item.title : null;
        page.art = item.kind == MusicApi.MOOD ? null : item.art;
        push(page);
    }

    @Override
    public void addToPlaylist(MusicApi.Item item) {
        com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil.showAddToPlaylistDialog(this, MeeTune.video(item), null);
    }

    @Override
    public void showAll(MusicApi.Shelf shelf) {
        Page page = new Page(mTab);
        page.browseId = shelf.moreBrowseId;
        page.params = shelf.moreParams;
        page.title = shelf.title;
        push(page);
    }

    @Override
    public void songMenu(View anchor, MusicApi.Item item) {
        MeeTuneSongMenu.show(this, anchor, item, this::openBrowse);
    }

    /** Open album, Open artist: their page, pushed as a card's is. */
    private void openBrowse(String browseId) {
        MusicApi.Item page = new MusicApi.Item();
        page.browseId = browseId;
        page.kind = MusicApi.CARD;
        open(page, new ArrayList<>());
    }

    // ----------------------------------------------------------------------------- the bar

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        return (mNavBar != null && mNavBar.onTouch(event)) || super.dispatchTouchEvent(event);
    }

    /** MEETUNE(status bar): the page runs up under the status bar, frosted there as MeeTube's Settings. */
    @Override
    protected androidx.core.graphics.Insets contentInsets(androidx.core.view.WindowInsetsCompat windowInsets) {
        androidx.core.graphics.Insets bars = super.contentInsets(windowInsets);
        return androidx.core.graphics.Insets.of(bars.left, 0, bars.right, bars.bottom);
    }

    /** The top row, its frost, the lists and the refresh spinner move down by the status bar themselves. */
    private void blendIntoStatusBar(View topFrost) {
        View row = findViewById(R.id.meetune_top);
        View suggestions = findViewById(R.id.meetune_suggestions);
        int frostBase = topFrost.getLayoutParams().height;
        int listBase = mList.getPaddingTop();
        int spinner = MeeTuneAdapter.dp(this, 40);
        ViewCompat.setOnApplyWindowInsetsListener(row, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top;
            ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).topMargin = top;
            row.requestLayout();
            topFrost.getLayoutParams().height = frostBase + top;
            topFrost.requestLayout();
            mList.setPadding(mList.getPaddingLeft(), listBase + top, mList.getPaddingRight(), mList.getPaddingBottom());
            suggestions.setPadding(suggestions.getPaddingLeft(), listBase + top, suggestions.getPaddingRight(),
                    suggestions.getPaddingBottom());
            mSwipe.setProgressViewOffset(false, listBase + top - spinner, listBase + top + spinner / 2);
            return insets;
        });
        ViewCompat.requestApplyInsets(row);
    }

    @Override
    protected boolean shouldInsetContentForNavigationBar() {
        return !com.newtube.mobile.ui.common.YouTubeMode.isOn(this); // OG Mode: the bar docks to the edge
    }

    // ----------------------------------------------------------------------------- mini card

    @Override
    protected void onResume() {
        super.onResume();
        MiniPlayerBridge.registerMiniHost(this);
        placeMiniCard();
        syncMini();
        mNavBar.syncLabels(); // MeeTube's Settings > Tabs may have changed while away
        showAvatar(); // signed in or switched on MeeTube's You page meanwhile
        String account = accountKey();
        if (!account.equals(mAccount)) { // switched in MeeTune's settings (or MeeTube's) meanwhile
            boolean first = mAccount == null;
            mAccount = account;
            if (!first) {
                for (Page page : mTabs) page.rows = null; // the music is the new account's
                mStack.clear();
                show(current(), true);
            }
        }
        String browse = MeeTune.takeOpenOnReturn(); // the music player's Open album or Open artist
        if (MeeTune.EQUALIZER.equals(browse)) push(settingsPage(MeeTuneSettings.EQUALIZER)); // its Equalizer
        else if (browse != null) openBrowse(browse);
    }

    @Nullable
    private String mAccount;

    private String accountKey() {
        return MeeTune.account(this);
    }

    @Override
    protected void onPause() {
        if (mMiniPlayer != null) mMiniPlayer.onHostPause();
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.newtube.mobile.downloads.DownloadRegistry.instance(this).addListener(mDownloadProgress);
    }

    @Override
    protected void onStop() {
        com.newtube.mobile.downloads.DownloadRegistry.instance(this).removeListener(mDownloadProgress);
        super.onStop();
        if (mMiniPlayer != null) mMiniPlayer.onHostStop();
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);
        if (MeeTune.sHistoryChanged == mHistoryChanged) MeeTune.sHistoryChanged = null;
        mPill.release();
        super.onDestroy();
    }

    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        mMiniPlayer.hide(); // MeeTube's video card never shows here
        if (!mPill.sync()) return false;
        mPill.whenDrawn(onDrawn);
        return true;
    }

    /**
     * MeeTune's own song docks as the pill. A MeeTube video is MeeTube's: it pauses and the song it
     * replaced comes back paused (MiniPlayerBridge.claim); the video waits for MeeTube the same way.
     */
    private void syncMini() {
        mPill.land(); // MeeTune is on top again: a song that just landed shows its own cover
        MiniPlayerBridge.claim(true);
        mPill.sync();
        mMiniPlayer.hide();
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return MeeTuneActivity.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        View nav = findViewById(R.id.meetune_nav_glass);
        int navSpace = nav != null && nav.getHeight() > 0 ? nav.getHeight() : MeeTuneAdapter.dp(this, 70);
        return navSpace + MeeTuneAdapter.dp(this, 20);
    }
}
