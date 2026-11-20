package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dalvik.system.DexClassLoader;

/**
 * MEETUBE(issue #281): Only people who turn on Karaoke download its runtime and model, so neither adds to the APK size.
 * The runtime and the MeeAI Model are each verified and published in private storage, and deleting the model removes both.
 */
public final class KaraokePack {
    public enum State { UNSUPPORTED, NOT_DOWNLOADED, DOWNLOADING, READY }

    private static final String TAG = "KaraokePack";
    private static final String VERSION = "1";
    private static final String RELEASE = "https://github.com/yashoncode/meetube/releases/download/karaoke-1/karaoke-";
    private static final String DEX = "classes.dex";
    private static final String RUNTIME = "runtime";
    private static final String MODEL = "model";
    private static final String MDX = "karaoke.onnx";
    private static final String[] LIBRARIES = {DEX, "libonnxruntime.so", "libonnxruntime4j_jni.so", "RUNTIME-LICENSE.txt"};
    private static final Asset ARM = new Asset(RUNTIME, "runtime-arm64-v8a", 10635689,
            "9ad6d1d9dff1ca09cffcbfa9940e526dd3a404eab9e9cfc58c52584ba4a27d56", LIBRARIES);
    private static final Asset X86 = new Asset(RUNTIME, "runtime-x86_64", 12494107,
            "8cabd7d77761f07b05e4459788eb2305e7e98f933dc7aefe62b52ef1d34ebe3f", LIBRARIES);
    private static final Asset BEST = new Asset(MODEL, "model-best", 27603570,
            "e7ba5e255d166c64ecffa22315ba80503619fe1b9e10280e874c9e5fce8a6cee", MDX, "BEST-LICENSE.txt", "UVR_MDXNET_3_9662.LICENSE");

    private static final class Asset {
        final String directory;
        final String name;
        final long size;
        final String hash;
        final String[] files;

        Asset(String directory, String name, long size, String hash, String... files) {
            this.directory = directory;
            this.name = name;
            this.size = size;
            this.hash = hash;
            this.files = files;
        }
    }

