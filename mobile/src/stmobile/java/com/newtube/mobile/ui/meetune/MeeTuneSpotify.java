package com.newtube.mobile.ui.meetune;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Spotify's canvas, the short looping video Spotify shows for a song. Unofficial and read only: the
 * listener signs in to Spotify's own web page in a WebView, and its session's token reads two of the
 * web player's own queries (search a song, then its canvas). The token is the one Spotify's page asks
 * for itself: a hidden WebView opens the web player, its request for {@code /api/token} is sent on
 * unchanged with the session's cookies, and the page gets an empty answer, so it never turns into a
 * player on the account. Nothing is injected into the page; other sites it reaches get nothing.
 */
public final class MeeTuneSpotify {
    private static final String PLAYER = "https://open.spotify.com/";
    private static final String SIGN_IN = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F";
    private static final String QUERY = "https://api-partner.spotify.com/pathfinder/v2/query";
    // The web player's persisted query codes, built in (as of 2026-11). Spotify retires a code when it
    // changes the query; then the new one goes in spotify/codes.json in MeeTube's repo, which every
    // phone reads at most every six hours ({@link #code}), so no app update is needed.
    private static final String CODES = "https://raw.githubusercontent.com/yashoncode/meetube/main/spotify/codes.json";
    private static final long CODES_EVERY_MS = 6 * 60 * 60_000L;
    private static final String SEARCH = "db61238974d27839a136c9dc02bfdbe3fab7635f21cf85976ebff9a1ee281345";
    private static final String CANVAS = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f";
    private static final String LIBRARY = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455";
    private static final String PLAYLIST = "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf";
    private static final String LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240";
    private static final String[] OPERATIONS = {"searchDesktop", "canvas", "libraryV3", "fetchPlaylist", "fetchLibraryTracks"};
    /** Liked Songs, listed with the playlists. */
    static final String LIKED_ID = "liked";
    private static final String[] HOSTS = {"https://open.spotify.com", "https://accounts.spotify.com",
            "https://www.spotify.com", "https://spotify.com", "https://api-partner.spotify.com"};
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build();

    private static String sToken;
    private static long sTokenUntil;
    /** A token that would not come is not asked for again for ten minutes. */
    private static long sNoTokenBefore;

    private MeeTuneSpotify() {
    }

    /** The web page's session cookie is there: the sign-in finished. */
    public static boolean signedIn() {
        try {
            String cookies = CookieManager.getInstance().getCookie(PLAYER);
            if (cookies == null) return false;
            for (String c : cookies.split(";")) if (c.trim().startsWith("sp_dc=") && c.trim().length() > 6) return true;
        } catch (RuntimeException ignored) { // no WebView on this phone
        }
        return false;
    }

