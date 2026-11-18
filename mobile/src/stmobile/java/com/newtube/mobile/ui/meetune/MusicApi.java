package com.newtube.mobile.ui.meetune;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.locale.LocaleManager;
import com.liskovsoft.youtubeapi.web.CookieStore;
import com.liskovsoft.youtubeapi.web.WebSession;
import com.newtube.mobile.ui.signin.WebSignInState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * MEETUNE: YouTube Music's own InnerTube (client WEB_REMIX at music.youtube.com). Signed out it
 * answers as a visitor; with MeeTube's web sign-in it sends the same .youtube.com cookies with the
 * music origin's SAPISIDHASH, so Home, Library and the mixes are the account's. Blocking: call from
 * a worker ({@link MeeTune#run}).
 */
final class MusicApi {
    static final String ORIGIN = "https://music.youtube.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    // Its own client: the shared one's interceptors add the TV account to youtube.com calls.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS).build();

    static final int SONG = 0;
    static final int CARD = 1;
    static final int MOOD = 2;

    /** Search's filters, as YouTube Music's chips: All, Songs, Videos, Albums, Artists, Playlists. */
    static final String[] FILTERS = {null, "EgWKAQIIAWoMEA4QChADEAQQCRAF", "EgWKAQIQAWoMEA4QChADEAQQCRAF",
            "EgWKAQIYAWoMEA4QChADEAQQCRAF", "EgWKAQIgAWoMEA4QChADEAQQCRAF", "EgWKAQIoAWoMEA4QChADEAQQCRAF"};

    static final class Item {
        String title;
        /** Marked explicit (the E before its name). */
        boolean explicit;
        String subtitle;
        String art;
        String videoId;
        String playlistId;
        String browseId;
        String params;
        int kind;
        int color;
        boolean round;
        /** A drawable drawn on its own cover when it has no art (MeeTune's Downloads); 0 for none. */
        int icon;
        /** A song's length ("2:28"); an album track's number (0: none). */
        String duration;
        int number;
        /** A page head's artist line, its "Album • 2026 • 17 songs • 37 minutes", and what saves it. */
        String artist;
        String meta;
        String saveId;
        boolean saved;
        /** The album and the first artist its line links to: the song menu's Open album and Open artist. */
        String album;
        String albumId;
        String artistId;
        /** A download still coming (MeeTune's Downloads): how far, 0..100, and how fast; -1 for none. */
        int downloadPercent = -1;
        String downloadSpeed;

        boolean playable() {
            return videoId != null;
        }
    }

    static final class Shelf {
        String title;
        final List<Item> items = new ArrayList<>();
        String moreBrowseId;
        String moreParams;
        /** Album/playlist/artist header: one big item over the tracks. */
        boolean header;
        /** Search's top result: its first item big, with Play and Playlist. */
        boolean top;
        /** Home's "Listen again" (its header carries the listener's name above the title). */
        boolean dial;

        boolean allSongs() {
            for (Item item : items) {
                if (item.kind != SONG) return false;
            }
            return !items.isEmpty();
        }

        boolean allMoods() {
            for (Item item : items) {
                if (item.kind != MOOD) return false;
            }
            return !items.isEmpty();
        }
    }

    private MusicApi() {
    }

    /** Pages fetched in the last few minutes (albums, artists, playlists, Explore), opened again at once. */
    private static final java.util.Map<String, Object[]> PAGES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PAGE_TTL_MS = 10 * 60_000;

    /** The account's own pages change with every like or save: never from memory. */
    private static boolean keep(String browseId) {
        return !browseId.startsWith("FEmusic_library") && !browseId.startsWith("FEmusic_liked")
                && !browseId.startsWith("FEmusic_history") && !"VLLM".equals(browseId) && !"FEmusic_home".equals(browseId);
    }

    static List<Shelf> browse(Context context, String browseId, @Nullable String params) throws Exception {
        JSONObject body = new JSONObject().put("browseId", browseId);
        if (params != null) body.put("params", params);
        String key = browseId + "|" + params + "|" + signedIn(context);
        Object[] kept = keep(browseId) ? PAGES.get(key) : null;
        JSONObject response;
        if (kept != null && System.currentTimeMillis() - (Long) kept[0] < PAGE_TTL_MS) {
            response = (JSONObject) kept[1];
        } else {
            response = post(context, "browse", body);
            if (keep(browseId)) PAGES.put(key, new Object[] {System.currentTimeMillis(), response});
        }
        List<Shelf> shelves = new ArrayList<>();
        Shelf header = header(response);
        if (header != null) {
            Item head = header.items.get(0);
            if (head.saveId == null && browseId.startsWith("VL") && !"VLLM".equals(browseId)) {
                head.saveId = browseId.substring(2);
            }
            shelves.add(header);
        }
        walk(response.opt("contents"), shelves);
        return shelves;
    }

    static List<Shelf> search(Context context, String query, @Nullable String filter) throws Exception {
        List<Shelf> shelves = new ArrayList<>();
        JSONObject body = new JSONObject().put("query", query);
        if (filter != null) body.put("params", filter);
        walk(post(context, "search", body).opt("contents"), shelves);
        return shelves;
    }

    /** What music.youtube.com suggests as {@code input} is typed: words to search, then songs, albums and artists. */
    static final class Suggestions {
        final List<String> words = new ArrayList<>();
        final Shelf songs = new Shelf();
    }

    /** With nothing typed, a signed-in account's recent searches. */
    static Suggestions suggestions(Context context, String input) throws Exception {
        Suggestions out = new Suggestions();
        JSONArray sections = post(context, "music/get_search_suggestions", new JSONObject().put("input", input))
                .optJSONArray("contents");
        for (int i = 0; sections != null && i < sections.length(); i++) {
            Object rows = path(sections.optJSONObject(i), "searchSuggestionsSectionRenderer", "contents");
            for (int j = 0; rows instanceof JSONArray && j < ((JSONArray) rows).length(); j++) {
                JSONObject wrapper = ((JSONArray) rows).optJSONObject(j);
                JSONObject row = first(wrapper, "searchSuggestionRenderer", "historySuggestionRenderer");
                String text = row != null ? text(row.opt("suggestion")) : null;
                if (text != null && !text.isEmpty()) out.words.add(text);
                Item item = row == null ? item(wrapper) : null;
                if (item != null) out.songs.items.add(item);
            }
        }
        return out;
    }

    static boolean signedIn(Context context) {
        return MeeTune.signedIn(context) && WebSession.getCookieStore() != null;
    }

    /** Saves a playlist or album to the library (a like), or takes it out. */
    static boolean save(Context context, String playlistId, boolean save) throws Exception {
        post(context, save ? "like/like" : "like/removelike",
                new JSONObject().put("target", new JSONObject().put("playlistId", playlistId)));
        return save;
    }

    /**
     * The queue YouTube Music builds around a song, the song first: each with its square art, title,
     * artist and album links. {@code playlistId} (a radio's RDAMVM..., an album's) gives that queue.
     */
    static List<Item> radio(Context context, String videoId, @Nullable String playlistId) throws Exception {
        JSONObject body = new JSONObject().put("videoId", videoId).put("isAudioOnly", true);
        if (playlistId != null) body.put("playlistId", playlistId);
        List<JSONObject> panels = new java.util.ArrayList<>();
        panels(post(context, "next", body), panels, 0);
        List<Item> out = new java.util.ArrayList<>();
        for (JSONObject renderer : panels) {
            Item item = new Item();
            item.videoId = renderer.optString("videoId", null);
            item.title = text(renderer.opt("title"));
            item.subtitle = text(renderer.opt("longBylineText"));
            item.art = thumb(renderer);
            item.playlistId = playlistId;
            links(item, renderer.opt("longBylineText"));
            if (item.artistId != null) item.artist = artists(renderer.opt("longBylineText"));
            if (item.videoId != null && item.title != null) out.add(item);
        }
        return out;
    }

    /** Whether the signed-in account likes the song: its like button in YouTube Music's player page. */
    static boolean liked(Context context, String videoId) throws Exception {
        String raw = post(context, "next", new JSONObject().put("videoId", videoId).put("isAudioOnly", true)).toString();
        String key = "\"likeButtonRenderer\"";
        for (int at = raw.indexOf(key); at >= 0; at = raw.indexOf(key, at + 1)) {
            String near = raw.substring(at, Math.min(raw.length(), at + 800));
            if (near.contains("\"videoId\":\"" + videoId + "\"")) return near.contains("\"likeStatus\":\"LIKE\"");
        }
        return false;
    }

    /**
     * The song's official music video (itself when it is one), from its twins in YouTube Music's player
     * page; null when it has none. Only what YouTube Music calls official (MUSIC_VIDEO_TYPE_OMV): a
     * user upload or a live stage take paired with the song is no canvas.
     */
    @Nullable
    static String musicVideo(Context context, String videoId) throws Exception {
        JSONObject next = post(context, "next", new JSONObject().put("videoId", videoId).put("isAudioOnly", true));
        List<JSONObject> found = new ArrayList<>();
        collect(next, "playlistPanelVideoWrapperRenderer", found, 0);
        for (JSONObject twins : found) {
            List<JSONObject> both = new ArrayList<>();
            JSONObject song = obj(twins, "primaryRenderer", "playlistPanelVideoRenderer");
            if (song != null) both.add(song);
            JSONArray counterparts = twins.optJSONArray("counterpart");
            for (int i = 0; counterparts != null && i < counterparts.length(); i++) {
                JSONObject twin = obj(counterparts.optJSONObject(i), "counterpartRenderer", "playlistPanelVideoRenderer");
                if (twin != null) both.add(twin);
            }
            boolean ours = false;
            for (JSONObject r : both) ours |= videoId.equals(r.optString("videoId"));
            if (!ours) continue;
            for (JSONObject r : both) if (official(r)) return r.optString("videoId", null);
            return null;
        }
        found.clear();
        collect(next, "playlistPanelVideoRenderer", found, 0); // no twin: the song itself may be the video
        for (JSONObject r : found) if (videoId.equals(r.optString("videoId")) && official(r)) return videoId;
        return null;
    }

    /**
     * The YouTube Music song that is this track (an import's): the same title, one of its artists, and
     * within 5 s of its length when both are known; null when no result is sure. Spotify's "Song - Remastered
     * 2011" is matched as "Song".
     */
    @Nullable
    static Item findSong(Context context, String title, List<String> artists, long durationMs) throws Exception {
        String plain = title.replaceFirst("\\s+-\\s+.*$", "");
        String wantTitle = MeeTuneCanvas.normal(plain);
        for (Shelf shelf : search(context, plain + " " + (artists.isEmpty() ? "" : artists.get(0)), FILTERS[1])) {
            for (Item item : shelf.items) {
                if (item.kind != SONG || item.videoId == null) continue;
                if (!MeeTuneCanvas.sameTitle(wantTitle, MeeTuneCanvas.normal(item.title))) continue;
                String by = MeeTuneCanvas.normal(item.artist != null ? item.artist : item.subtitle);
                boolean artistOk = artists.isEmpty();
                for (String a : artists) {
                    String name = MeeTuneCanvas.normal(a);
                    artistOk |= !name.isEmpty() && MeeTuneCanvas.words(by, name);
                }
                if (!artistOk) continue;
                long ms = MeeTuneCanvas.durationMs(item.duration != null ? item.duration : lastTime(item.subtitle));
                if (durationMs > 0 && ms > 0 && Math.abs(ms - durationMs) > 5000) continue;
                return item;
            }
        }
        return null;
    }

    /** "Artist • Album • 3:45" -> "3:45". */
    @Nullable
    private static String lastTime(@Nullable String subtitle) {
        if (subtitle == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(:\\d{2}){1,2})\\s*$").matcher(subtitle);
        return m.find() ? m.group(1) : null;
    }

    private static boolean official(JSONObject renderer) {
        return "MUSIC_VIDEO_TYPE_OMV".equals(path(renderer, "navigationEndpoint", "watchEndpoint",
                "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"));
    }

    private static void collect(@Nullable Object node, String key, List<JSONObject> out, int depth) {
        if (depth > 40) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject hit = o.optJSONObject(key);
            if (hit != null) {
                out.add(hit);
                return;
            }
            for (java.util.Iterator<String> keys = o.keys(); keys.hasNext(); ) collect(o.opt(keys.next()), key, out, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), key, out, depth + 1);
        }
    }

    /**
     * Every song in a queue panel, in order. A song with a music video comes as twins (the song and its
     * video, two ids); the video's twin is added as a copy of the song under the video's id, so it
     * shows the song's square art whichever of the two plays.
     */
    private static void panels(@Nullable Object node, List<JSONObject> out, int depth) {
        if (depth > 40) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject panel = o.optJSONObject("playlistPanelVideoRenderer");
            if (panel != null) {
                out.add(panel);
                return;
            }
            JSONObject twins = o.optJSONObject("playlistPanelVideoWrapperRenderer");
            if (twins != null) {
                JSONObject song = obj(twins, "primaryRenderer", "playlistPanelVideoRenderer");
                if (song == null) return;
                out.add(song);
                JSONArray counterparts = twins.optJSONArray("counterpart");
                for (int i = 0; counterparts != null && i < counterparts.length(); i++) {
                    JSONObject twin = obj(counterparts.optJSONObject(i), "counterpartRenderer", "playlistPanelVideoRenderer");
                    String id = twin != null ? twin.optString("videoId", null) : null;
                    if (id == null) continue;
                    try {
                        out.add(new JSONObject(song.toString()).put("videoId", id));
                    } catch (org.json.JSONException ignored) {
                    }
                }
                return;
            }
            for (java.util.Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                panels(o.opt(keys.next()), out, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                panels(a.opt(i), out, depth + 1);
            }
        }
    }

    /** A song's thumbs: {@code like/like}, {@code like/dislike} or {@code like/removelike}. */
    static boolean rate(Context context, String videoId, String action) throws Exception {
        post(context, "like/" + action, new JSONObject().put("target", new JSONObject().put("videoId", videoId)));
        return true;
    }

    private static JSONObject post(Context context, String endpoint, JSONObject body) throws Exception {
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        String version = "1." + day.format(new Date()) + ".01.00"; // as music.youtube.com sends today
        LocaleManager locale = LocaleManager.instance();
        body.put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", version)
                .put("hl", locale.getLanguage()).put("gl", locale.getCountry())));
        Request.Builder request = new Request.Builder()
                .url(ORIGIN + "/youtubei/v1/" + endpoint + "?prettyPrint=false")
                .post(RequestBody.create(body.toString(), JSON))
                .header("Origin", ORIGIN).header("X-Origin", ORIGIN).header("Referer", ORIGIN + "/")
                .header("User-Agent", UA).header("X-Youtube-Client-Name", "67")
                .header("X-Youtube-Client-Version", version);
        CookieStore store = signedIn(context) ? WebSession.getCookieStore() : null;
        if (store == null) {
            return execute(request.build(), null);
        }
        // Same lock as WebSession: sign-out cannot race a rotated cookie back in.
        synchronized (store) {
            String cookies = store.get(ORIGIN + "/");
            String auth = WebSession.authorization(cookies, System.currentTimeMillis() / 1000, ORIGIN);
            if (auth != null) {
                request.header("Cookie", cookies).header("Authorization", auth)
                        .header("X-Goog-AuthUser", String.valueOf(WebSession.getAuthUser()));
            }
            return execute(request.build(), auth != null ? store : null);
        }
    }

    private static JSONObject execute(Request request, @Nullable CookieStore store) throws IOException {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (store != null) {
                for (String cookie : response.headers("Set-Cookie")) store.put(ORIGIN + "/", cookie);
            }
            if (!response.isSuccessful()) {
                throw new IOException("YouTube Music returned HTTP " + response.code());
            }
            try {
                return new JSONObject(response.body() != null ? response.body().string() : "{}");
            } catch (org.json.JSONException e) {
                throw new IOException("Unreadable YouTube Music response");
            }
        }
    }

    // ----------------------------------------------------------------------------- parsing
    // A walk, not fixed paths: YouTube moves the wrappers around (tabs, twoColumn, sectionList)
    // far more often than it renames the shelves and items themselves.

    private static void walk(@Nullable Object node, List<Shelf> out) {
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) walk(array.opt(i), out);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        JSONObject carousel = first(o, "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer");
        if (carousel != null) {
            JSONObject header = first(carousel.optJSONObject("header"),
                    "musicCarouselShelfBasicHeaderRenderer", "musicImmersiveCarouselShelfBasicHeaderRenderer");
            Shelf shelf = new Shelf();
            if (header != null) {
                shelf.title = text(header.opt("title"));
                shelf.dial = header.has("strapline") && header.has("thumbnail")
                        || "listen again".equalsIgnoreCase(shelf.title) || "speed dial".equalsIgnoreCase(shelf.title);
                JSONObject more = obj(header, "moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint");
                if (more != null) {
                    shelf.moreBrowseId = more.optString("browseId", null);
                    shelf.moreParams = more.optString("params", null);
                }
            }
            items(carousel.optJSONArray("contents"), shelf);
            add(out, shelf);
            return;
        }
        JSONObject list = first(o, "musicShelfRenderer", "musicPlaylistShelfRenderer");
        if (list != null) {
            Shelf shelf = new Shelf();
            shelf.title = text(list.opt("title"));
            items(list.optJSONArray("contents"), shelf);
            add(out, shelf);
            return;
        }
        // Search now sends each result in its own untitled section: a run of them is one list.
        JSONObject section = o.optJSONObject("itemSectionRenderer");
        JSONArray sectionItems = section != null ? section.optJSONArray("contents") : null;
        if (sectionItems != null && item(sectionItems.optJSONObject(0)) != null) {
            Shelf last = out.isEmpty() ? null : out.get(out.size() - 1);
            Shelf shelf = last != null && last.title == null && !last.top ? last : new Shelf();
            items(sectionItems, shelf);
            if (shelf != last) add(out, shelf);
            return;
        }
        JSONObject grid = o.optJSONObject("gridRenderer");
        if (grid != null) {
            Shelf shelf = new Shelf();
            shelf.title = text(path(grid, "header", "gridHeaderRenderer", "title"));
            items(grid.optJSONArray("items"), shelf);
            add(out, shelf);
            return;
        }
        JSONObject card = o.optJSONObject("musicCardShelfRenderer"); // search's top result
        if (card != null) {
            Item item = new Item();
            item.title = text(card.opt("title"));
            item.subtitle = text(card.opt("subtitle"));
            item.art = thumb(obj(card, "thumbnail", "musicThumbnailRenderer"));
            endpoint(item, card.optJSONObject("onTap"));
            endpoint(item, firstRunEndpoint(card.opt("title")));
            item.kind = item.videoId != null ? SONG : CARD;
            item.artist = artists(card.opt("subtitle"));
            links(item, card.opt("subtitle"));
            item.explicit = explicit(card.optJSONArray("subtitleBadges"));
            Shelf shelf = new Shelf();
            shelf.top = true;
            shelf.title = text(path(card, "header", "musicCardShelfHeaderBasicRenderer", "title"));
            if (item.title != null && (item.videoId != null || item.browseId != null)) shelf.items.add(item);
            items(card.optJSONArray("contents"), shelf);
            add(out, shelf);
            return;
        }
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.endsWith("HeaderRenderer") && !"header".equals(key)) walk(o.opt(key), out);
        }
    }

    private static void add(List<Shelf> out, Shelf shelf) {
        if (!shelf.items.isEmpty()) out.add(shelf);
    }

    private static void items(@Nullable JSONArray contents, Shelf shelf) {
        if (contents == null) return;
        for (int i = 0; i < contents.length(); i++) {
            Item item = item(contents.optJSONObject(i));
            if (item != null) shelf.items.add(item);
        }
    }

    @Nullable
    private static Item item(@Nullable JSONObject wrapper) {
        if (wrapper == null) return null;
        Item item = new Item();
        JSONObject r;
        if ((r = wrapper.optJSONObject("musicTwoRowItemRenderer")) != null) {
            item.kind = CARD;
            item.title = text(r.opt("title"));
            item.subtitle = text(r.opt("subtitle"));
            item.art = thumb(obj(r, "thumbnailRenderer", "musicThumbnailRenderer"));
            links(item, r.opt("subtitle"));
            endpoint(item, r.optJSONObject("navigationEndpoint"));
        } else if ((r = wrapper.optJSONObject("musicResponsiveListItemRenderer")) != null) {
            JSONArray columns = r.optJSONArray("flexColumns");
            StringBuilder subtitle = new StringBuilder();
            for (int c = 0; columns != null && c < columns.length(); c++) {
                Object text = path(columns.optJSONObject(c), "musicResponsiveListItemFlexColumnRenderer", "text");
                String value = text(text);
                if (c == 0) {
                    item.title = value;
                    endpoint(item, firstRunEndpoint(text));
                } else if (value != null && !value.isEmpty()) {
                    links(item, text);
                    if (item.artist == null) item.artist = artists(text);
                    if (subtitle.length() > 0) subtitle.append(" • ");
                    subtitle.append(value);
                }
            }
            item.subtitle = subtitle.toString();
            JSONArray fixed = r.optJSONArray("fixedColumns");
            if (fixed != null && fixed.length() > 0) {
                item.duration = text(path(fixed.optJSONObject(0), "musicResponsiveListItemFixedColumnRenderer", "text"));
            }
            item.art = thumb(obj(r, "thumbnail", "musicThumbnailRenderer"));
            item.explicit = explicit(r.optJSONArray("badges"));
            JSONObject data = r.optJSONObject("playlistItemData");
            if (data != null && item.videoId == null) item.videoId = data.optString("videoId", null);
            endpoint(item, obj(r, "overlay", "musicItemThumbnailOverlayRenderer", "content",
                    "musicPlayButtonRenderer", "playNavigationEndpoint"));
            endpoint(item, r.optJSONObject("navigationEndpoint"));
            item.kind = item.videoId != null ? SONG : CARD;
        } else if ((r = wrapper.optJSONObject("musicNavigationButtonRenderer")) != null) {
            item.kind = MOOD;
            item.title = text(r.opt("buttonText"));
            JSONObject solid = r.optJSONObject("solid");
            item.color = solid != null ? (int) solid.optLong("leftStripeColor") : 0;
            endpoint(item, r.optJSONObject("clickCommand"));
        } else if ((r = wrapper.optJSONObject("musicMultiRowListItemRenderer")) != null) {
            item.kind = SONG;
            item.title = text(r.opt("title"));
            item.subtitle = text(r.opt("subtitle"));
            item.art = thumb(obj(r, "thumbnail", "musicThumbnailRenderer"));
            endpoint(item, r.optJSONObject("onTap"));
            if (item.videoId == null) item.kind = CARD;
        } else {
            return null;
        }
        return item.title != null && (item.videoId != null || item.browseId != null) ? item : null;
    }

    /** The artists in a "Song • A, B • 3:30" line, from their links on: in any language. */
    @Nullable
    private static void links(Item item, @Nullable Object text) {
        JSONArray runs = text instanceof JSONObject ? ((JSONObject) text).optJSONArray("runs") : null;
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            JSONObject browse = obj(run, "navigationEndpoint", "browseEndpoint");
            if (browse == null) continue;
            String page = String.valueOf(path(browse, "browseEndpointContextSupportedConfigs",
                    "browseEndpointContextMusicConfig", "pageType"));
            String id = browse.optString("browseId", null);
            if (page.contains("ALBUM") && item.albumId == null) {
                item.albumId = id;
                item.album = run.optString("text");
            } else if ((page.contains("ARTIST") || page.contains("USER_CHANNEL")) && item.artistId == null) {
                item.artistId = id;
            }
        }
    }

    private static String artists(@Nullable Object text) {
        if (!(text instanceof JSONObject)) return null;
        JSONArray runs = ((JSONObject) text).optJSONArray("runs");
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            String page = String.valueOf(path(run, "navigationEndpoint", "browseEndpoint",
                    "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType"));
            if (page.contains("ARTIST") || page.contains("USER_CHANNEL")) in = true;
            if (!in) continue;
            String part = run.optString("text");
            if (part.contains("•")) break;
            out.append(part);
        }
        return out.length() > 0 ? out.toString().trim() : null;
    }

    private static boolean explicit(@Nullable JSONArray badges) {
        for (int i = 0; badges != null && i < badges.length(); i++) {
            Object icon = path(badges.optJSONObject(i), "musicInlineBadgeRenderer", "icon", "iconType");
            if ("MUSIC_EXPLICIT_BADGE".equals(icon)) return true;
        }
        return false;
    }

    /** First endpoint wins: an item's own link is read before its play overlay's. */
    private static void endpoint(Item item, @Nullable JSONObject endpoint) {
        if (endpoint == null) return;
        JSONObject watch = endpoint.optJSONObject("watchEndpoint");
        if (watch != null && item.videoId == null && item.browseId == null) {
            item.videoId = watch.optString("videoId", null);
            item.playlistId = watch.optString("playlistId", null);
            return;
        }
        JSONObject browse = endpoint.optJSONObject("browseEndpoint");
        if (browse != null && item.browseId == null && item.videoId == null) {
            item.browseId = browse.optString("browseId", null);
            item.params = browse.optString("params", null);
            String page = String.valueOf(path(browse, "browseEndpointContextSupportedConfigs",
                    "browseEndpointContextMusicConfig", "pageType"));
            item.round = page.contains("ARTIST") || page.contains("USER_CHANNEL");
        }
    }

    @Nullable
    private static JSONObject firstRunEndpoint(@Nullable Object text) {
        if (!(text instanceof JSONObject)) return null;
        JSONArray runs = ((JSONObject) text).optJSONArray("runs");
        JSONObject run = runs != null ? runs.optJSONObject(0) : null;
        return run != null ? run.optJSONObject("navigationEndpoint") : null;
    }

    /** An album, playlist, artist or mood page's head: title, subtitle and big art. */
    @Nullable
    private static Shelf header(JSONObject response) {
        JSONObject found = findHeader(response, 0);
        if (found == null) return null;
        Item item = new Item();
        item.kind = CARD;
        item.title = text(found.opt("title"));
        String sub = text(found.opt("straplineTextOne"));
        String sub2 = text(found.opt("subtitle"));
        item.subtitle = sub != null && sub2 != null ? sub + " • " + sub2 : sub != null ? sub : sub2;
        item.artist = sub;
        String sub3 = text(found.opt("secondSubtitle"));
        item.meta = sub2 != null && sub3 != null ? sub2 + " • " + sub3 : sub2 != null ? sub2 : sub3;
        // Its save (like) button: what it saves and whether it already is.
        String raw = response.toString();
        int like = raw.indexOf("\"likeButtonRenderer\"");
        if (like >= 0) {
            String near = raw.substring(like, Math.min(raw.length(), like + 1200));
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"playlistId\":\"([^\"]+)\"").matcher(near);
            if (id.find()) item.saveId = id.group(1);
            item.saved = near.contains("\"likeStatus\":\"LIKE\"");
        }
        Object thumbnail = path(found, "thumbnail", "musicThumbnailRenderer");
        if (thumbnail == null) thumbnail = path(found, "thumbnail", "croppedSquareThumbnailRenderer");
        item.art = thumbnail instanceof JSONObject ? thumb((JSONObject) thumbnail) : null;
        if (item.title == null) return null;
        Shelf shelf = new Shelf();
        shelf.header = true;
        shelf.items.add(item);
        return shelf;
    }

    @Nullable
    private static JSONObject findHeader(Object node, int depth) {
        if (depth > 12) return null;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findHeader(array.opt(i), depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = o.opt(key);
                if (key.endsWith("HeaderRenderer") && key.startsWith("music") && value instanceof JSONObject
                        && ((JSONObject) value).has("title")) {
                    return (JSONObject) value;
                }
                if (!key.endsWith("ShelfRenderer")) {
                    JSONObject found = findHeader(value, depth + 1);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String thumb(@Nullable JSONObject renderer) {
        Object nested = path(renderer, "thumbnail", "thumbnails");
        JSONArray thumbs = nested instanceof JSONArray ? (JSONArray) nested : null;
        if (thumbs == null && renderer != null) thumbs = renderer.optJSONArray("thumbnails");
        if (thumbs == null || thumbs.length() == 0) return null;
        String url = thumbs.optJSONObject(thumbs.length() - 1).optString("url", null);
        return url != null ? sized(url, 544) : null;
    }

    /** googleusercontent art is any size asked for: =w120-h120 becomes =w544-h544. */
    static String sized(String url, int px) {
        return url.replaceFirst("=w\\d+-h\\d+", "=w" + px + "-h" + px);
    }

    @Nullable
    private static String text(@Nullable Object value) {
        if (!(value instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) value;
        if (o.has("simpleText")) return o.optString("simpleText");
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null) return null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) text.append(runs.optJSONObject(i).optString("text"));
        return text.toString();
    }

    @Nullable
    private static JSONObject first(@Nullable JSONObject o, String... keys) {
        if (o == null) return null;
        for (String key : keys) {
            JSONObject value = o.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    @Nullable
    private static JSONObject obj(@Nullable JSONObject o, String... keys) {
        Object node = path(o, keys);
        return node instanceof JSONObject ? (JSONObject) node : null;
    }

    @Nullable
    private static Object path(@Nullable JSONObject o, String... keys) {
        Object node = o;
        for (String key : keys) {
            if (!(node instanceof JSONObject)) return null;
            node = ((JSONObject) node).opt(key);
        }
        return node;
    }
}
