package com.newtube.mobile.downloads;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.newtube.mobile.player.MediaHttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/**
 * MEETUBE(subtitles): a download can bring one subtitle track with it, the language picked in its
 * dialog (remembered for the next one). Saved as {@code subtitles/<videoId>.<lang>.<ext>} in the
 * app's files; a downloaded video's playback merges it in (Media3PlayerController.openUrlList).
 */
public final class DownloadSubtitles {
    private static final String PREFS = "meetube_downloads";
    private static final String KEY_LANG = "subtitle_lang"; // "" = off

    private DownloadSubtitles() {
    }

    /** The video's own tracks (no auto-translations), one per language, manual before auto-generated. */
    public static List<MediaSubtitle> tracks(@Nullable List<MediaSubtitle> all) {
        List<MediaSubtitle> tracks = new ArrayList<>();
        if (all == null) return tracks;
        for (int pass = 0; pass < 2; pass++) {
            for (MediaSubtitle s : all) {
                if (s == null || s.getBaseUrl() == null || s.getLanguageCode() == null
                        || s.getBaseUrl().contains("&tlang=")) continue;
                boolean auto = s.getVssId() != null && s.getVssId().startsWith("a.");
                if (auto != (pass == 1)) continue;
                boolean seen = false;
                for (MediaSubtitle t : tracks) seen |= t.getLanguageCode().equals(s.getLanguageCode());
                if (!seen) tracks.add(s);
            }
        }
        return tracks;
    }

    public static String language(Context context) {
        return prefs(context).getString(KEY_LANG, "");
    }

    public static void setLanguage(Context context, String code) {
        prefs(context).edit().putString(KEY_LANG, code).apply();
    }

    /** The remembered language's track on this video, or null (off, or not offered here). */
    @Nullable
    public static MediaSubtitle pick(Context context, @Nullable List<MediaSubtitle> all) {
        String lang = language(context);
        if (lang.isEmpty()) return null;
        for (MediaSubtitle s : tracks(all)) {
            if (lang.equals(s.getLanguageCode())) return s;
        }
        return null;
    }

    /** Fetches {@code subtitle} for {@code videoId} off the main thread. Best effort: a failure saves nothing. */
    public static void save(Context context, String videoId, @Nullable MediaSubtitle subtitle) {
        if (subtitle == null || videoId == null) return;
        File dir = dir(context);
        String mime = subtitle.getMimeType();
        String ext = mime != null && mime.contains("ttml") ? "ttml" : "vtt";
        File out = new File(dir, videoId + "." + subtitle.getLanguageCode() + "." + ext);
        String url = subtitle.getBaseUrl();
        new Thread(() -> {
            // Through the media client (the shared client's InnerTube interceptors get a 403 here too).
            Request request = new Request.Builder().url(url).build();
            try (Response response = MediaHttpClient.create(OkHttpManager.instance().getClient())
                    .newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                byte[] bytes = response.body().bytes();
                if (bytes.length == 0) return;
                dir.mkdirs();
                delete(context, videoId); // one track per video
                File part = new File(dir, out.getName() + ".part");
                try (FileOutputStream stream = new FileOutputStream(part)) {
                    stream.write(bytes);
                }
                part.renameTo(out);
            } catch (Exception ignored) {
                // no subtitles for this one
            }
        }, "download-subtitles").start();
    }

    /** The saved track for {@code videoId}, or null. */
    @Nullable
    public static File find(Context context, @Nullable String videoId) {
        if (videoId == null) return null;
        File[] files = dir(context).listFiles((d, name) -> name.startsWith(videoId + ".") && !name.endsWith(".part"));
        return files != null && files.length > 0 ? files[0] : null;
    }

    /** {@code <videoId>.<lang>.<ext>}: the language. */
    public static String languageOf(File file) {
        String[] parts = file.getName().split("\\.");
        return parts.length >= 3 ? parts[parts.length - 2] : "und";
    }

    // ponytail: keyed by video, so deleting one of two copies (two qualities) drops the shared track.
    public static void delete(Context context, String videoId) {
        File file;
        while ((file = find(context, videoId)) != null && file.delete()) {
            // every language saved for it
        }
    }

    private static File dir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "subtitles");
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
