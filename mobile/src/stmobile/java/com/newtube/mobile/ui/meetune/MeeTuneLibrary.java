package com.newtube.mobile.ui.meetune;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileSnackbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MeeTune's library on this phone, no account needed: playlists made here (or imported from Spotify)
 * and albums and playlists saved from YouTube Music, any of them pinned to the front. Kept as one JSON
 * value in MeeTune's prefs, and exported or imported whole as a file.
 *
 * <p>ponytail: one JSON string, rewritten on every change; fine to a few thousand songs. A database
 * when libraries grow past that.
 */
public final class MeeTuneLibrary {
    /** A playlist made on the phone: {@code meetune:local:<id>} as its browse id. */
    static final String PLAYLIST = "meetune:local:";
    /** The Library's New playlist card. */
    static final String NEW = "meetune:new-playlist";
    private static final String KEY = "local_library";
    private static final ExecutorService IMPORTS = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** MeeTune's screen, told when the library changed (its Library and an open phone playlist reload). */
    @Nullable
    static Runnable sChanged;

    private MeeTuneLibrary() {
    }

    // ------------------------------------------------------------------------------ store

    private static synchronized JSONObject load(Context c) {
        try {
            JSONObject o = new JSONObject(MeeTune.prefs(c).getString(KEY, "{}"));
            if (o.optJSONArray("playlists") == null) o.put("playlists", new JSONArray());
            if (o.optJSONArray("saved") == null) o.put("saved", new JSONArray());
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static synchronized void store(Context c, JSONObject library) {
        MeeTune.prefs(c).edit().putString(KEY, library.toString()).apply();
        Runnable changed = sChanged;
        if (changed != null) MAIN.post(changed);
    }

    static boolean isPlaylist(@Nullable String browseId) {
        return browseId != null && browseId.startsWith(PLAYLIST);
    }

    static boolean exists(Context c, String browseId) {
        return playlist(load(c), browseId) != null;
    }

    @Nullable
    private static JSONObject playlist(JSONObject library, String browseId) {
        JSONArray all = library.optJSONArray("playlists");
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p != null && browseId.equals(PLAYLIST + p.optString("id"))) return p;
        }
        return null;
    }