    private static final AtomicBoolean migrationQueued = new AtomicBoolean();
    private static final AtomicBoolean downloading = new AtomicBoolean();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile int percent;
    // Serialize disk changes.
    static final Executor WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, TAG));
    private static DexClassLoader loader;

    private KaraokePack() {}

    static void prepareStorage(Context context) {
        if (migrationQueued.compareAndSet(false, true)) {
            Context app = context.getApplicationContext();
            WORKER.execute(() -> {
                File root = new File(app.getFilesDir(), "karaoke");
                // Earlier builds kept a single pack in 1, then offered two models.
                for (String legacy : new String[] {"1", "1.tmp", "fast", "best"}) cleanUp(new File(root, legacy));
                // Reclaim incomplete transfers after process death. Published packs have an ok marker.
                cleanUp(new File(app.getCacheDir(), "karaoke-" + VERSION + ".zip.part"));
                for (String pack : new String[] {RUNTIME, MODEL}) {
                    File dir = directory(app, pack);
                    cleanUp(new File(dir.getParentFile(), VERSION + ".tmp"));
                    if (!installed(app, pack)) cleanUp(dir);
                }
                if (!installed(app, MODEL)) cleanUp(directory(app, RUNTIME));
            });
        }
    }

    @Nullable
    public static String abi(Context context) {
        prepareStorage(context);
        String dir = context.getApplicationInfo().nativeLibraryDir;
        return dir == null ? null : mapAbi(new File(dir).getName());
    }

    @Nullable
    static String mapAbi(String name) {
        if ("arm64".equals(name)) return "arm64-v8a";
        if ("x86_64".equals(name)) return "x86_64";
        return null;
    }

    private static File directory(Context context, String pack) {
        return new File(new File(context.getFilesDir(), "karaoke/" + pack), VERSION);
    }

    private static boolean installed(Context context, String pack) {
        return new File(directory(context, pack), "ok").isFile();
    }

    static boolean runtimeReady(Context context) {
        return installed(context, RUNTIME);
    }

    static State state(Context context) {
        if (abi(context) == null) return State.UNSUPPORTED;
        if (downloading.get()) return State.DOWNLOADING;
        return runtimeReady(context) && installed(context, MODEL) ? State.READY : State.NOT_DOWNLOADED;
    }

    public static int percent() {
        return percent;
    }

    static void addListener(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    static void notifyListeners() {
        if (!listeners.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                for (Runnable listener : listeners) listener.run();
            });
        }
    }

    private static void progress(long received, long total) {
        int next = (int) (100 * received / total);
        if (percent != next) {
            percent = next;
            notifyListeners();
        }
    }

    static boolean download(Context context, Consumer<Boolean> done) {
        if (!downloading.compareAndSet(false, true)) return false;
        percent = 0;
        notifyListeners();
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            boolean success = false;
            try {
                String abi = abi(app);
                if (abi != null) {
                    Asset runtime = "arm64-v8a".equals(abi) ? ARM : X86;
                    long engineBytes = runtimeReady(app) ? 0 : runtime.size;
                    long total = engineBytes + BEST.size;
                    if (engineBytes > 0) install(app, runtime, bytes -> progress(bytes, total));
                    if (!installed(app, MODEL)) install(app, BEST, bytes -> progress(engineBytes + bytes, total));
                    success = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not install Karaoke", e);
            } finally {
                if (!success && !installed(app, MODEL)) cleanUp(directory(app, RUNTIME));
                boolean installed = success;
                downloading.set(false);
                notifyListeners();
                main.post(() -> done.accept(installed));
            }
        });
        return true;
    }

    private static void install(Context context, Asset asset, LongConsumer progress) throws Exception {
        File dir = directory(context, asset.directory);
        File root = dir.getParentFile();
        File staging = new File(root, VERSION + ".tmp");
        // Fixed private temp paths are also reclaimed on the next attempt after a process death.
        File archive = new File(context.getCacheDir(), "karaoke-" + VERSION + ".zip.part");
        boolean installed = false;
        try {
            remove(staging);
            remove(dir);
            if (!staging.mkdirs()) throw new IOException("Could not create " + staging);
            fetch(asset, archive, progress);
            unpack(archive, staging, asset.files);
            if (RUNTIME.equals(asset.directory) && !new File(staging, DEX).setReadOnly()) {
                throw new IOException("Could not protect " + DEX);
            }
            File[] versions = root.listFiles();
            if (versions == null) throw new IOException("Could not list " + root);
            for (File other : versions) {
                if (other.isDirectory() && !staging.equals(other)) remove(other);
            }
            remove(archive);
            if (!new File(staging, "ok").createNewFile()) throw new IOException("Could not mark " + dir + " ready");
            if (!staging.renameTo(dir)) throw new IOException("Could not publish " + dir);
            installed = true;
        } finally {
            if (!installed) {
                cleanUp(archive);
                cleanUp(staging);
                cleanUp(dir);
            }
        }
    }

    private static void fetch(Asset asset, File archive, LongConsumer progress) throws Exception {
        long size = asset.size;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASE + asset.name + ".zip").openConnection();
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(20_000);
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            long received = 0;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(archive)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    received += n;
                    if (received > size) throw new IOException("Karaoke archive is too large");
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    progress.accept(received);
                }
            }
            BigInteger expected = new BigInteger(asset.hash, 16);
            if (received != size || !expected.equals(new BigInteger(1, sha.digest()))) {
                throw new IOException("Karaoke archive size or SHA-256 mismatch");
            }
        } finally {
            conn.disconnect();
        }
    }

    static boolean safeEntryName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.contains("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static void unpack(File archive, File staging, String[] files) throws IOException {
        Set<String> missing = new HashSet<>(Arrays.asList(files));
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !safeEntryName(name) || !missing.remove(name)) {
                    throw new IOException("Unexpected entry: " + name);
                }
                try (FileOutputStream out = new FileOutputStream(new File(staging, name))) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
        }
        if (!missing.isEmpty()) throw new IOException("Missing Karaoke files: " + missing);
    }

    private static void remove(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Could not list " + file);
            for (File child : children) remove(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Could not delete " + file);
    }

    private static boolean cleanUp(File file) {
        try {
            remove(file);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Karaoke cleanup failed", e);
            return false;
        }
    }

    /** Session changes share a lock with deletion, independent of archive transfers. */
    static synchronized boolean enable(Context context) {
        if (!load(context)) return false;
        MeeTuneKaraoke.setOn(true);
        return true;
    }

    private static boolean load(Context context) {
        if (state(context) != State.READY) return false;
        if (MeeTuneKaraoke.isLoaded()) return true;
        File runtime = directory(context, RUNTIME);
        try {
            // Android binds native libraries to their loader. Reuse it after a delete and re-download.
            if (loader == null) {
                loader = new DexClassLoader(new File(runtime, DEX).getAbsolutePath(), context.getCodeCacheDir().getAbsolutePath(),
                        runtime.getAbsolutePath(), KaraokePack.class.getClassLoader());
            }
            OrtSession session = new OrtSession(loader, new File(directory(context, MODEL), MDX));
            MeeTuneKaraoke.modelLoaded(session::run, () -> WORKER.execute(() -> close(session)));
            return true;
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "Could not load Karaoke", e);
            return false;
        }
    }

    /** Turns karaoke off. A song still separating keeps the session until its worker stops. */
    static synchronized boolean delete(Context context) {
        File model = directory(context, MODEL);
        File runtime = directory(context, RUNTIME);
        if (!cleanUp(new File(model, "ok"))) return false;
        MeeTuneKaraoke.removeModel();
        boolean removed = cleanUp(model.getParentFile());
        removed = cleanUp(new File(runtime, "ok")) && cleanUp(runtime.getParentFile()) && removed;
        notifyListeners();
        return removed;
    }

    private static void close(OrtSession session) {
        if (session == null) return;
        try {
            session.close();
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "Could not close Karaoke", e);
        }
    }

    private static final class OrtSession {
        private final Object env;
        private final AutoCloseable options;
        private Object session;
        private final Method createTensor;
        private final Method run;
        private final Method getOutput;
        private final Method getFloatBuffer;
        private final Method closeSession;
        private FloatBuffer inputBuffer;

        OrtSession(ClassLoader loader, File file) throws Exception {
            Class<?> environment = loader.loadClass("ai.onnxruntime.OrtEnvironment");
            Class<?> sessionType = loader.loadClass("ai.onnxruntime.OrtSession");
            Class<?> optionsType = loader.loadClass("ai.onnxruntime.OrtSession$SessionOptions");
            Class<?> tensor = loader.loadClass("ai.onnxruntime.OnnxTensor");
            Class<?> result = loader.loadClass("ai.onnxruntime.OrtSession$Result");
            createTensor = tensor.getMethod("createTensor", environment, FloatBuffer.class, long[].class);
            run = sessionType.getMethod("run", Map.class);
            getOutput = result.getMethod("get", int.class);
            getFloatBuffer = tensor.getMethod("getFloatBuffer");
            closeSession = sessionType.getMethod("close");
            env = environment.getMethod("getEnvironment").invoke(null);
            options = (AutoCloseable) optionsType.getConstructor().newInstance();
            try {
                optionsType.getMethod("setIntraOpNumThreads", int.class).invoke(options, 4);
                session = environment.getMethod("createSession", String.class, optionsType)
                        .invoke(env, file.getAbsolutePath(), options);
            } catch (Exception | LinkageError e) {
                options.close();
                throw e;
            }
        }

        synchronized float[] run(float[] input, int frames) throws Exception {
            if (session == null) throw new IllegalStateException("Karaoke session closed");
            // ORT can use native-order direct storage in place. Reuse it across splits.
            if (inputBuffer == null || inputBuffer.capacity() != input.length) {
                inputBuffer = ByteBuffer.allocateDirect(input.length * Float.BYTES)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
            }
            inputBuffer.clear();
            inputBuffer.put(input).flip();
            long[] shape = {1, 4, 2048, frames};
            try (AutoCloseable tensor = (AutoCloseable) createTensor.invoke(null, env, inputBuffer, shape);
                 AutoCloseable result = (AutoCloseable) run.invoke(session, Collections.singletonMap("input", tensor))) {
                FloatBuffer buffer = (FloatBuffer) getFloatBuffer.invoke(getOutput.invoke(result, 0));
                float[] output = new float[buffer.remaining()];
                buffer.get(output);
                return output;
            }
        }

        synchronized void close() throws Exception {
            try {
                closeSession.invoke(session);
            } finally {
                session = null;
                options.close();
            }
        }
    }
}
