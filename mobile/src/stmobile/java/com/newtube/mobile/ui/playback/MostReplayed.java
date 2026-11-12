package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * MEETUBE(most replayed): YouTube's "Most replayed" graph over the seek bar. Only the WEB client's
 * /next carries it (frameworkUpdates > macroMarkersListEntity, markerType MARKER_TYPE_HEATMAP: about
 * 100 even slices of the video, each scored 0-1); the TV /next the player uses has none. So one
 * anonymous WEB /next per video, off the main thread; a video without one (new, few views) gets
 * an empty answer and no graph, as on YouTube.
 */
public final class MostReplayed {
    private static final String TAG = "MostReplayed";
    private static final String WEB = "{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20251101.00.00\",\"hl\":\"en\"}}";
    /** Back and forth between a few videos asks once each. Empty arrays are remembered too. */
    private static final LruCache<String, float[]> CACHE = new LruCache<>(30);

    private MostReplayed() {
    }

    public static boolean isOn(Context context) {
        return context.getSharedPreferences("meetube_app", Context.MODE_PRIVATE).getBoolean("most_replayed", true);
    }

    public static void setOn(Context context, boolean on) {
        context.getSharedPreferences("meetube_app", Context.MODE_PRIVATE).edit().putBoolean("most_replayed", on).apply();
    }

    /** The scores, evenly spaced over the video, on a background thread; null when there are none. */
    public static void fetch(String videoId, Consumer<float[]> done) {
        float[] cached = CACHE.get(videoId);
        if (cached != null) {
            done.accept(cached.length > 0 ? cached : null);
            return;
        }
        Lyrics.POOL.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); // never before playback
            float[] scores = new float[0];
            try {
                scores = parse(Lyrics.body(new Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
                        .post(RequestBody.create("{\"context\":" + WEB + ",\"videoId\":" + JSONObject.quote(videoId) + "}",
                                MusicLink.JSON))
                        .build()));
            } catch (Exception e) {
                Log.w(TAG, videoId + ": " + e);
                done.accept(null); // offline or refused: not cached, the next open asks again
                return;
            }
            CACHE.put(videoId, scores);
            done.accept(scores.length > 0 ? scores : null);
        });
    }

    static float[] parse(String json) throws Exception {
        List<JSONObject> lists = new ArrayList<>();
        Lyrics.findAll(new JSONObject(json), "markersList", lists);
        for (JSONObject list : lists) {
            if (!"MARKER_TYPE_HEATMAP".equals(list.optString("markerType"))) {
                continue;
            }
            JSONArray markers = list.optJSONArray("markers");
            if (markers == null || markers.length() < 2) {
                continue;
            }
            float[] scores = new float[markers.length()];
            for (int i = 0; i < scores.length; i++) {
                JSONObject marker = markers.optJSONObject(i);
                scores[i] = marker != null ? (float) marker.optDouble("intensityScoreNormalized", 0) : 0;
            }
            return scores;
        }
        return new float[0];
    }
}
