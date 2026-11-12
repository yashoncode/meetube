package com.newtube.mobile.ui.playback;

import android.util.Log;
import android.util.LruCache;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * MEETUBE(hype): a video's hype points, for the "Hype this video" card beside the comments. Only
 * the mobile web client's /next carries them signed out (frameworkUpdates > hypePointsEntity; WEB
 * and TV have none), about 20 KB gzipped. Only videos in their first week can be hyped; the rest
 * have no entity and get no card.
 */
final class HypePoints {
    private static final String TAG = "HypePoints";
    private static final String MWEB = "{\"client\":{\"clientName\":\"MWEB\",\"clientVersion\":\"2.20251101.01.00\",\"hl\":\"en\"}}";
    /** "" = none, remembered too. */
    private static final LruCache<String, String> CACHE = new LruCache<>(30);

    private HypePoints() {
    }

    /** The points as YouTube writes them ("646,030"), on a background thread; null when there are none. */
    static void fetch(String videoId, Consumer<String> done) {
        String cached = CACHE.get(videoId);
        if (cached != null) {
            done.accept(cached.isEmpty() ? null : cached);
            return;
        }
        Lyrics.POOL.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); // never before playback
            String points;
            try {
                points = parse(Lyrics.body(new Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
                        .post(RequestBody.create("{\"context\":" + MWEB + ",\"videoId\":" + JSONObject.quote(videoId) + "}",
                                MusicLink.JSON))
                        .build()));
            } catch (Exception e) {
                Log.w(TAG, videoId + ": " + e);
                done.accept(null); // offline or refused: not cached, the next open asks again
                return;
            }
            CACHE.put(videoId, points);
            done.accept(points.isEmpty() ? null : points);
        });
    }

    /** The count the card rolls up to (RollTo), else the one it starts at; "" when there is none. */
    static String parse(String json) throws Exception {
        List<JSONObject> found = new ArrayList<>();
        Lyrics.findAll(new JSONObject(json), "hypePointsEntity", found);
        for (JSONObject entity : found) {
            for (String key : new String[]{"expandedRollToNumber", "expandedRollFromNumber"}) {
                JSONObject number = entity.optJSONObject(key);
                String text = number != null ? number.optString("text") : "";
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }
}
