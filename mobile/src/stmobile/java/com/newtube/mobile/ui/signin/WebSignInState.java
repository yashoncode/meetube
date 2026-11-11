package com.newtube.mobile.ui.signin;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.youtubeapi.web.CookieStore;
import com.liskovsoft.youtubeapi.web.WebSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * UI hints only. Session secrets belong exclusively to the WebView cookie jar, which holds every
 * Google account signed in there; each one is reached by its slot (X-Goog-AuthUser).
 */
public final class WebSignInState {
    private WebSignInState() {}

    public static final class Account {
        public final int slot;
        public final String name;
        /** Avatar URL, or null (not known yet). */
        public final String photo;

        Account(int slot, String name, String photo) {
            this.slot = slot;
            this.name = name;
            this.photo = photo;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("youtube_web_signin", Context.MODE_PRIVATE);
    }

    public static List<Account> accounts(Context context) {
        SharedPreferences prefs = prefs(context);
        List<Account> accounts = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("accounts", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                accounts.add(new Account(item.getInt("slot"), item.getString("name"),
                        item.has("photo") ? item.getString("photo") : null));
            }
        } catch (Exception ignored) {
            // A damaged list reads as signed out.
        }
        // Saved by the one-account version: that account was always slot 0.
        if (accounts.isEmpty() && prefs.getBoolean("signed_in", false)) {
            accounts.add(new Account(0, prefs.getString("account_name", ""), null));
        }
        return accounts;
    }

    public static boolean isSignedIn(Context context) {
        return !accounts(context).isEmpty();
    }

    public static int selectedSlot(Context context) {
        return prefs(context).getInt("selected_slot", 0);
    }

    public static String accountName(Context context) {
        int slot = selectedSlot(context);
        for (Account account : accounts(context)) {
            if (account.slot == slot) return account.name;
        }
        return "";
    }

    /** The selected account's avatar URL, or null. */
    public static String accountPhoto(Context context) {
        int slot = selectedSlot(context);
        for (Account account : accounts(context)) {
            if (account.slot == slot) return account.photo;
        }
        return null;
    }

    public static void select(Context context, int slot) {
        prefs(context).edit().putInt("selected_slot", slot).apply();
        WebSession.setAuthUser(slot);
    }

    /** Applies the saved choice to the web session (app start). */
    public static void restore(Context context) {
        WebSession.setAuthUser(selectedSlot(context));
    }

    /** Adds (or renames) the account in that slot and makes it the one that speaks. */
    static void signedIn(Context context, int slot, String name, String photo) {
        save(context, slot, name, photo);
        select(context, slot);
    }

    /** Stores (or updates) the account in that slot, its order kept. */
    static void save(Context context, int slot, String name, String photo) {
        JSONArray array = new JSONArray();
        boolean found = false;
        for (Account account : accounts(context)) {
            boolean match = account.slot == slot;
            found |= match;
            array.put(match ? json(slot, name, photo) : json(account.slot, account.name, account.photo));
        }
        if (!found) array.put(json(slot, name, photo));
        prefs(context).edit().remove("signed_in").remove("account_name")
                .putString("accounts", array.toString()).apply();
    }

    private static JSONObject json(int slot, String name, String photo) {
        try {
            JSONObject item = new JSONObject().put("slot", slot).put("name", name);
            return photo != null ? item.put("photo", photo) : item;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
        WebSession.setAuthUser(0);
    }

    /** Blocking, worker only. Expire domain and host cookies without touching unrelated sites. */
    public static void clearCookies() {
        CookieStore store = WebSession.getCookieStore();
        if (store == null) return;
        synchronized (store) {
            for (String domain : new String[] {"google.com", "youtube.com"}) {
                String[] hosts = domain.equals("google.com")
                        ? new String[] {domain, "www.google.com", "accounts.google.com"}
                        : new String[] {domain, "www.youtube.com", "m.youtube.com", "music.youtube.com"};
                for (String host : hosts) {
                    String url = "https://" + host + "/";
                    String header = store.get(url);
                    if (header == null) continue;
                    for (String pair : header.split(";")) {
                        int equals = pair.indexOf('=');
                        if (equals < 1) continue;
                        String name = pair.substring(0, equals).trim();
                        String expired = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure";
                        store.put(url, expired);
                        if (!name.startsWith("__Host-")) {
                            store.put(url, expired + "; Domain=" + host);
                            store.put(url, expired + "; Domain=." + domain);
                        }
                    }
                }
            }
        }
    }
}
