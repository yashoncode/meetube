package com.newtube.mobile.downloads;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * MEETUBE(issue #371): a download keeps its video's description, so the watch page shows it
 * offline too. Saved as {@code descriptions/<videoId>.txt} in the app's files, outside the
 * registry's JSON (rewritten on every progress tick).
 */
public final class DownloadDescription {
    private DownloadDescription() {
    }

    /** Writes it off the main thread. Best effort: a failure saves nothing. */
    public static void save(Context context, @Nullable String videoId, @Nullable String description) {
        if (videoId == null || description == null || description.isEmpty()) return;
        File file = file(context, videoId);
        new Thread(() -> {
            try {
                file.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(description.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // no description for this one
            }
        }, "download-description").start();
    }

    /** The saved description, or null. */
    @Nullable
    public static String read(Context context, @Nullable String videoId) {
        if (videoId == null) return null;
        File file = file(context, videoId);
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ponytail: keyed by video like DownloadSubtitles, so deleting one of two copies drops it for both.
    public static void delete(Context context, @Nullable String videoId) {
        if (videoId != null) file(context, videoId).delete();
    }

    private static File file(Context context, String videoId) {
        return new File(new File(context.getApplicationContext().getFilesDir(), "descriptions"), videoId + ".txt");
    }
}
