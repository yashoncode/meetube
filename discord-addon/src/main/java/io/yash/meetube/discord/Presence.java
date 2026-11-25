package io.yash.meetube.discord;

import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * MeeTube's Discord status add-on: one user-gateway session that carries the "Watching" or
 * "Listening to" activity. Loaded by the app from a downloaded dex and driven by reflection
 * (contract in docs/discord-presence/README.md); OkHttp and org.json are the app's own.
 *
 * Discord has no API that lets another app set a user's presence, so this logs in to the gateway
 * with the user's token and identifies as Discord Android, as BitChord and Kizzy do.
 *
 * Every field is touched only on {@link #exec}, so there are no locks. Behaviour that matters on
 * a phone in a pocket: a heartbeat that goes unacknowledged drops the socket (a dead TCP link is
 * not reported as closed), a dropped session is resumed or re-identified with a growing backoff,
 * and the wanted presence is replayed on every new session, since Discord forgets it with the
 * session. With nothing to show (paused) a dropped socket is not redialled.
 */
public final class Presence {
    private static final String TAG = "DiscordPresence";
    private static final String GATEWAY = "wss://gateway.discord.gg/?v=9&encoding=json";
    private static final String API = "https://discord.com/api/v9/";
    private static final String AGENT = "Discord-Android/314013;RNA";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
    private static volatile String sProperties;

    private final String token;
    private final String device;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, TAG));
    private final Map<String, String> art = new HashMap<>();

    private WebSocket socket;
    private boolean opened;
    private boolean ready;
    private boolean closed;
    private int seq = -1;
    private String sessionId;
    private String resumeUrl;
    private long heartbeatMs;
    private boolean awaitingAck;
    private long lastAck;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> retry;
    private long backoffMs = 1000;
    private boolean retryNow;
    /** The presence to show (null: nothing asked yet), and what this session already carries. */
    private JSONObject wanted;
    private String delivered;

    public Presence(String token, String device) {
        this.token = token;
        this.device = device;
    }

    /** Shows the activity; see the README for the fields. Returns at once. */
    public void publish(String activityJson) {
        post(() -> {
            try {
                wanted = presence(activity(new JSONObject(activityJson)));
                // A socket gone quiet still says it is open: drop it rather than write into it.
                if (ready && heartbeatMs > 0 && SystemClock.elapsedRealtime() - lastAck > heartbeatMs + 15_000) {
                    drop("stale before a publish");
                }
                if (socket == null) connect();
                else sendPresence();
            } catch (Exception e) {
                Log.w(TAG, "publish failed", e);
            }
        });
    }

    /** Takes the card down and keeps the session; never dials one just to say nothing. */
    public void clear() {
        post(() -> {
            try {
                wanted = presence(null);
                sendPresence();
            } catch (Exception e) {
                Log.w(TAG, "clear failed", e);
            }
        });
    }

    /** Ends the session. A normal close makes Discord drop the activity at once. */
    public void close() {
        post(() -> {
            closed = true;
            cancel(heartbeat);
            cancel(retry);
            if (socket != null) socket.close(1000, null);
            socket = null;
            exec.shutdown();
        });
    }

    // ----------------------------------------------------------------------------- the activity

    private JSONObject activity(JSONObject in) throws Exception {
        String appId = in.optString("appId");
        JSONObject a = new JSONObject();
        a.put("name", in.getString("name"));
        a.put("type", "listening".equals(in.optString("type")) ? 2 : 3);
        a.put("details", clip(in.optString("details")));
        a.put("state", clip(in.optString("state")));
        a.put("status_display_type", 1); // the member list reads "Watching <channel>" / "Listening to <artist>"
        String url = in.optString("url");
        if (!url.isEmpty()) a.put("details_url", url);
        JSONObject time = new JSONObject();
        if (in.optLong("start") > 0) time.put("start", in.getLong("start"));
        if (in.optLong("end") > 0) time.put("end", in.getLong("end"));
        a.put("timestamps", time);
        // Artwork and buttons both need the app's Discord application id.
        if (!appId.isEmpty()) {
            String image = externalAsset(appId, in.optString("image"));
            if (image != null) a.put("assets", new JSONObject().put("large_image", image));
            String button = in.optString("button");
            if (!button.isEmpty() && !url.isEmpty()) {
                a.put("buttons", new JSONArray().put(clip(button)));
                a.put("metadata", new JSONObject().put("button_urls", new JSONArray().put(url)));
                a.put("application_id", appId);
            }
        }
        return a;
    }

    private static String clip(String text) {
        if (text.length() < 2) return text + "  "; // Discord wants 2 to 128 characters
        return text.length() > 128 ? text.substring(0, 127) + "…" : text;
    }

    private static JSONObject presence(JSONObject activity) throws Exception {
        return new JSONObject()
                .put("since", 0)
                .put("status", "online")
                .put("afk", true) // keeps the phone app getting notifications
                .put("activities", activity == null ? new JSONArray() : new JSONArray().put(activity));
    }

    /** A thumbnail URL proxied by Discord ("mp:..."), cached; null when Discord says no. */
    private String externalAsset(String appId, String image) {
        if (!image.startsWith("https://")) return null;
        String cached = art.get(image);
        if (cached != null) return cached;
        try {
            Request request = rest(API + "applications/" + appId + "/external-assets")
                    .post(RequestBody.create(new JSONObject().put("urls", new JSONArray().put(image)).toString(), JSON))
                    .build();
            try (Response response = HTTP.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "external-assets HTTP " + response.code());
                    return null;
                }
                String path = new JSONArray(response.body().string()).getJSONObject(0).optString("external_asset_path");
                if (path.isEmpty()) return null;
                if (art.size() > 64) art.clear();
                art.put(image, "mp:" + path);
                return "mp:" + path;
            }
        } catch (Exception e) {
            Log.w(TAG, "external-assets failed", e);
            return null;
        }
    }

    // ----------------------------------------------------------------------------- the gateway

    private void connect() {
        if (closed || socket != null) return;
        cancel(retry);
        opened = false;
        Request request = new Request.Builder().url(resumeUrl != null ? resumeUrl : GATEWAY)
                .header("User-Agent", AGENT).header("Accept-Language", "en-US").build();
        socket = HTTP.newWebSocket(request, new Listener());
    }

    private final class Listener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket ws, Response response) {
            post(() -> {
                if (ws == socket) opened = true;
            });
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            post(() -> {
                if (ws != socket) return;
                try {
                    onPayload(new JSONObject(text));
                } catch (Exception e) { // one payload this build does not understand never takes the session down
                    Log.w(TAG, "bad payload", e);
                }
            });
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, null);
            post(() -> dropped(ws, code));
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            post(() -> dropped(ws, code));
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            post(() -> dropped(ws, -1));
        }
    }

    /** Socket callbacks can arrive after {@link #close} has shut the executor down; they are dropped. */
    private void post(Runnable task) {
        try {
            exec.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void onPayload(JSONObject p) throws Exception {
        if (!p.isNull("s")) seq = p.getInt("s");
        switch (p.getInt("op")) {
            case 0: // DISPATCH
                String event = p.optString("t");
                if ("READY".equals(event)) {
                    JSONObject d = p.getJSONObject("d");
                    sessionId = d.optString("session_id", null);
                    String resume = d.optString("resume_gateway_url", null);
                    resumeUrl = resume != null ? resume + "/?v=9&encoding=json" : null;
                    onReady();
                } else if ("RESUMED".equals(event)) {
                    onReady();
                }
                break;
            case 1: // HEARTBEAT asked for now
                beat();
                break;
            case 7: // RECONNECT
                retryNow = true;
                drop("gateway asked to reconnect");
                break;
            case 9: // INVALID_SESSION: identify afresh on this socket after a short wait
                forgetSession();
                WebSocket ws = socket;
                exec.schedule(() -> {
                    if (ws == socket) identify();
                }, 2, TimeUnit.SECONDS);
                break;
            case 10: // HELLO
                heartbeatMs = p.getJSONObject("d").getLong("heartbeat_interval");
                if (sessionId != null && seq >= 0) resume();
                else identify();
                startHeartbeat();
                break;
            case 11: // HEARTBEAT_ACK
                awaitingAck = false;
                lastAck = SystemClock.elapsedRealtime();
                break;
            default:
                break;
        }
    }

    private void onReady() {
        ready = true;
        backoffMs = 1000;
        lastAck = SystemClock.elapsedRealtime();
        sendPresence();
    }

    private void identify() {
        try {
            JSONObject properties = new JSONObject().put("os", "Android").put("browser", "Discord Android").put("device", device);
            send(2, new JSONObject()
                    .put("token", token)
                    .put("capabilities", 16381)
                    .put("properties", properties)
                    .put("presence", presence(null).put("afk", false))
                    .put("compress", false)
                    .put("large_threshold", 100));
        } catch (Exception e) {
            Log.w(TAG, "identify failed", e);
        }
    }

    private void resume() {
        try {
            send(6, new JSONObject().put("token", token).put("session_id", sessionId).put("seq", seq));
        } catch (Exception e) {
            Log.w(TAG, "resume failed", e);
        }
    }

    private void startHeartbeat() {
        cancel(heartbeat);
        awaitingAck = false;
        lastAck = SystemClock.elapsedRealtime();
        long first = (long) (heartbeatMs * Math.random());
        heartbeat = exec.scheduleWithFixedDelay(this::beat, first, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    private void beat() {
        if (awaitingAck) {
            drop("heartbeat went unacknowledged");
            return;
        }
        awaitingAck = true;
        try {
            send(1, seq < 0 ? JSONObject.NULL : seq);
        } catch (Exception e) {
            Log.w(TAG, "heartbeat failed", e);
        }
    }

    private void sendPresence() {
        if (!ready || wanted == null) return;
        String text = wanted.toString();
        if (text.equals(delivered)) return; // the publish and the reconnect replay can carry the same one
        try {
            if (send(3, wanted)) delivered = text;
        } catch (Exception e) {
            Log.w(TAG, "presence failed", e);
        }
    }

    private boolean send(int op, Object d) throws Exception {
        return socket != null && socket.send(new JSONObject().put("op", op).put("d", d).toString());
    }

    /** Ends this socket; {@link #dropped} decides what comes next. */
    private void drop(String why) {
        Log.i(TAG, "dropping socket: " + why);
        if (socket != null) socket.cancel();
    }

    private void dropped(WebSocket ws, int code) {
        if (ws != socket) return;
        Log.i(TAG, "socket closed code=" + code);
        socket = null;
        ready = false;
        delivered = null;
        awaitingAck = false;
        cancel(heartbeat);
        if (closed) return;
        if (code == 4004) { // the token no longer works (password change, sign out elsewhere)
            Log.w(TAG, "authentication failed; not retrying");
            closed = true;
            return;
        }
        // Never opened: the resume URL may be stale. 4007/4009: the session cannot be resumed.
        if (!opened || code == 4007 || code == 4009) forgetSession();
        if (wanted == null || wanted.optJSONArray("activities").length() == 0) return; // nothing to show
        long wait = retryNow ? 0 : backoffMs;
        retryNow = false;
        backoffMs = Math.min(backoffMs * 2, 60_000);
        retry = exec.schedule(this::connect, wait, TimeUnit.MILLISECONDS);
    }

    private void forgetSession() {
        sessionId = null;
        resumeUrl = null;
        seq = -1;
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    // ----------------------------------------------------------------------------- statics

    /**
     * Run in discord.com after sign in. Discord takes localStorage away from the page once it has
     * booted, so a new same-origin iframe reads it; alert() hands the value out without a
     * JavaScript bridge. Alerts '' while the client has not written the token yet.
     */
    public static String tokenScript() {
        return "(function(){var t='';try{var f=document.createElement('iframe');f.style.display='none';"
                + "document.body.appendChild(f);t=f.contentWindow.localStorage.getItem('token')||'';"
                + "f.remove();if(t.charAt(0)=='\"')t=JSON.parse(t);}catch(e){t='';}alert(t);})()";
    }

    /** The account's display name, or null. Blocking. */
    public static String userName(String token) {
        try (Response response = HTTP.newCall(restStatic(token, API + "users/@me").build()).execute()) {
            if (!response.isSuccessful()) return null;
            JSONObject me = new JSONObject(response.body().string());
            String name = me.optString("global_name");
            return name.isEmpty() || "null".equals(name) ? me.optString("username", null) : name;
        } catch (Exception e) {
            return null;
        }
    }

    /** Invalidates the token on Discord's side, as Discord's own sign out does. Blocking, best effort. */
    public static void logout(String token) {
        try (Response ignored = HTTP.newCall(restStatic(token, API + "auth/logout")
                .post(RequestBody.create("{\"provider\":null,\"voip_provider\":null}", JSON)).build()).execute()) {
            Log.i(TAG, "logout HTTP " + ignored.code());
        } catch (Exception e) {
            Log.w(TAG, "logout failed", e);
        }
    }

    private Request.Builder rest(String url) {
        return restStatic(token, url);
    }

    /** Discord's REST answers a request with no client fingerprint with 401s, so it gets the Android app's. */
    private static Request.Builder restStatic(String token, String url) {
        return new Request.Builder().url(url)
                .header("Authorization", token)
                .header("User-Agent", AGENT)
                .header("X-Super-Properties", properties());
    }

    /** Discord Android 314.13's X-Super-Properties, with ids made once per process like the real client. */
    private static String properties() {
        if (sProperties == null) {
            try {
                JSONObject p = new JSONObject()
                        .put("os", "Android")
                        .put("browser", "Discord Android")
                        .put("device", Build.DEVICE)
                        .put("system_locale", Locale.getDefault().toString())
                        .put("client_version", "314.13 - Stable")
                        .put("release_channel", "googleRelease")
                        .put("device_vendor_id", UUID.randomUUID().toString())
                        .put("client_uuid", UUID.randomUUID().toString())
                        .put("client_launch_id", UUID.randomUUID().toString())
                        .put("os_version", Build.VERSION.RELEASE)
                        .put("os_sdk_version", String.valueOf(Build.VERSION.SDK_INT))
                        .put("client_build_number", 314013)
                        .put("client_event_source", JSONObject.NULL)
                        .put("design_id", 0);
                sProperties = Base64.encodeToString(p.toString().getBytes("UTF-8"), Base64.NO_WRAP);
            } catch (Exception e) {
                sProperties = "";
            }
        }
        return sProperties;
    }
}