    @Nullable
    private static JSONObject saved(JSONObject library, String browseId) {
        JSONArray all = library.optJSONArray("saved");
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject s = all.optJSONObject(i);
            if (s != null && browseId.equals(s.optString("b"))) return s;
        }
        return null;
    }

    /** A new, empty playlist; its browse id. */
    static synchronized String create(Context c, String name) {
        JSONObject library = load(c);
        String id = Long.toString(System.currentTimeMillis(), 36);
        try {
            library.getJSONArray("playlists").put(new JSONObject().put("id", id).put("name", name).put("songs", new JSONArray()));
        } catch (JSONException ignored) {
        }
        store(c, library);
        return PLAYLIST + id;
    }

    /** Adds the songs not in it yet, in order; how many were new. */
    static synchronized int add(Context c, String browseId, List<MusicApi.Item> songs) {
        JSONObject library = load(c);
        JSONObject p = playlist(library, browseId);
        if (p == null) return 0;
        JSONArray list = p.optJSONArray("songs");
        if (list == null) list = new JSONArray();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (int i = 0; i < list.length(); i++) have.add(list.optJSONObject(i).optString("v"));
        int added = 0;
        try {
            for (MusicApi.Item song : songs) {
                if (song.videoId == null || !have.add(song.videoId)) continue;
                list.put(new JSONObject().put("v", song.videoId).put("t", song.title)
                        .put("a", song.artist != null ? song.artist : MeeTune.artist(song.subtitle)).put("art", song.art));
                added++;
            }
            p.put("songs", list);
        } catch (JSONException ignored) {
        }
        store(c, library);
        return added;
    }

    static synchronized void remove(Context c, String browseId, String videoId) {
        JSONObject library = load(c);
        JSONObject p = playlist(library, browseId);
        JSONArray list = p != null ? p.optJSONArray("songs") : null;
        for (int i = 0; list != null && i < list.length(); i++) {
            if (videoId.equals(list.optJSONObject(i).optString("v"))) {
                list.remove(i);
                break;
            }
        }
        store(c, library);
    }

    static synchronized void rename(Context c, String browseId, String name) {
        JSONObject library = load(c);
        JSONObject p = playlist(library, browseId);
        try {
            if (p != null) p.put("name", name);
        } catch (JSONException ignored) {
        }
        store(c, library);
    }

    /** A phone playlist deleted, or a saved page taken off the phone. */
    static synchronized void delete(Context c, String browseId) {
        JSONObject library = load(c);
        for (String key : new String[] {"playlists", "saved"}) {
            JSONArray all = library.optJSONArray(key);
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                String id = "playlists".equals(key) ? PLAYLIST + o.optString("id") : o.optString("b");
                if (browseId.equals(id)) {
                    all.remove(i);
                    break;
                }
            }
        }
        store(c, library);
    }

    static boolean isSaved(Context c, @Nullable String browseId) {
        return browseId != null && saved(load(c), browseId) != null;
    }

    /** An album or playlist page saved on the phone (or taken off): its card in the Library opens it again. */
    static synchronized void setSaved(Context c, String browseId, MusicApi.Item head, boolean on) {
        JSONObject library = load(c);
        JSONObject s = saved(library, browseId);
        try {
            if (on && s == null) {
                library.getJSONArray("saved").put(new JSONObject().put("b", browseId).put("t", head.title)
                        .put("s", head.artist != null ? head.artist : head.subtitle).put("art", head.art));
            }
        } catch (JSONException ignored) {
        }
        store(c, library);
        if (!on && s != null) delete(c, browseId);
    }

    static boolean isPinned(Context c, String browseId) {
        JSONObject library = load(c);
        JSONObject o = isPlaylist(browseId) ? playlist(library, browseId) : saved(library, browseId);
        return o != null && o.optBoolean("pinned");
    }

    static synchronized void setPinned(Context c, String browseId, boolean pinned) {
        JSONObject library = load(c);
        JSONObject o = isPlaylist(browseId) ? playlist(library, browseId) : saved(library, browseId);
        try {
            if (o != null) o.put("pinned", pinned);
        } catch (JSONException ignored) {
        }
        store(c, library);
    }

    /** The Library's "On this phone" row: New playlist, then pinned cards, then the rest (newest first). */
    static MusicApi.Shelf shelf(Context c) {
        MusicApi.Shelf shelf = new MusicApi.Shelf();
        shelf.title = c.getString(R.string.meetune_on_phone);
        MusicApi.Item make = new MusicApi.Item();
        make.kind = MusicApi.CARD;
        make.title = c.getString(R.string.meetune_new_playlist);
        make.browseId = NEW;
        make.icon = R.drawable.ic_mobile_add;
        shelf.items.add(make);
        JSONObject library = load(c);
        List<MusicApi.Item> pinned = new ArrayList<>(), rest = new ArrayList<>();
        JSONArray playlists = library.optJSONArray("playlists");
        for (int i = playlists != null ? playlists.length() - 1 : -1; i >= 0; i--) {
            JSONObject p = playlists.optJSONObject(i);
            JSONArray songs = p.optJSONArray("songs");
            int n = songs != null ? songs.length() : 0;
            MusicApi.Item card = new MusicApi.Item();
            card.kind = MusicApi.CARD;
            card.title = p.optString("name");
            card.browseId = PLAYLIST + p.optString("id");
            card.subtitle = c.getResources().getQuantityString(R.plurals.meetune_playlist_count, n, n);
            card.art = n > 0 ? songs.optJSONObject(0).optString("art", null) : null;
            if (card.art == null) card.icon = R.drawable.ic_player_queue;
            (p.optBoolean("pinned") ? pinned : rest).add(card);
        }
        JSONArray saved = library.optJSONArray("saved");
        for (int i = saved != null ? saved.length() - 1 : -1; i >= 0; i--) {
            JSONObject s = saved.optJSONObject(i);
            MusicApi.Item card = new MusicApi.Item();
            card.kind = MusicApi.CARD;
            card.title = s.optString("t");
            card.subtitle = s.optString("s", null);
            card.art = s.optString("art", null);
            card.browseId = s.optString("b");
            (s.optBoolean("pinned") ? pinned : rest).add(card);
        }
        shelf.items.addAll(pinned);
        shelf.items.addAll(rest);
        return shelf;
    }

    /** A phone playlist's songs, as a page. */
    static List<MusicApi.Shelf> songs(Context c, String browseId) {
        MusicApi.Shelf shelf = new MusicApi.Shelf();
        JSONObject p = playlist(load(c), browseId);
        JSONArray list = p != null ? p.optJSONArray("songs") : null;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            MusicApi.Item item = new MusicApi.Item();
            item.kind = MusicApi.SONG;
            item.videoId = o.optString("v");
            item.title = o.optString("t", null);
            item.artist = o.optString("a", null);
            item.subtitle = item.artist;
            item.art = o.optString("art", null);
            shelf.items.add(item);
        }
        List<MusicApi.Shelf> shelves = new ArrayList<>();
        if (!shelf.items.isEmpty()) shelves.add(shelf);
        return shelves;
    }

    // ------------------------------------------------------------------------------ export, import

    static void export(Context c, Uri file) throws Exception {
        JSONObject library = load(c).put("meetune_library", 1);
        try (OutputStream out = c.getContentResolver().openOutputStream(file, "wt")) {
            if (out == null) throw new java.io.IOException("no file");
            out.write(library.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Merges a file's library in: its playlists and saved pages not here yet; returns {playlists, saved}. */
    static synchronized int[] importFile(Context c, Uri file) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (InputStream in = c.getContentResolver().openInputStream(file)) {
            if (in == null) throw new java.io.IOException("no file");
            byte[] buffer = new byte[16 * 1024];
            for (int n; (n = in.read(buffer)) > 0 && bytes.size() < 20 * 1024 * 1024; ) bytes.write(buffer, 0, n);
        }
        JSONObject theirs = new JSONObject(bytes.toString("UTF-8"));
        if (theirs.optInt("meetune_library") != 1) throw new JSONException("not a MeeTune library");
        JSONObject library = load(c);
        int[] counts = new int[2];
        JSONArray playlists = theirs.optJSONArray("playlists");
        for (int i = 0; playlists != null && i < playlists.length(); i++) {
            JSONObject p = playlists.optJSONObject(i);
            if (p == null || p.optString("id").isEmpty() || playlist(library, PLAYLIST + p.optString("id")) != null) continue;
            library.getJSONArray("playlists").put(p);
            counts[0]++;
        }
        JSONArray saved = theirs.optJSONArray("saved");
        for (int i = 0; saved != null && i < saved.length(); i++) {
            JSONObject s = saved.optJSONObject(i);
            if (s == null || s.optString("b").isEmpty() || saved(library, s.optString("b")) != null) continue;
            library.getJSONArray("saved").put(s);
            counts[1]++;
        }
        store(c, library);
        return counts;
    }

    // ------------------------------------------------------------------------------ sheets

    /**
     * A song's Add to playlist: a phone playlist (New playlist first), or, signed in, a YouTube playlist
     * as before.
     */
    static void addSheet(Activity a, MusicApi.Item song) {
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = MeeTuneSongMenu.glassPopup(a, content, 264, 28);
        List<MusicApi.Item> one = new ArrayList<>();
        one.add(song);
        MeeTuneSongMenu.row(content, R.drawable.ic_mobile_add, a.getString(R.string.meetune_new_playlist), null, () -> {
            popup.dismiss();
            askName(a, null, name -> {
                String id = create(a, name);
                add(a, id, one);
                MobileSnackbar.show(a, a.getString(R.string.meetune_added_to, name));
            });
        });
        for (MusicApi.Item card : shelf(a).items) {
            if (!isPlaylist(card.browseId)) continue;
            MeeTuneSongMenu.row(content, R.drawable.ic_player_queue, card.title, card.subtitle, () -> {
                popup.dismiss();
                MobileSnackbar.show(a, a.getString(add(a, card.browseId, one) > 0
                        ? R.string.meetune_added_to : R.string.meetune_already_in, card.title));
            });
        }
        if (MusicApi.signedIn(a)) {
            content.addView(MeeTuneSongMenu.divider(a));
            MeeTuneSongMenu.row(content, R.drawable.ic_player_playlist_add, a.getString(R.string.meetune_youtube_playlist), null, () -> {
                popup.dismiss();
                com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil.showAddToPlaylistDialog(a, MeeTune.video(song), null);
            });
        }
        MeeTuneSongMenu.place(a, popup, null, true);
    }

    /** A phone card's long press: pin, rename (a playlist made here), delete or take off the phone. */
    static void cardMenu(Activity a, View anchor, MusicApi.Item card) {
        String id = card.browseId;
        if (id == null || NEW.equals(id)) return;
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = MeeTuneSongMenu.glassPopup(a, content, 250, 28);
        boolean pinned = isPinned(a, id);
        MeeTuneSongMenu.row(content, R.drawable.ic_menu_pin, a.getString(pinned ? R.string.meetune_unpin : R.string.meetune_pin), null, () -> {
            popup.dismiss();
            setPinned(a, id, !pinned);
        });
        if (isPlaylist(id)) {
            MeeTuneSongMenu.row(content, R.drawable.ic_menu_edit, a.getString(R.string.meetune_rename), null, () -> {
                popup.dismiss();
                askName(a, card.title, name -> rename(a, id, name));
            });
        }
        MeeTuneSongMenu.row(content, R.drawable.ic_meetune_delete, a.getString(isPlaylist(id)
                ? R.string.meetune_delete_playlist : R.string.meetune_unsave_phone), null, () -> {
            popup.dismiss();
            delete(a, id);
        });
        MeeTuneSongMenu.place(a, popup, anchor, false);
    }

    /** The playlist name box: {@code done} with a name that is not blank. */
    static void askName(Activity a, @Nullable String current, Consumer<String> done) {
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setHint(R.string.meetune_playlist_name);
        if (current != null) field.setText(current);
        int pad = MeeTuneAdapter.dp(a, 20);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(pad, MeeTuneAdapter.dp(a, 8), pad, 0);
        box.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = MeeTuneSongMenu.glassPrompt(a, content,
                a.getString(current == null ? R.string.meetune_new_playlist : R.string.meetune_rename), null, android.R.string.cancel);
        content.addView(box, 2); // under the title and the (hidden) message, above the buttons
        android.widget.TextView create = content.findViewById(android.R.id.button1);
        create.setText(current == null ? R.string.meetune_create : R.string.meetune_rename);
        create.setVisibility(View.VISIBLE);
        create.setOnClickListener(v -> {
            String name = field.getText().toString().trim();
            if (!name.isEmpty()) MeeTuneSongMenu.dismissPrompt(popup, () -> done.accept(name));
        });
        popup.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        MeeTuneSongMenu.place(a, popup, null, true);
        field.requestFocus();
    }

    // ------------------------------------------------------------------------------ Spotify

    /**
     * Import from Spotify: signed in to Spotify (its sign-in first when not), pick playlists, and each
     * becomes a phone playlist of the songs found on YouTube Music. Imported again later, the new songs
     * are added to the same playlist.
     */
    static void importSpotify(Activity a) {
        if (!MeeTuneSpotify.signedIn()) {
            MeeTuneSpotify.signIn(a, () -> {
                if (MeeTuneSpotify.signedIn()) importSpotify(a);
            });
            return;
        }
        Toast.makeText(a, R.string.meetune_spotify_loading, Toast.LENGTH_SHORT).show();
        MeeTune.run(a, () -> MeeTuneSpotify.playlists(a), (lists, error) -> {
            if (lists == null || lists.isEmpty()) {
                Toast.makeText(a, lists == null ? R.string.meetune_spotify_import_failed : R.string.meetune_spotify_none,
                        Toast.LENGTH_LONG).show();
                return;
            }
            String[] names = new String[lists.size()];
            boolean[] picked = new boolean[lists.size()];
            for (int i = 0; i < names.length; i++) {
                MeeTuneSpotify.Playlist p = lists.get(i);
                names[i] = p.name + " (" + a.getResources().getQuantityString(R.plurals.meetune_playlist_count, p.count, p.count) + ")";
            }
            new MaterialAlertDialogBuilder(a, R.style.MobileAlertDialog_GlassCheck)
                    .setTitle(R.string.meetune_spotify_import)
                    .setMultiChoiceItems(names, picked, (d, which, on) -> picked[which] = on)
                    .setPositiveButton(R.string.meetune_import_action, (d, w) -> {
                        List<MeeTuneSpotify.Playlist> chosen = new ArrayList<>();
                        for (int i = 0; i < picked.length; i++) if (picked[i]) chosen.add(lists.get(i));
                        if (chosen.isEmpty()) return;
                        Toast.makeText(a, a.getResources().getQuantityString(R.plurals.meetune_spotify_importing,
                                chosen.size(), chosen.size()), Toast.LENGTH_LONG).show();
                        Context app = a.getApplicationContext();
                        IMPORTS.execute(() -> {
                            for (MeeTuneSpotify.Playlist p : chosen) importOne(app, p);
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /** One Spotify playlist into its phone playlist; a toast with how many songs were found. */
    private static void importOne(Context app, MeeTuneSpotify.Playlist from) {
        String message;
        try {
            List<MeeTuneSpotify.Track> tracks = MeeTuneSpotify.tracks(app, from.id);
            List<MusicApi.Item> found = new ArrayList<>();
            for (MeeTuneSpotify.Track t : tracks) {
                try {
                    MusicApi.Item song = MusicApi.findSong(app, t.title, t.artists, t.durationMs);
                    if (song != null) found.add(song);
                } catch (Exception ignored) {
                    // that song's search failed: it is left out, the rest go on
                }
            }
            String browseId = spotifyPlaylist(app, from);
            add(app, browseId, found);
            message = app.getString(R.string.meetune_spotify_imported, from.name, found.size(), tracks.size());
        } catch (Exception e) {
            message = app.getString(R.string.meetune_spotify_import_failed);
        }
        String text = message;
        MAIN.post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }

    /** The phone playlist a Spotify playlist was imported into before, else a new one by its name. */
    private static synchronized String spotifyPlaylist(Context c, MeeTuneSpotify.Playlist from) throws JSONException {
        JSONObject library = load(c);
        JSONArray all = library.getJSONArray("playlists");
        for (int i = 0; i < all.length(); i++) {
            if (from.id.equals(all.getJSONObject(i).optString("spotify"))) return PLAYLIST + all.getJSONObject(i).optString("id");
        }
        String browseId = create(c, from.name);
        library = load(c);
        JSONObject p = playlist(library, browseId);
        if (p != null) p.put("spotify", from.id);
        store(c, library);
        return browseId;
    }
}
