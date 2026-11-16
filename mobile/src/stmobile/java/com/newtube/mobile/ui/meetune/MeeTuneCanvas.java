package com.newtube.mobile.ui.meetune;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A song's moving cover: a short looping clip from the community canvas index (the one BitChord
 * reads), one public JSON file of song + artist -> clip. Small and hit-or-miss: most songs have
 * none and keep their still cover. The index is fetched once per half hour, and every answer,
 * a miss too, is remembered for the song.
 *
 * <p>Fetched with a plain connection, not the app's InnerTube client: its interceptors add
 * YouTube's headers, which have no business going to someone else's server.
 */
public final class MeeTuneCanvas {
    private static final String INDEX = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json";
    private static final long INDEX_TTL_MS = 30L * 60 * 1000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** Clip by video id; "" is a song with none. */
    private static final Map<String, String> FOUND = new HashMap<>();

    private static List<String[]> sIndex = new ArrayList<>(); // song, artist, album, url
    private static long sIndexAt;

    private MeeTuneCanvas() {
    }

    /** The song's clip, or null, on the main thread. */
    public static void find(Video video, Consumer<String> done) {
        if (video == null || video.videoId == null) return;
        String id = video.videoId;
        MusicApi.Item item = MeeTune.item(video);
        String title = item.title != null ? item.title : video.getTitle();
        String artist = item.artist != null ? item.artist : MeeTune.artist(item.subtitle != null ? item.subtitle : video.getAuthor());
        String album = item.album;
        WORKER.execute(() -> {
            String url;
            synchronized (FOUND) {
                url = FOUND.get(id);
            }
            if (url == null) {
                url = match(index(), title, artist, album);
                synchronized (FOUND) {
                    FOUND.put(id, url != null ? url : "");
                }
            }
            String clip = url == null || url.isEmpty() ? null : url;
            new Handler(Looper.getMainLooper()).post(() -> done.accept(clip));
        });
    }

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
            HttpURLConnection connection = (HttpURLConnection) new URL(INDEX).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) return sIndex;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) body.write(buffer, 0, n);
            }
            JSONArray items = new JSONObject(body.toString("UTF-8")).getJSONArray("items");
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
}