    /** Spotify's sign-in page, full screen; {@code closed} when it goes, signed in or backed out of ({@link #signedIn}). */
    @SuppressLint("SetJavaScriptEnabled")
    public static void signIn(Activity activity, Runnable closed) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        WebView web = new WebView(activity);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUserAgentString(agent(web.getSettings().getUserAgentString()));
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return isTokenRequest(request) ? empty() : null; // signed in, the page would become a player
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (signedIn() && dialog.isShowing()) {
                    CookieManager.getInstance().flush();
                    dialog.dismiss();
                }
            }
        });
        dialog.setContentView(web);
        dialog.setOnDismissListener(d -> {
            web.destroy();
            closed.run();
        });
        dialog.show();
        web.loadUrl(SIGN_IN);
    }

    /** Spotify's cookies and pages' storage go (each by name: the store has no per-site clear). */
    public static void signOut() {
        synchronized (MeeTuneSpotify.class) {
            sToken = null;
        }
        CookieManager cm = CookieManager.getInstance();
        for (String host : HOSTS) {
            String cookies = cm.getCookie(host);
            if (cookies == null) continue;
            for (String c : cookies.split(";")) {
                String name = c.split("=", 2)[0].trim();
                if (name.isEmpty()) continue;
                cm.setCookie(host, name + "=; Max-Age=0; Path=/");
                cm.setCookie(host, name + "=; Max-Age=0; Path=/; Domain=.spotify.com");
            }
            android.webkit.WebStorage.getInstance().deleteOrigin(host);
        }
        cm.flush();
    }

    /**
     * The song's canvas loop on Spotify's file host, or null (none, a still picture, no sure match, or
     * Spotify not answering). Blocking: from a worker.
     */
    @Nullable
    static String canvas(Context context, String title, String artist, long durationMs) {
        if (!signedIn()) return null;
        try {
            String token = token(context);
            if (token == null) return null;
            JSONObject search = query(token, "searchDesktop", code(context, "searchDesktop", SEARCH), new JSONObject()
                    .put("searchTerm", title + " " + artist).put("offset", 0).put("limit", 10)
                    .put("numberOfTopResults", 5).put("includeAudiobooks", false)
                    .put("includeArtistHasConcertsField", false).put("includePreReleases", false)
                    .put("includeLocalConcertsField", false).put("includeAuthors", false));
            String uri = pick(search, title, artist, durationMs);
            if (uri == null) return null;
            JSONObject canvas = query(token, "canvas", code(context, "canvas", CANVAS), new JSONObject().put("trackUri", uri));
            JSONObject c = canvas.optJSONObject("trackUnion") != null ? canvas.optJSONObject("trackUnion").optJSONObject("canvas") : null;
            if (c == null || !c.optString("type").startsWith("VIDEO")) return null;
            String url = c.optString("url");
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost() != null ? u.getHost().toLowerCase(java.util.Locale.ROOT) : "";
            return "https".equals(u.getScheme()) && (host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com")) ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The search's track that is this song: the same title and artist, and within 3 s of its length when both are known. */
    @Nullable
    static String pick(JSONObject search, String title, String artist, long durationMs) {
        JSONObject v2 = search.optJSONObject("searchV2");
        JSONObject tracks = v2 != null ? v2.optJSONObject("tracksV2") : null;
        JSONArray items = tracks != null ? tracks.optJSONArray("items") : null;
        String wantTitle = MeeTuneCanvas.normal(title), wantArtist = MeeTuneCanvas.normal(artist);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i) != null ? items.optJSONObject(i).optJSONObject("item") : null;
            JSONObject data = item != null ? item.optJSONObject("data") : null;
            if (data == null || !data.optString("uri").startsWith("spotify:track:")) continue;
            if (!MeeTuneCanvas.sameTitle(wantTitle, MeeTuneCanvas.normal(data.optString("name")))) continue;
            JSONObject artists = data.optJSONObject("artists");
            JSONArray list = artists != null ? artists.optJSONArray("items") : null;
            boolean artistOk = false;
            for (int a = 0; list != null && a < list.length(); a++) {
                JSONObject profile = list.optJSONObject(a) != null ? list.optJSONObject(a).optJSONObject("profile") : null;
                String name = MeeTuneCanvas.normal(profile != null ? profile.optString("name") : null);
                artistOk |= !name.isEmpty() && (MeeTuneCanvas.words(wantArtist, name) || MeeTuneCanvas.words(name, wantArtist));
            }
            if (!artistOk) continue;
            JSONObject length = data.optJSONObject("duration") != null ? data.optJSONObject("duration") : data.optJSONObject("trackDuration");
            long ms = length != null ? length.optLong("totalMilliseconds") : 0;
            if (durationMs > 0 && ms > 0 && Math.abs(ms - durationMs) > 3000) continue;
            return data.optString("uri");
        }
        return null;
    }

    /** A Spotify playlist to import: its id ({@link #LIKED_ID} for Liked Songs), name and length. */
    static final class Playlist {
        final String id;
        final String name;
        final int count;

        Playlist(String id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    /** A track to find on YouTube Music. */
    static final class Track {
        final String title;
        final List<String> artists = new ArrayList<>();
        long durationMs;

        Track(String title) {
            this.title = title;
        }
    }

    /** Liked Songs, then the listener's playlists (their own and the ones they follow, folders opened). Blocking. */
    static List<Playlist> playlists(Context context) throws Exception {
        String token = needToken(context);
        List<Playlist> out = new ArrayList<>();
        JSONObject liked = query(token, "fetchLibraryTracks", code(context, "fetchLibraryTracks", LIKED),
                new JSONObject().put("offset", 0).put("limit", 1));
        JSONObject tracks = obj(liked, "me", "library", "tracks");
        if (tracks != null && tracks.optInt("totalCount") > 0) out.add(new Playlist(LIKED_ID, "Liked Songs", tracks.optInt("totalCount")));
        for (int offset = 0; offset < 2000; offset += 50) {
            JSONObject page = obj(query(token, "libraryV3", code(context, "libraryV3", LIBRARY), new JSONObject()
                    .put("filters", new JSONArray().put("Playlists")).put("order", JSONObject.NULL).put("textFilter", "")
                    .put("features", new JSONArray().put("LIKED_SONGS").put("YOUR_EPISODES_V2").put("PRERELEASES").put("EVENTS"))
                    .put("limit", 50).put("offset", offset).put("flatten", true).put("expandedFolders", new JSONArray())
                    .put("folderUri", JSONObject.NULL).put("includeFoldersWhenFlattening", false)), "me", "libraryV3");
            JSONArray items = page != null ? page.optJSONArray("items") : null;
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject wrap = obj(items.optJSONObject(i), "item");
                JSONObject data = obj(wrap, "data");
                if (wrap == null || data == null) continue;
                String uri = wrap.optString("_uri", data.optString("uri"));
                if (!uri.startsWith("spotify:playlist:")) continue;
                String id = uri.substring(uri.lastIndexOf(':') + 1);
                out.add(new Playlist(id, data.optString("name", id), data.optInt("count")));
            }
            if (offset + 50 >= page.optInt("totalCount")) break;
        }
        return out;
    }

    /** A playlist's songs, in order (episodes and local files left out). Blocking. */
    static List<Track> tracks(Context context, String playlistId) throws Exception {
        String token = needToken(context);
        boolean liked = LIKED_ID.equals(playlistId);
        List<Track> out = new ArrayList<>();
        for (int offset = 0; offset < 10_000; offset += 100) {
            JSONObject content;
            if (liked) {
                content = obj(query(token, "fetchLibraryTracks", code(context, "fetchLibraryTracks", LIKED),
                        new JSONObject().put("offset", offset).put("limit", 100)), "me", "library", "tracks");
            } else {
                content = obj(query(token, "fetchPlaylist", code(context, "fetchPlaylist", PLAYLIST), new JSONObject()
                        .put("uri", "spotify:playlist:" + playlistId).put("offset", offset).put("limit", 100)
                        .put("enableWatchFeedEntrypoint", true)), "playlistV2", "content");
            }
            JSONArray items = content != null ? content.optJSONArray("items") : null;
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject data = obj(items.optJSONObject(i), liked ? "track" : "itemV2", "data");
                if (data == null || data.optString("name").isEmpty()) continue;
                String uri = data.optString("uri", "");
                if (!uri.isEmpty() && !uri.startsWith("spotify:track:")) continue;
                Track track = new Track(data.optString("name"));
                JSONObject artistList = obj(data, "artists");
                JSONArray artists = artistList != null ? artistList.optJSONArray("items") : null;
                for (int a = 0; artists != null && a < artists.length(); a++) {
                    JSONObject profile = obj(artists.optJSONObject(a), "profile");
                    if (profile != null && !profile.optString("name").isEmpty()) track.artists.add(profile.optString("name"));
                }
                JSONObject length = obj(data, "duration") != null ? obj(data, "duration") : obj(data, "trackDuration");
                track.durationMs = length != null ? length.optLong("totalMilliseconds") : 0;
                out.add(track);
            }
            if (offset + 100 >= content.optInt("totalCount")) break;
        }
        return out;
    }

    private static String needToken(Context context) throws Exception {
        String token = signedIn() ? token(context) : null;
        if (token == null) throw new java.io.IOException("no Spotify session");
        return token;
    }

    @Nullable
    private static JSONObject obj(@Nullable JSONObject o, String... keys) {
        for (String key : keys) {
            if (o == null) return null;
            o = o.optJSONObject(key);
        }
        return o;
    }

    /** The query's code: the one from the repo's codes.json when it gave one, else the built-in one. */
    private static String code(Context context, String operation, String builtIn) {
        android.content.SharedPreferences prefs = MeeTune.prefs(context);
        long now = System.currentTimeMillis();
        long at = prefs.getLong("spotify_codes_at", 0);
        if (now - at > CODES_EVERY_MS || at > now) {
            prefs.edit().putLong("spotify_codes_at", now).apply(); // a failed fetch waits the six hours too
            try (Response response = CLIENT.newCall(new Request.Builder().url(CODES).build()).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    android.content.SharedPreferences.Editor edit = prefs.edit();
                    JSONObject codes = new JSONObject(response.body().string());
                    for (String op : OPERATIONS) {
                        String fresh = codes.optString(op).toLowerCase(java.util.Locale.ROOT);
                        if (isCode(fresh)) edit.putString("spotify_code_" + op, fresh);
                    }
                    edit.apply();
                }
            } catch (Exception ignored) {
                // offline or no file: the codes we had
            }
        }
        String kept = prefs.getString("spotify_code_" + operation, null);
        return isCode(kept) ? kept : builtIn;
    }

    /** 64 hex digits: anything else in the file is ignored. */
    static boolean isCode(@Nullable String text) {
        return text != null && text.matches("[0-9a-f]{64}");
    }

    private static JSONObject query(String token, String operation, String hash, JSONObject variables) throws Exception {
        JSONObject body = new JSONObject().put("variables", variables).put("operationName", operation)
                .put("extensions", new JSONObject().put("persistedQuery", new JSONObject().put("version", 1).put("sha256Hash", hash)));
        Request request = new Request.Builder().url(QUERY)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")))
                .header("Authorization", "Bearer " + token).header("app-platform", "WebPlayer")
                .header("Origin", "https://open.spotify.com").header("Referer", PLAYER)
                .header("Accept", "application/json").build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.code() == 401) {
                synchronized (MeeTuneSpotify.class) {
                    sToken = null; // ran out early: the next song asks for a new one
                }
            }
            if (!response.isSuccessful() || response.body() == null) throw new java.io.IOException("Spotify HTTP " + response.code());
            JSONObject data = new JSONObject(response.body().string()).optJSONObject("data");
            return data != null ? data : new JSONObject();
        }
    }

    /** The session's token: the kept one while it lasts, else the one Spotify's own page asks for. */
    @Nullable
    private static synchronized String token(Context context) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (sToken != null && now < sTokenUntil - 60_000) return sToken;
        if (now < sNoTokenBefore) return null;
        String[] got = new String[2]; // token, expiry
        CountDownLatch answered = new CountDownLatch(1);
        WebView[] web = new WebView[1];
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> web[0] = hiddenPage(context, got, answered));
        boolean ok = answered.await(30, TimeUnit.SECONDS);
        main.post(() -> {
            if (web[0] != null) {
                web[0].stopLoading();
                web[0].destroy();
            }
        });
        if (!ok || got[0] == null) {
            sNoTokenBefore = now + 10 * 60_000;
            return null;
        }
        sToken = got[0];
        sTokenUntil = got[1] != null ? Long.parseLong(got[1]) : now + 30 * 60_000;
        return sToken;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView hiddenPage(Context context, String[] got, CountDownLatch answered) {
        WebView web = new WebView(context.getApplicationContext());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.getSettings().setUserAgentString(agent(web.getSettings().getUserAgentString()));
        web.setWebViewClient(new WebViewClient() {
            // On the WebView's network thread: the carried request may block here.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost() != null ? request.getUrl().getHost() : "";
                if (!(host.endsWith("spotify.com") || host.endsWith("scdn.co") || host.endsWith("spotifycdn.com"))) return empty();
                if (!isTokenRequest(request)) return null;
                if (answered.getCount() > 0) {
                    carry(request, got);
                    answered.countDown();
                }
                return empty();
            }
        });
        web.loadUrl(PLAYER);
        return web;
    }

    /** The page's own token request, sent on with its headers and the session's cookies. */
    private static void carry(WebResourceRequest page, String[] got) {
        String url = page.getUrl().toString();
        Request.Builder request = new Request.Builder().url(url);
        for (Map.Entry<String, String> h : page.getRequestHeaders().entrySet()) {
            if (!h.getKey().equalsIgnoreCase("Accept-Encoding") && !h.getKey().equalsIgnoreCase("Cookie")) request.header(h.getKey(), h.getValue());
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) request.header("Cookie", cookies);
        try (Response response = CLIENT.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return;
            JSONObject o = new JSONObject(response.body().string());
            if (o.optBoolean("isAnonymous")) return; // signed out on Spotify's side
            String token = o.optString("accessToken");
            if (token.isEmpty()) return;
            got[0] = token;
            long until = o.optLong("accessTokenExpirationTimestampMs");
            if (until > System.currentTimeMillis()) got[1] = String.valueOf(until);
        } catch (Exception ignored) {
            // no token this time
        }
    }

    private static boolean isTokenRequest(WebResourceRequest request) {
        android.net.Uri u = request.getUrl();
        return "https".equals(u.getScheme()) && "open.spotify.com".equals(u.getHost()) && "/api/token".equals(u.getPath());
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    /** A WebView's agent without its "; wv" and "Version/x" (Spotify's sign-in refuses an embedded browser). */
    private static String agent(String webView) {
        return webView.replaceAll(";\\s*wv\\)", ")").replaceAll("\\s*Version/\\d+(\\.\\d+)*", "");
    }
}
