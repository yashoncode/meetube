package com.newtube.mobile.discord;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.newtube.mobile.player.Media3PlayerController;
import com.newtube.mobile.ui.meetune.MeeTune;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * MEETUBE(discord): the player's state as a Discord activity. A MeeTune song is "Listening to
 * MeeTune", anything else "Watching MeeTube". Discord runs the progress bar on its own clock from
 * two instants, so only play, pause, seek, speed and a new video send anything; a pause takes the
 * card down, or Discord would keep counting. Updates wait 1 s, so scrubbing or a quick pause and
 * play sends one. Main thread only.
 */
public final class DiscordPresence {
    /**
     * MeeTube's and MeeTune's Discord application ids (discord.com/developers/applications). Empty
     * means no artwork and no button on the card; the rest works without them.
     */
    static final String MEETUBE_APP_ID = "1575206723822682123";
    static final String MEETUNE_APP_ID = "1575206880567890100";
    private static final long DEBOUNCE_MS = 1000;
    private static final long IDLE_CLOSE_MS = 5 * 60_000;
    private static final String TAG = "DiscordPresence";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable SEND = DiscordPresence::send;
    private static final Runnable IDLE_CLOSE = DiscordPresence::close;
    private static Context sContext;
    private static WeakReference<Media3PlayerController> sPlayer = new WeakReference<>(null);
    private static Object sPresence;
    private static String sPending; // the activity to show, null to clear
    private static String sSent;
    private static String sLiveId;
    private static long sLiveStart;

    private DiscordPresence() {
    }

    /** From the player's listener on every play, pause, seek, speed change, new video and release. */
    public static void update(Context context, Media3PlayerController player) {
        if (sContext == null) sContext = context.getApplicationContext();
        Media3PlayerController last = sPlayer.get();
        if (last != null && last != player && last.isPlaying() && !player.isPlaying()) return; // an old player stopping late
        sPlayer = new WeakReference<>(player); // kept signed out too, so signing in mid-video shows it
        if (!DiscordAddon.signedIn(sContext)) return;
        sPending = activity(player);
        MAIN.removeCallbacks(SEND);
        MAIN.postDelayed(SEND, DEBOUNCE_MS);
    }

    /** A switch changed: show the player as it is now. */
    static void refresh() {
        Media3PlayerController player = sPlayer.get();
        if (player != null && sContext != null) update(sContext, player);
    }

    /** Signed in or out: drop the session, then start again from the player as it is now. */
    static void restart() {
        MAIN.post(() -> {
            close();
            refresh();
        });
    }

    private static String activity(Media3PlayerController player) {
        Video video = player.getVideo();
        if (video == null || video.videoId == null || !player.isPlaying()) return null;
        boolean music = MeeTune.isMusic(video);
        if (!(music ? DiscordAddon.music(sContext) : DiscordAddon.videos(sContext))) return null;
        float speed = player.getSpeed() > 0 ? player.getSpeed() : 1f;
        long now = System.currentTimeMillis();
        try {
            String title = video.title != null ? video.title : "";
            if (speed != 1f) title += String.format(Locale.ROOT, " [%.2fx]", speed);
            JSONObject a = new JSONObject()
                    .put("type", music ? "listening" : "watching")
                    .put("appId", music ? MEETUNE_APP_ID : MEETUBE_APP_ID)
                    .put("name", music ? "MeeTune" : "MeeTube")
                    .put("details", title)
                    .put("state", video.getAuthor() != null ? video.getAuthor() : "")
                    .put("image", video.cardImageUrl != null && video.cardImageUrl.startsWith("https://")
                            ? video.cardImageUrl : "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg")
                    .put("url", (music ? "https://music.youtube.com/watch?v=" : "https://www.youtube.com/watch?v=") + video.videoId)
                    .put("button", sContext.getString(music ? com.liskovsoft.smartyoutubetv2.tv.R.string.discord_button_listen
                            : com.liskovsoft.smartyoutubetv2.tv.R.string.discord_button_watch));
            if (video.isLive) { // time watched, counted from when this stream started playing here
                if (!video.videoId.equals(sLiveId)) {
                    sLiveId = video.videoId;
                    sLiveStart = now;
                }
                a.put("start", sLiveStart);
            } else {
                // Rounded to the second, so the same moment published twice is the same presence.
                long start = Math.round((now - player.getPositionMs() / speed) / 1000.0) * 1000;
                a.put("start", start);
                long duration = player.getDurationMs();
                if (duration > 0) a.put("end", start + Math.round(duration / speed / 1000.0) * 1000);
            }
            return a.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void send() {
        String pending = sPending;
        if (pending == null ? sSent == null : pending.equals(sSent)) return;
        if (pending == null) {
            sSent = null;
            if (sPresence != null) invoke("clear");
            MAIN.removeCallbacks(IDLE_CLOSE);
            MAIN.postDelayed(IDLE_CLOSE, IDLE_CLOSE_MS); // a paused app keeps no socket open for long
            return;
        }
        MAIN.removeCallbacks(IDLE_CLOSE);
        if (sPresence == null && !open()) return;
        sSent = pending;
        invoke("publish", pending);
    }

    private static boolean open() {
        Class<?> entry = DiscordAddon.entry(sContext);
        String token = entry != null ? DiscordAddon.token(sContext) : null;
        if (token == null) return false;
        try {
            sPresence = entry.getConstructor(String.class, String.class).newInstance(token, Build.DEVICE);
            return true;
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "could not start", e);
            return false;
        }
    }

    private static void close() {
        MAIN.removeCallbacks(IDLE_CLOSE);
        if (sPresence != null) invoke("close");
        sPresence = null;
        sSent = null;
    }

    private static void invoke(String method, String... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            java.util.Arrays.fill(types, String.class);
            sPresence.getClass().getMethod(method, types).invoke(sPresence, (Object[]) args);
        } catch (Exception | LinkageError e) {
            Log.e(TAG, method + " failed", e);
        }
    }
}
