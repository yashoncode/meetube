package com.newtube.mobile.ui.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.format.Formatter;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.discord.DiscordAddon;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MEETUBE(discord): Settings > Discord status. The add-on's download, the Discord sign in, and which
 * side shows: MeeTube videos as "Watching", MeeTune songs as "Listening to". MeeTune's Add-ons card
 * opens this same page.
 */
final class DiscordPages {
    private static final String DISCORD = "https://discord.com";
    private static final Pattern TOKEN = Pattern.compile("[\\w-]{20,}\\.[\\w-]{4,}\\.[\\w-]{20,}");
    private static final int ATTEMPTS = 12;
    private static final long RETRY_MS = 500;

    private DiscordPages() {
    }

    static SettingsPages.Page discord(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.action(0, context.getString(R.string.discord_addon), () -> addonSummary(context),
                DiscordPages::onAddon));
        rows.add(SettingsRow.action(0, context.getString(R.string.discord_account), () -> accountSummary(context),
                DiscordPages::onAccount).enabledWhen(() -> DiscordAddon.installed(context)));
        rows.add(SettingsRow.toggle(context.getString(R.string.discord_show_videos), context.getString(R.string.discord_show_videos_summary),
                () -> DiscordAddon.videos(context), on -> DiscordAddon.setVideos(context, on))
                .enabledWhen(() -> DiscordAddon.signedIn(context)));
        rows.add(SettingsRow.toggle(context.getString(R.string.discord_show_music), context.getString(R.string.discord_show_music_summary),
                () -> DiscordAddon.music(context), on -> DiscordAddon.setMusic(context, on))
                .enabledWhen(() -> DiscordAddon.signedIn(context)));
        rows.add(SettingsRow.note(context.getString(R.string.discord_note)));
        return new SettingsPages.Page(context.getString(R.string.discord_status), rows);
    }

    private static CharSequence addonSummary(Context context) {
        if (DiscordAddon.downloading()) return context.getString(R.string.discord_addon_downloading);
        if (DiscordAddon.installed(context)) return context.getString(R.string.discord_addon_ready);
        return context.getString(R.string.discord_addon_get, Formatter.formatShortFileSize(context, DiscordAddon.SIZE));
    }

    private static CharSequence accountSummary(Context context) {
        if (!DiscordAddon.signedIn(context)) return context.getString(R.string.discord_signed_out);
        String name = DiscordAddon.userName(context);
        return name != null ? context.getString(R.string.discord_signed_in, name) : context.getString(R.string.discord_signed_in_plain);
    }

    private static void onAddon(SettingsPageFragment page) {
        Context app = page.requireContext().getApplicationContext();
        if (DiscordAddon.downloading()) return;
        if (!DiscordAddon.installed(app)) {
            DiscordAddon.download(app, ok -> {
                if (!ok) MobileSnackbar.show(app, R.string.discord_addon_failed);
                if (page.isAdded()) page.rebuild();
            });
            page.rebuild();
            return;
        }
        page.showDialog(new MaterialAlertDialogBuilder(page.requireActivity(), R.style.MobileAlertDialog)
                .setTitle(R.string.discord_addon_remove_title)
                .setMessage(R.string.discord_addon_remove_message)
                .setPositiveButton(R.string.discord_addon_remove, (d, w) -> DiscordAddon.WORKER.execute(() -> {
                    DiscordAddon.remove(app);
                    page.requireActivity().runOnUiThread(() -> {
                        if (page.isAdded()) page.rebuild();
                    });
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static void onAccount(SettingsPageFragment page) {
        Context app = page.requireContext().getApplicationContext();
        if (DiscordAddon.signedIn(app)) {
            page.showDialog(new MaterialAlertDialogBuilder(page.requireActivity(), R.style.MobileAlertDialog)
                    .setTitle(R.string.discord_signout_title)
                    .setMessage(R.string.discord_signout_message)
                    .setPositiveButton(R.string.discord_signout, (d, w) -> {
                        Activity activity = page.requireActivity();
                        DiscordAddon.WORKER.execute(() -> {
                            DiscordAddon.signOut(app);
                            activity.runOnUiThread(() -> {
                                if (page.isAdded()) page.rebuild();
                            });
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            return;
        }
        // The account's own token is what signs in, and Discord's rules do not allow it: said first.
        page.showDialog(new MaterialAlertDialogBuilder(page.requireActivity(), R.style.MobileAlertDialog)
                .setTitle(R.string.discord_signin_title)
                .setMessage(R.string.discord_signin_warning)
                .setPositiveButton(R.string.discord_signin, (d, w) -> signIn(page))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    /**
     * discord.com's own login page (password, 2FA, passkeys), full screen. Once it leaves the
     * sign-in pages the add-on's script reads the token and alert() hands it out; the WebView's
     * Discord cookies and storage are then deleted, so the encrypted copy is the only one.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private static void signIn(SettingsPageFragment page) {
        Activity activity = page.requireActivity();
        Context app = activity.getApplicationContext();
        String script = DiscordAddon.call(app, "tokenScript");
        if (script == null) {
            MobileSnackbar.show(app, R.string.discord_signin_failed);
            return;
        }
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        WebView web = new WebView(activity);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUserAgentString(browserAgent(web.getSettings().getUserAgentString()));
        boolean[] captured = {false};
        int[] attempts = {0};
        Runnable harvest = () -> {
            if (captured[0] || attempts[0] >= ATTEMPTS) return;
            attempts[0]++;
            web.evaluateJavascript(script, null);
        };
        web.setWebViewClient(new WebViewClient() {
            // Discord moves from the form to the app with pushState, which only this hook sees.
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (signedIn(url)) harvest.run();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (signedIn(url)) harvest.run();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                if (captured[0] || attempts[0] == 0 || url == null || !url.startsWith(DISCORD + "/")) return true;
                if (message == null || !TOKEN.matcher(message).matches()) { // the client has not written it yet
                    view.postDelayed(harvest, RETRY_MS);
                    return true;
                }
                captured[0] = true;
                String token = message;
                DiscordAddon.WORKER.execute(() -> {
                    DiscordAddon.signIn(app, token);
                    activity.runOnUiThread(() -> {
                        if (page.isAdded()) page.rebuild();
                    });
                });
                dialog.dismiss();
                return true;
            }
        });
        // MATCH_PARENT both ways: a WebView laid out with a wrapped height gets a zero layout
        // viewport, and Discord's height:100% page then paints nothing.
        dialog.setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> {
            web.destroy();
            forgetWebLogin();
            if (!captured[0] && attempts[0] >= ATTEMPTS) MobileSnackbar.show(app, R.string.discord_signin_failed);
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP
                    && web.canGoBack()) {
                web.goBack();
                return true;
            }
            return false;
        });
        dialog.show();
        page.showDialog(dialog);
        web.loadUrl(DISCORD + "/login");
    }

    private static boolean signedIn(String url) {
        return url != null && url.startsWith(DISCORD + "/") && !url.contains("/login") && !url.contains("/register");
    }

    /**
     * Without "; wv" and "Version/4.0": Discord reads that pair as Android Browser 4.0 and serves a
     * page that never draws the form.
     */
    static String browserAgent(String agent) {
        if (agent == null) return null;
        return agent.replace("; wv", "").replaceAll("Version/\\d+(\\.\\d+)*\\s*", "").replace("  ", " ").trim();
    }

    /** discord.com's cookies and storage in the WebView go (each cookie by name: there is no per-site clear). */
    private static void forgetWebLogin() {
        CookieManager cm = CookieManager.getInstance();
        String cookies = cm.getCookie(DISCORD);
        if (cookies != null) {
            for (String c : cookies.split(";")) {
                String name = c.split("=", 2)[0].trim();
                if (name.isEmpty()) continue;
                cm.setCookie(DISCORD, name + "=; Max-Age=0; Path=/");
                cm.setCookie(DISCORD, name + "=; Max-Age=0; Path=/; Domain=.discord.com");
            }
        }
        cm.flush();
        WebStorage.getInstance().deleteOrigin(DISCORD);
    }
}
