package com.newtube.mobile.ui.signin;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.web.WebSession;
import com.newtube.mobile.ui.common.MobileActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Google's page owns credential entry. The app only reads the resulting YouTube session cookies. */
public class MobileWebSignInActivity extends MobileActivity {
    private static final String SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&prompt=select_account"
            + "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3D%252F";
    /** One more Google account into the same session; the ones already there stay signed in. */
    private static final String ADD_URL = "https://accounts.google.com/AddSession?service=youtube"
            + "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3D%252F";
    /** Intent extra: add an account next to the signed-in ones instead of starting over. */
    public static final String EXTRA_ADD = "add_account";
    private static final int MAX_SLOTS = 10;

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private WebView mWeb;
    private View mStatus;
    private View mProgress;
    private View mRetry;
    private TextView mMessage;
    private boolean mChecking;
    private boolean mCheckAgain;
    private boolean mFinishingSignIn;
    private volatile boolean mClosed;
    private int mAttempt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_mobile_web_signin);
        mStatus = findViewById(R.id.web_signin_status);
        mProgress = findViewById(R.id.web_signin_progress);
        mRetry = findViewById(R.id.web_signin_retry);
        mMessage = findViewById(R.id.web_signin_message);
        findViewById(R.id.web_signin_close).setOnClickListener(v -> finish());
        findViewById(R.id.web_signin_error_close).setOnClickListener(v -> finish());
        mRetry.setOnClickListener(v -> startSignIn());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (mStatus.getVisibility() != View.VISIBLE && mWeb != null && mWeb.canGoBack()) mWeb.goBack();
                else finish();
            }
        });
        startSignIn();
    }

    @Override protected boolean isBackStackScreen() { return false; }

    @Override protected boolean drawsUnderKeyboard() { return true; }

    @Override protected boolean onThemeChanged(int night) {
        return recolourInPlace(night);
    }

    @Override
    protected Insets contentInsets(WindowInsetsCompat insets) {
        return insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                | WindowInsetsCompat.Type.ime());
    }

    private void startSignIn() {
        final int attempt = ++mAttempt;
        mChecking = false;
        mCheckAgain = false;
        mFinishingSignIn = false;
        destroyWebView();
        boolean add = isAdding();
        if (!add) WebSignInState.clear(this);
        showStatus(R.string.mobile_web_signin_loading, false);
        mWorker.execute(() -> {
            try {
                // CookieManager loads WebView's library. Never acquire it on the main thread.
                CookieManager.getInstance().setAcceptCookie(true);
                if (!add) WebSignInState.clearCookies();
                CookieManager.getInstance().flush();
                runOnUiThread(() -> { if (active(attempt)) createWebView(attempt); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (active(attempt)) showFailure(); });
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView(int attempt) {
        try {
            mWeb = new WebView(this);
            WebView web = mWeb;
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setUserAgentString(WebSettings.getDefaultUserAgent(this).replace("; wv", ""));
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { checkSignIn(attempt, url); }
                @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                    checkSignIn(attempt, url);
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame() && active(attempt)) showFailure();
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !"https".equals(request.getUrl().getScheme());
                }
            });
            ((FrameLayout) findViewById(R.id.web_signin_page)).addView(web,
                    new FrameLayout.LayoutParams(-1, -1));
            // A WebView method: main thread only (the library is loaded by now, so it is cheap).
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            mStatus.setVisibility(View.GONE);
            web.loadUrl(isAdding() ? ADD_URL : SIGN_IN_URL);
        } catch (Exception e) {
            showFailure();
        }
    }

    private void checkSignIn(int attempt, String url) {
        String host = url != null ? Uri.parse(url).getHost() : null;
        if (!active(attempt) || mFinishingSignIn || host == null
                || !(host.equals("youtube.com") || host.endsWith(".youtube.com"))) return;
        if (mChecking) {
            mCheckAgain = true;
            return;
        }
        mChecking = true;
        mWorker.execute(() -> {
            try {
                if (!WebSession.isSignedIn()) {
                    runOnUiThread(() -> {
                        if (!active(attempt)) return;
                        mChecking = false;
                        if (mCheckAgain && mWeb != null) {
                            mCheckAgain = false;
                            checkSignIn(attempt, mWeb.getUrl());
                        }
                    });
                    return;
                }
                runOnUiThread(() -> {
                    if (active(attempt)) {
                        mFinishingSignIn = true;
                        showStatus(R.string.mobile_web_signin_finishing, false);
                    }
                });
                int[] slot = new int[1];
                WebSession.WebAccount account = newAccount(slot);
                if (account == null) throw new IllegalStateException();
                CookieManager.getInstance().flush();
                runOnUiThread(() -> {
                    if (active(attempt)) {
                        WebSignInState.signedIn(this, slot[0], account.name, account.photo);
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (active(attempt)) showFailure(); });
            }
        });
    }

    private boolean isAdding() {
        return getIntent().getBooleanExtra(EXTRA_ADD, false) && WebSignInState.isSignedIn(this);
    }

    /**
     * Worker only. The first slot whose account the app doesn't know yet: Google appends a new
     * session after the others. A slot past the last account answers as an earlier one, so a
     * repeated name ends the walk. Nothing new (an account already there): the current one stays.
     */
    private WebSession.WebAccount newAccount(int[] slot) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (WebSignInState.Account account : WebSignInState.accounts(this)) known.add(account.name);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < MAX_SLOTS; i++) {
            WebSession.WebAccount account = WebSession.account(i);
            if (account == null || !seen.add(account.name)) break;
            if (!known.contains(account.name)) {
                slot[0] = i;
                return account;
            }
        }
        slot[0] = WebSignInState.selectedSlot(this);
        return slot[0] < MAX_SLOTS ? WebSession.account(slot[0]) : null;
    }

    private boolean active(int attempt) { return !mClosed && !isFinishing() && attempt == mAttempt; }

    private void showFailure() {
        ++mAttempt;
        mFinishingSignIn = false;
        // No further history callback may finish this failed attempt; Retry starts a fresh one.
        mChecking = true;
        if (mWeb != null) mWeb.stopLoading();
        showStatus(R.string.mobile_web_signin_failed, true);
    }

    private void showStatus(int message, boolean failed) {
        mStatus.setVisibility(View.VISIBLE);
        mMessage.setText(message);
        mProgress.setVisibility(failed ? View.GONE : View.VISIBLE);
        mRetry.setVisibility(failed ? View.VISIBLE : View.GONE);
        findViewById(R.id.web_signin_error_close).setVisibility(failed ? View.VISIBLE : View.GONE);
    }

    private void destroyWebView() {
        if (mWeb != null) {
            ((FrameLayout) findViewById(R.id.web_signin_page)).removeView(mWeb);
            mWeb.stopLoading();
            mWeb.destroy();
            mWeb = null;
        }
    }

    @Override protected void onDestroy() {
        mClosed = true;
        mWorker.shutdownNow();
        destroyWebView();
        super.onDestroy();
    }
}
