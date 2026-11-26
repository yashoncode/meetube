package com.newtube.mobile.discord;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import dalvik.system.DexClassLoader;

/**
 * MEETUBE(discord): the Discord status add-on's download, its Discord sign in and its switches.
 * The add-on is one dex from this repo's releases, pinned by size and SHA-256, checked on download
 * and again before it is loaded (docs/discord-presence/README.md). The token is encrypted with a
 * Keystore key; shared_prefs/discord.xml stays out of backups.
 */
public final class DiscordAddon {
    private static final String TAG = "DiscordAddon";
    private static final String URL = "https://github.com/yashoncode/meetube/releases/download/discord-status-addon-v1/discord-presence-1.dex";
    public static final long SIZE = 18988;
    private static final String SHA256 = "2b010dd28467188bbee8bc47468b46fca37c8f046478ca597b426a05814b969f";
    private static final String ENTRY = "io.yash.meetube.discord.Presence";
    private static final String PREFS = "discord";
    private static final String KEY_ALIAS = "meetube_discord";
    private static final String TOKEN = "token";
    private static final String NAME = "name";
    private static final String VIDEOS = "videos";
    private static final String MUSIC = "music";

    public static final Executor WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, TAG));
    private static final AtomicBoolean sDownloading = new AtomicBoolean();
    private static Class<?> sEntry;

    private DiscordAddon() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "discord/discord-presence-1.dex");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean installed(Context context) {
        return file(context).length() == SIZE;
    }

    public static boolean downloading() {
        return sDownloading.get();
    }

    /** {@code done} on the main thread with whether it worked. */
    public static void download(Context context, Consumer<Boolean> done) {
        if (!sDownloading.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            boolean ok = false;
            File target = file(app);
            File part = new File(app.getCacheDir(), "discord-presence-1.dex.part");
            try {
                fetch(part);
                File dir = target.getParentFile();
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("no folder");
                target.delete();
                if (!part.renameTo(target) || !target.setReadOnly()) throw new IOException("could not publish");
                ok = true;
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                part.delete();
            } finally {
                sDownloading.set(false);
                boolean result = ok;
                new Handler(Looper.getMainLooper()).post(() -> done.accept(result));
            }
        });
    }

    private static void fetch(File part) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        HttpURLConnection conn = (HttpURLConnection) new URL(URL).openConnection();
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(20_000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + conn.getResponseCode());
            long received = 0;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    received += n;
                    if (received > SIZE) throw new IOException("add-on too large");
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (received != SIZE || !new BigInteger(SHA256, 16).equals(new BigInteger(1, sha.digest()))) {
                throw new IOException("add-on size or SHA-256 mismatch");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Signs out (revoking the token), then deletes the add-on. Blocking: from {@link #WORKER}. */
    public static void remove(Context context) {
        signOut(context);
        File f = file(context);
        f.setWritable(true);
        f.delete();
    }

    /** The add-on's entry class, verified again before the first load; null when missing or altered. */
    @Nullable
    static synchronized Class<?> entry(Context context) {
        if (sEntry != null) return sEntry;
        File f = file(context);
        try {
            if (!installed(context) || !verified(f)) return null;
            DexClassLoader loader = new DexClassLoader(f.getAbsolutePath(), context.getCodeCacheDir().getAbsolutePath(),
                    null, DiscordAddon.class.getClassLoader());
            sEntry = loader.loadClass(ENTRY);
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "could not load the add-on", e);
        }
        return sEntry;
    }

    private static boolean verified(File f) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) sha.update(buffer, 0, n);
        }
        return new BigInteger(SHA256, 16).equals(new BigInteger(1, sha.digest()));
    }

    /** A static String method of the add-on (tokenScript, userName, logout); null on any failure. */
    @Nullable
    public static String call(Context context, String method, Object... args) {
        Class<?> entry = entry(context);
        if (entry == null) return null;
        try {
            Class<?>[] types = new Class<?>[args.length];
            java.util.Arrays.fill(types, String.class);
            Object result = entry.getMethod(method, types).invoke(null, args);
            return result instanceof String ? (String) result : null;
        } catch (Exception | LinkageError e) {
            Log.e(TAG, method + " failed", e);
            return null;
        }
    }

    // ----------------------------------------------------------------------------- account

    public static boolean signedIn(Context context) {
        return prefs(context).contains(TOKEN);
    }

    @Nullable
    public static String userName(Context context) {
        return prefs(context).getString(NAME, null);
    }

    /** Stores the token and looks up who it belongs to. Blocking: from {@link #WORKER}. */
    public static void signIn(Context context, String token) {
        try {
            prefs(context).edit().putString(TOKEN, encrypt(token)).apply();
            prefs(context).edit().putString(NAME, call(context, "userName", token)).apply();
        } catch (Exception e) {
            Log.e(TAG, "could not store the token", e);
        }
        DiscordPresence.restart();
    }

    /** Revokes the token on Discord (best effort), then forgets it. Blocking: from {@link #WORKER}. */
    public static void signOut(Context context) {
        String token = token(context);
        prefs(context).edit().remove(TOKEN).remove(NAME).apply();
        DiscordPresence.restart();
        if (token != null) call(context, "logout", token);
    }

    @Nullable
    static String token(Context context) {
        String stored = prefs(context).getString(TOKEN, null);
        if (stored == null) return null;
        try {
            byte[] data = Base64.decode(stored, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { // a restore to another phone, or a wiped Keystore: sign in again
            Log.e(TAG, "could not read the token", e);
            prefs(context).edit().remove(TOKEN).remove(NAME).apply();
            return null;
        }
    }

    private static String encrypt(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] data = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, data, 0, iv.length);
        System.arraycopy(sealed, 0, data, iv.length, sealed.length);
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    // ----------------------------------------------------------------------------- switches

    public static boolean videos(Context context) {
        return prefs(context).getBoolean(VIDEOS, true);
    }

    public static void setVideos(Context context, boolean on) {
        prefs(context).edit().putBoolean(VIDEOS, on).apply();
        DiscordPresence.refresh();
    }

    public static boolean music(Context context) {
        return prefs(context).getBoolean(MUSIC, true);
    }

    public static void setMusic(Context context, boolean on) {
        prefs(context).edit().putBoolean(MUSIC, on).apply();
        DiscordPresence.refresh();
    }
}
