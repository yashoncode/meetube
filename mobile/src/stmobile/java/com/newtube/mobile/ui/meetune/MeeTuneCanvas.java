package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.newtube.mobile.downloads.ClipRanges;
import com.newtube.mobile.downloads.StreamFetcher;
import com.newtube.mobile.player.Media3SourceFactory;
import com.newtube.mobile.ui.playback.MostReplayed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A song's canvas: a short silent loop over its cover. Asked of, in order:
 * <ol>
 * <li>Spotify's own canvas, when "Spotify canvas first" is on and Spotify is signed in ({@link MeeTuneSpotify});</li>
 * <li>an animated cover: Apple Music's motion artwork, Tidal's video cover, then the community canvas
 *     list (the one BitChord reads);</li>
 * <li>a music-video loop: a few seconds of the song's official music video, picked from its most
 *     replayed moment, fetched once (on Wi-Fi unless allowed on mobile data) and played from the phone after.</li>
 * </ol>
 * Each source can be switched off in MeeTune's settings; a song can be left without one, or without its
 * video, from the player's menu. Cover and Spotify answers are remembered for the run, a miss too.
 *
 * <p>Everything here uses its own plain connection, not the app's InnerTube client: its interceptors
 * add YouTube's headers, which have no business going to someone else's server. The music video's
 * bytes come through {@link StreamFetcher} (MeeTube's media client), like a download's.
 */
public final class MeeTuneCanvas {
    /** What the player plays: a link or a file, the whole of it or {@code startMs..endMs} round and round. */
    public static final class Clip {
        public final String uri;
        public final long startMs;
        /** 0: the whole clip. */
        public final long endMs;

        Clip(String uri, long startMs, long endMs) {
            this.uri = uri;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final String INDEX = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json";
    private static final long INDEX_TTL_MS = 30L * 60 * 1000;
    /** How long a music-video loop runs: cut to whole fragments, so up to a fragment more. */
    private static final long LOOP_MS = 8000;
    /** A loop's fragments bigger than this are left alone (a 720p H.264 loop is about 3 MB). */
    private static final long MAX_LOOP_BYTES = 12L * 1024 * 1024;
    /** Loops kept on the phone; the least lately played go first. */
    private static final int KEEP_LOOPS = 60;
    /** Public, read only, used by Tidal's own web player. */
    private static final String TIDAL_TOKEN = "vNVdglQOjFJJGG2U";
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build();
    /** Per song id; "" is a song with none. */
    private static final Map<String, String> SPOTIFY = new HashMap<>();
    private static final Map<String, String> COVERS = new HashMap<>();
    private static final Map<String, String> VIDEOS = new HashMap<>();

    private static List<String[]> sIndex = new ArrayList<>(); // song, artist, album, url
    private static long sIndexAt;
    private static String sAppleToken;
    private static long sAppleTokenUntil;

    private MeeTuneCanvas() {
    }

    // ------------------------------------------------------------------------------ settings

    public static boolean covers(Context c) {
        return MeeTune.prefs(c).getBoolean("canvas_covers", true);
    }

    public static void setCovers(Context c, boolean on) {
        put(c, "canvas_covers", on);
    }

    public static boolean videoLoops(Context c) {
        return MeeTune.prefs(c).getBoolean("canvas_video", true);
    }

    public static void setVideoLoops(Context c, boolean on) {
        put(c, "canvas_video", on);
    }

    public static boolean spotifyFirst(Context c) {
        return MeeTune.prefs(c).getBoolean("canvas_spotify", false);
    }

    public static void setSpotifyFirst(Context c, boolean on) {
        put(c, "canvas_spotify", on);
    }

    /** Music-video loops may be fetched on mobile data (off: Wi-Fi only). Data Saver never fetches them. */
    public static boolean loopsOnMobile(Context c) {
        return MeeTune.prefs(c).getBoolean("canvas_mobile", false);
    }

    public static void setLoopsOnMobile(Context c, boolean on) {
        put(c, "canvas_mobile", on);
    }

    /** Lighter loops: cut from the 480p stream instead of 720p, about half the size. */
    public static boolean lighter(Context c) {
        return MeeTune.prefs(c).getBoolean("canvas_light", false);
    }

    public static void setLighter(Context c, boolean on) {
        put(c, "canvas_light", on);
    }

    private static void put(Context c, String key, boolean on) {
        MeeTune.prefs(c).edit().putBoolean(key, on).apply();
        synchronized (COVERS) {
            SPOTIFY.clear(); // a source switched: every song asks again
            COVERS.clear();
        }
    }

    // ------------------------------------------------------------------------------ per song

    /** The song shows no canvas at all. */
    public static boolean isOff(Context c, String songId) {
        return inSet(c, "canvas_off", songId);
    }

    public static void setOff(Context c, String songId, boolean off) {
        toSet(c, "canvas_off", songId, off);
    }

    /** The song shows no music-video loop ("Not this video"); a cover or Spotify's canvas still does. */
    public static boolean isNotVideo(Context c, String songId) {
        return inSet(c, "canvas_not_video", songId);
    }

    public static void setNotVideo(Context c, String songId, boolean not) {
        toSet(c, "canvas_not_video", songId, not);
    }

    /** The song's music-video loop moves on to its next most replayed part. */
    public static void nextPart(Context c, String songId) {
        SharedPreferences p = MeeTune.prefs(c);
        p.edit().putInt("canvas_part_" + songId, p.getInt("canvas_part_" + songId, 0) + 1).apply();
    }

    /** The song has a music video to loop (known only once it was looked up this run). */
    public static boolean hasVideo(String songId) {
        synchronized (VIDEOS) {
            String omv = VIDEOS.get(songId);
            return omv != null && !omv.isEmpty();
        }
    }

    private static boolean inSet(Context c, String key, String id) {
        return MeeTune.prefs(c).getStringSet(key, java.util.Collections.emptySet()).contains(id);
    }

    private static void toSet(Context c, String key, String id, boolean in) {
        Set<String> set = new HashSet<>(MeeTune.prefs(c).getStringSet(key, java.util.Collections.emptySet()));
        if (in) set.add(id); else set.remove(id);
        MeeTune.prefs(c).edit().putStringSet(key, set).apply();
    }

    /**
     * The canvas may move now: not in battery saver, not with the phone hot, not with animations off
     * (Reduce motion). Otherwise the still cover stays.
     */
    public static boolean mayMove(Context c) {
        android.os.PowerManager power = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (power != null && power.isPowerSaveMode()) return false;
        if (power != null && android.os.Build.VERSION.SDK_INT >= 29
                && power.getCurrentThermalStatus() >= android.os.PowerManager.THERMAL_STATUS_SEVERE) return false;
        return android.provider.Settings.Global.getFloat(c.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    // ------------------------------------------------------------------------------ lookup

    /** The song's canvas, or null, on the main thread. */
    public static void find(Context context, Video video, Consumer<Clip> done) {
        if (video == null || video.videoId == null) return;
        Context app = context.getApplicationContext();
        String id = video.videoId;
        MusicApi.Item item = MeeTune.item(video);
        String title = item.title != null ? item.title : video.getTitle();
        String artist = item.artist != null ? item.artist : MeeTune.artist(item.subtitle != null ? item.subtitle : video.getAuthor());
        String album = item.album;
        long durationMs = durationMs(item.duration);
        WORKER.execute(() -> {
            Clip clip = null;
            try {
                clip = lookup(app, id, title, artist, album, durationMs);
            } catch (Exception ignored) {
                // offline or a source hiccup: the still cover, and the next play asks again
            }
            Clip found = clip;
            new Handler(Looper.getMainLooper()).post(() -> done.accept(found));
        });
    }

    @Nullable
    private static Clip lookup(Context c, String id, @Nullable String title, @Nullable String artist,
                               @Nullable String album, long durationMs) throws Exception {
        if (isOff(c, id) || title == null || artist == null) return null;
        if (spotifyFirst(c) && MeeTuneSpotify.signedIn()) {
            String url = remembered(SPOTIFY, id, () -> MeeTuneSpotify.canvas(c, title, artist, durationMs));
            if (url != null) return new Clip(url, 0, 0);
        }
        if (covers(c)) {
            String url = remembered(COVERS, id, () -> cover(title, artist, album));
            if (url != null) return new Clip(url, 0, 0);
        }
        if (videoLoops(c) && !isNotVideo(c, id)) return videoLoop(c, id);
        return null;
    }

    private interface Source {
        @Nullable
        String get() throws Exception;
    }

    @Nullable
    private static String remembered(Map<String, String> answers, String id, Source source) throws Exception {
        String known;
        synchronized (COVERS) {
            known = answers.get(id);
        }
        if (known == null) {
            known = source.get();
            if (known == null) known = "";
            synchronized (COVERS) {
                answers.put(id, known);
            }
        }
        return known.isEmpty() ? null : known;
    }

    @Nullable
    private static String cover(String title, String artist, @Nullable String album) {
        String url = null;
        try {
            url = apple(title, artist, album);
        } catch (Exception ignored) {
        }
        if (url == null) {
            try {
                url = tidal(title, artist);
            } catch (Exception ignored) {
            }
        }
        return url != null ? url : match(index(), title, artist, album);
    }

    // ------------------------------------------------------------------------------ Apple Music

    /** Motion artwork, from Apple Music's web catalog (its page's own token), the song's or its album's. */
    @Nullable
    private static String apple(String title, String artist, @Nullable String album) throws Exception {
        String token = appleToken();
        if (token == null) return null;
        String country = Locale.getDefault().getCountry().length() == 2 ? Locale.getDefault().getCountry().toLowerCase(Locale.ROOT) : "us";
        String base = "https://amp-api.music.apple.com/v1/catalog/" + country;
        HttpUrl search = HttpUrl.get(base + "/search").newBuilder()
                .addQueryParameter("term", artist + " " + title).addQueryParameter("types", "songs")
                .addQueryParameter("limit", "10").addQueryParameter("extend", "editorialVideo")
                .addQueryParameter("include", "albums").build();
        JSONObject answer = new JSONObject(get(search.toString(), token));
        JSONObject songs = answer.optJSONObject("results") != null ? answer.optJSONObject("results").optJSONObject("songs") : null;
        JSONArray hits = songs != null ? songs.optJSONArray("data") : null;
        String wantTitle = normal(title), wantArtist = normal(artist);
        for (int i = 0; hits != null && i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            JSONObject a = hit != null ? hit.optJSONObject("attributes") : null;
            if (a == null || !sameTitle(wantTitle, normal(a.optString("name")))) continue;
            String credited = normal(a.optString("artistName"));
            if (credited.isEmpty() || !(words(wantArtist, credited) || words(credited, wantArtist))) continue;
            String url = motion(a.optJSONObject("editorialVideo"));
            if (url != null) return url;
            JSONObject rel = hit.optJSONObject("relationships");
            JSONArray albums = rel != null && rel.optJSONObject("albums") != null ? rel.optJSONObject("albums").optJSONArray("data") : null;
            String albumId = albums != null && albums.length() > 0 ? albums.optJSONObject(0).optString("id") : "";
            if (albumId.isEmpty()) continue;
            JSONArray data = new JSONObject(get(base + "/albums/" + albumId + "?extend=editorialVideo", token)).optJSONArray("data");
            JSONObject attributes = data != null && data.length() > 0 ? data.optJSONObject(0).optJSONObject("attributes") : null;
            url = attributes != null ? motion(attributes.optJSONObject("editorialVideo")) : null;
            if (url != null) return url;
        }
        return null;
    }

    /** The square motion artwork's HLS link (the tall one when there is no square). */
    @Nullable
    static String motion(@Nullable JSONObject editorial) {
        if (editorial == null) return null;
        for (String key : new String[] {"motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4"}) {
            JSONObject v = editorial.optJSONObject(key);
            String url = v != null ? v.optString("video", null) : null;
            if (url != null && url.startsWith("https://")) return url;
        }
        return null;
    }

    private static final Pattern APPLE_SCRIPT = Pattern.compile("/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\\.js");
    private static final Pattern JWT = Pattern.compile("ey[A-Za-z0-9_-]+\\.ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** The web player's catalog token, read from its own script; kept till it runs out, a miss for half an hour. */
    @Nullable
    private static synchronized String appleToken() {
        long now = System.currentTimeMillis();
        if (now < sAppleTokenUntil - 60_000) return sAppleToken;
        sAppleToken = null;
        sAppleTokenUntil = now + 30 * 60_000;
        try {
            Matcher scripts = APPLE_SCRIPT.matcher(get("https://music.apple.com/us/browse", null));
            while (scripts.find()) {
                Matcher jwts = JWT.matcher(get("https://music.apple.com" + scripts.group(), null));
                while (jwts.find()) {
                    String[] parts = jwts.group().split("\\.");
                    long exp = new JSONObject(new String(android.util.Base64.decode(parts[1],
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP), "UTF-8"))
                            .optLong("exp") * 1000;
                    if (exp > now) {
                        sAppleToken = jwts.group();
                        sAppleTokenUntil = exp;
                        return sAppleToken;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------------------ Tidal

    /** The album's video cover, from Tidal's public search. */
    @Nullable
    private static String tidal(String title, String artist) throws Exception {
        String country = Locale.getDefault().getCountry().length() == 2 ? Locale.getDefault().getCountry() : "US";
        HttpUrl url = HttpUrl.get("https://api.tidal.com/v1/search").newBuilder()
                .addQueryParameter("query", artist + " " + title).addQueryParameter("limit", "10")
                .addQueryParameter("types", "TRACKS").addQueryParameter("countryCode", country).build();
        Request request = new Request.Builder().url(url).header("X-Tidal-Token", TIDAL_TOKEN).build();
        JSONObject answer;
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            answer = new JSONObject(response.body().string());
        }
        JSONArray items = answer.optJSONObject("tracks") != null ? answer.optJSONObject("tracks").optJSONArray("items") : null;
        String wantTitle = normal(title), wantArtist = normal(artist);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject track = items.optJSONObject(i);
            if (track == null || !normal(track.optString("title")).equals(wantTitle)) continue;
            JSONArray artists = track.optJSONArray("artists");
            boolean artistOk = false;
            for (int a = 0; artists != null && a < artists.length(); a++) {
                String name = normal(artists.optJSONObject(a).optString("name"));
                artistOk |= !name.isEmpty() && (words(wantArtist, name) || words(name, wantArtist));
            }
            JSONObject album = track.optJSONObject("album");
            String cover = album != null ? album.optString("videoCover", "") : "";
            String[] parts = cover.split("-");
            if (artistOk && parts.length == 5) {
                return "https://resources.tidal.com/videos/" + String.join("/", parts) + "/1280x1280.mp4";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------ music video

    /**
     * A few seconds of the song's official music video: the file kept from before, else (when the
     * network allows) the fragments around its most replayed part, fetched now. Starts on a fragment's
     * start, which is a key frame, so the loop shows at once and comes round without a stall.
     */
    @Nullable
    private static Clip videoLoop(Context c, String songId) throws Exception {
        String omv;
        synchronized (VIDEOS) {
            omv = VIDEOS.get(songId);
        }
        if (omv == null) {
            omv = MusicApi.musicVideo(c, songId);
            synchronized (VIDEOS) {
                VIDEOS.put(songId, omv != null ? omv : "");
            }
        }
        if (omv == null || omv.isEmpty()) return null;
        int part = MeeTune.prefs(c).getInt("canvas_part_" + songId, 0);
        boolean light = lighter(c);
        File dir = new File(c.getCacheDir(), "canvas");
        String prefix = omv + "_" + part + (light ? "l" : "") + "_";
        File[] kept = dir.listFiles();
        for (int i = 0; kept != null && i < kept.length; i++) {
            Clip clip = keptLoop(kept[i], prefix);
            if (clip != null) {
                //noinspection ResultOfMethodCallIgnored
                kept[i].setLastModified(System.currentTimeMillis());
                return clip;
            }
        }
        if (!mayFetch(c)) return null;

        float[] scores = heatmap(omv);
        MediaItemFormatInfo info = YouTubeMediaItemService.instance().getSpeculativeFormatInfo(omv);
        if (info == null || info.isUnplayable()) return null;
        long lengthMs = parseLong(info.getLengthSeconds()) * 1000;
        MediaFormat format = pickFormat(info.getAdaptiveFormats(), light ? 480 : 720);
        long[] init = format != null ? ClipRanges.range(format.getInit()) : null;
        long[] index = format != null ? ClipRanges.range(format.getIndex()) : null;
        if (lengthMs < 4 * LOOP_MS || init == null || index == null || init[0] != 0 || index[1] > 1024 * 1024) return null;
        long start = partStart(scores, lengthMs, part);

        // A refused link (403) gives up for now; the next play resolves again. Never a retry of the same link.
        StreamFetcher fetcher = new StreamFetcher(Media3SourceFactory.MEDIA_USER_AGENT);
        byte[] head = fetcher.fetchBytes(format.getUrl(), 0, index[1]);
        ClipRanges.Plan plan = ClipRanges.plan(head, (int) init[1], (int) index[0], start, start + LOOP_MS);
        if (plan == null || plan.to - plan.from + 1 > MAX_LOOP_BYTES) return null;
        byte[] fragments = fetcher.fetchBytes(format.getUrl(), plan.from, plan.to);
        long end = Math.min(plan.startMs + LOOP_MS, plan.endMs);

        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File part0 = new File(dir, prefix + ".part");
        try (FileOutputStream out = new FileOutputStream(part0)) {
            out.write(plan.header);
            out.write(plan.sidx);
            out.write(fragments);
        }
        File file = new File(dir, prefix + plan.startMs + "_" + end + ".mp4");
        if (!part0.renameTo(file)) return null;
        trim(dir);
        return new Clip(android.net.Uri.fromFile(file).toString(), plan.startMs, end);
    }

    /** {@code <omv>_<part>[l]_<startMs>_<endMs>.mp4}, when it is this prefix's. */
    @Nullable
    static Clip keptLoop(File file, String prefix) {
        String name = file.getName();
        if (!name.startsWith(prefix) || !name.endsWith(".mp4")) return null;
        String[] times = name.substring(prefix.length(), name.length() - 4).split("_");
        if (times.length != 2) return null;
        long start = parseLong(times[0]), end = parseLong(times[1]);
        return end > start ? new Clip(android.net.Uri.fromFile(file).toString(), start, end) : null;
    }

    private static boolean mayFetch(Context c) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 24
                && cm.getRestrictBackgroundStatus() == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return false;
        return !cm.isActiveNetworkMetered() || loopsOnMobile(c);
    }

    /** The biggest H.264 stream up to {@code maxHeight} (every phone decodes it; VP9 and AV1 not everywhere). */
    @Nullable
    private static MediaFormat pickFormat(@Nullable List<MediaFormat> formats, int maxHeight) {
        MediaFormat best = null;
        for (int i = 0; formats != null && i < formats.size(); i++) {
            MediaFormat f = formats.get(i);
            String mime = f.getMimeType();
            if (f.getUrl() == null || f.isOtf() || mime == null || !mime.startsWith("video/mp4") || !mime.contains("avc1")
                    || f.getHeight() <= 0 || f.getHeight() > maxHeight) continue;
            if (best == null || f.getHeight() > best.getHeight()) best = f;
        }
        return best;
    }

    @Nullable
    private static float[] heatmap(String videoId) throws InterruptedException {
        float[][] out = new float[1][];
        CountDownLatch done = new CountDownLatch(1);
        MostReplayed.fetch(videoId, scores -> {
            out[0] = scores;
            done.countDown();
        });
        done.await(10, TimeUnit.SECONDS);
        return out[0];
    }

    /**
     * Where part {@code part} starts: the most replayed slices first, each at least 20 s from the ones
     * before it, never in the first 5% or the last 10%. Without a graph, through the video at set places.
     */
    static long partStart(@Nullable float[] scores, long lengthMs, int part) {
        long from = lengthMs / 20, to = lengthMs - lengthMs / 10 - LOOP_MS;
        List<Long> starts = new ArrayList<>();
        if (scores != null && scores.length > 1) {
            Integer[] order = new Integer[scores.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
            for (int i : order) {
                long at = i * lengthMs / scores.length;
                if (at < from || at > to) continue;
                boolean apart = true;
                for (long s : starts) apart &= Math.abs(s - at) >= 20_000;
                if (apart) starts.add(at);
            }
        }
        if (starts.isEmpty()) {
            for (double f : new double[] {0.3, 0.5, 0.7, 0.15}) starts.add((long) (lengthMs * f));
        }
        return starts.get(part % starts.size());
    }

    /** The oldest loops go once more than {@link #KEEP_LOOPS} are kept (each one is played as it is opened). */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP_LOOPS) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP_LOOPS; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // ------------------------------------------------------------------------------ community list

    /** Title and artist both in each other's way (contributors write "Song (Official Video)"), the album when both name one. */
    @Nullable
    static String match(List<String[]> index, @Nullable String title, @Nullable String artist, @Nullable String album) {
        String wantTitle = normal(title), wantArtist = normal(artist), wantAlbum = normal(album);
        if (wantTitle.isEmpty() || wantArtist.isEmpty()) return null;
        for (String[] entry : index) {
            String song = normal(entry[0]), credited = normal(entry[1]), listed = normal(entry[2]);
            boolean titleOk = !song.isEmpty() && (words(wantTitle, song) || words(song, wantTitle));
            boolean artistOk = !credited.isEmpty() && (words(wantArtist, credited) || words(credited, wantArtist));
            boolean albumOk = listed.isEmpty() || wantAlbum.isEmpty() || listed.equals(wantAlbum);
            if (titleOk && artistOk && albumOk) return entry[3];
        }
        return null;
    }

    /** The same title, give or take a trailing word run ("Song" and "Song Remastered"); not "Love" in "Love Is Lonely"'s place. */
    static boolean sameTitle(String want, String got) {
        return !want.isEmpty() && !got.isEmpty() && (want.equals(got) || want.startsWith(got + " ") || got.startsWith(want + " "))
                && Math.abs(want.length() - got.length()) <= 12;
    }

    /** {@code part} as whole words of {@code text} ("her" is in "her love", not in "mother"). */
    static boolean words(String text, String part) {
        return (" " + text + " ").contains(" " + part + " ");
    }

    /** Lower case, no "(...)"/"[...]", no "feat. ...", letters and digits only, single spaces. */
    static String normal(@Nullable String text) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
                .replaceAll("\\s(feat\\.?|ft\\.?|featuring)\\s.*$", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ");
        return s.trim();
    }

    private static synchronized List<String[]> index() {
        long now = System.currentTimeMillis();
        if (!sIndex.isEmpty() && now - sIndexAt < INDEX_TTL_MS) return sIndex;
        sIndexAt = now; // a failed fetch keeps what it had, and waits the half hour too
        try {
            JSONArray items = new JSONObject(get(INDEX, null)).getJSONArray("items");
            List<String[]> parsed = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null || o.optString("song").isEmpty() || o.optString("artist").isEmpty()
                        || !o.optString("url").startsWith("https://")) continue;
                parsed.add(new String[] {o.optString("song"), o.optString("artist"), o.optString("album"), o.optString("url")});
            }
            if (!parsed.isEmpty()) sIndex = parsed;
        } catch (Exception ignored) {
            // no clips this half hour: the still covers stay
        }
        return sIndex;
    }

    // ------------------------------------------------------------------------------ helpers

    private static String get(String url, @Nullable String appleToken) throws Exception {
        Request.Builder request = new Request.Builder().url(url);
        if (appleToken != null) {
            request.header("Authorization", "Bearer " + appleToken)
                    .header("Origin", "https://music.apple.com").header("Referer", "https://music.apple.com/");
        }
        try (Response response = CLIENT.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new java.io.IOException("HTTP " + response.code());
            return response.body().string();
        }
    }

    /** "3:45" or "1:02:03" in ms; 0 when unknown. */
    static long durationMs(@Nullable String text) {
        if (text == null || !text.matches("\\d+(:\\d{1,2}){1,2}")) return 0;
        long seconds = 0;
        for (String part : text.split(":")) seconds = seconds * 60 + Long.parseLong(part);
        return seconds * 1000;
    }

    private static long parseLong(@Nullable String text) {
        try {
            return text != null ? Long.parseLong(text.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
