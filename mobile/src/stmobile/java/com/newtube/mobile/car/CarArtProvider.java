package com.newtube.mobile.car;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * MEETUBE(android auto, issue #253): cover art for the car. Android Auto and Android Automotive
 * never fetch a web link themselves; they read a content URI from the app. This serves a YouTube
 * image (and only those hosts: the provider is exported for the car) from a file in the cache,
 * with rounded corners (the car draws covers square), and draws the tiles of the car's own folders.
 */
public final class CarArtProvider extends ContentProvider {

    /** The car-readable URI for a YouTube image link. */
    @NonNull
    public static Uri uri(@NonNull Context context, @NonNull String url) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".carart")
                .appendPath(url).build();
    }

    /** A tile for one of the car's folders: {@code icon} (a white drawable of this app) on a gradient. */
    @NonNull
    public static Uri tile(@NonNull Context context, @NonNull String icon, int from, int to) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".carart")
                .appendPath("tile").appendPath(icon)
                .appendPath(Integer.toHexString(from) + "-" + Integer.toHexString(to)).build();
    }

    private static boolean allowed(@Nullable Uri url) {
        String host = url != null ? url.getHost() : null;
        return url != null && "https".equals(url.getScheme()) && host != null
                && (host.endsWith(".googleusercontent.com") || host.endsWith(".ytimg.com") || host.endsWith(".ggpht.com"));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        File dir = new File(getContext().getCacheDir(), "car_art");
        if (uri.getPathSegments().size() == 3 && "tile".equals(uri.getPathSegments().get(0))) {
            return tile(dir, uri.getPathSegments().get(1), uri.getPathSegments().get(2));
        }
        String url = uri.getLastPathSegment();
        if (url == null || !allowed(Uri.parse(url))) {
            throw new FileNotFoundException("not a YouTube image: " + uri);
        }
        // ponytail: lives in the cache dir, which Android trims under storage pressure; no own LRU.
        File file = new File(dir, Long.toHexString(url.hashCode() & 0xffffffffL) + "_" + url.length() + "_s.png");
        if (!file.exists()) {
            File part = new File(dir, file.getName() + ".part");
            try {
                dir.mkdirs();
                HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
                http.setConnectTimeout(10_000);
                http.setReadTimeout(10_000);
                try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16 * 1024];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        out.write(buffer, 0, n);
                    }
                } finally {
                    http.disconnect();
                }
                round(part);
                if (!part.renameTo(file)) {
                    throw new FileNotFoundException("car art: rename failed");
                }
            } catch (FileNotFoundException e) {
                part.delete();
                throw e;
            } catch (Exception e) {
                part.delete();
                throw new FileNotFoundException("car art: " + e);
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The image in {@code file} again, its corners rounded (transparent). Left as it is if it will not decode. */
    private static void round(File file) throws Exception {
        Bitmap cover = BitmapFactory.decodeFile(file.getPath());
        if (cover == null) {
            return;
        }
        // Square first: the car crops a video's 16:9 cover to its middle, which would cut the corners off.
        int side = Math.min(cover.getWidth(), cover.getHeight());
        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        BitmapShader shader = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix middle = new Matrix();
        middle.setTranslate(-(cover.getWidth() - side) / 2f, -(cover.getHeight() - side) / 2f);
        shader.setLocalMatrix(middle);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(shader);
        float radius = side * 0.08f;
        new Canvas(out).drawRoundRect(new RectF(0, 0, side, side), radius, radius, paint);
        try (OutputStream stream = new FileOutputStream(file)) {
            out.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        cover.recycle();
        out.recycle();
    }

    /** Draws (once) a folder tile: a rounded gradient with a soft light at the top and the icon in the middle. */
    private ParcelFileDescriptor tile(File dir, String icon, String colors) throws FileNotFoundException {
        String[] pair = colors.split("-");
        int id = icon.startsWith("ic_") ? getContext().getResources().getIdentifier(icon, "drawable",
                getContext().getPackageName()) : 0;
        Drawable drawable = id != 0 ? ContextCompat.getDrawable(getContext(), id) : null;
        if (drawable == null || pair.length != 2) {
            throw new FileNotFoundException("no such tile: " + icon);
        }
        File file = new File(dir, "tile_" + icon + "_" + colors + ".png");
        if (!file.exists()) {
            int size = 384;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            RectF box = new RectF(0, 0, size, size);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(0, 0, size, size, (int) Long.parseLong(pair[0], 16),
                    (int) Long.parseLong(pair[1], 16), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, size * 0.08f, size * 0.08f, paint);
            paint.setShader(new RadialGradient(size * 0.25f, 0, size * 0.9f, Color.argb(70, 255, 255, 255),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, size * 0.08f, size * 0.08f, paint);
            int inset = size * 3 / 10;
            drawable.setBounds(inset, inset, size - inset, size - inset);
            drawable.draw(canvas);
            File part = new File(dir, file.getName() + ".part");
            dir.mkdirs();
            try (OutputStream stream = new FileOutputStream(part)) {
                out.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } catch (Exception e) {
                part.delete();
                throw new FileNotFoundException("car tile: " + e);
            } finally {
                out.recycle();
            }
            if (!part.renameTo(file)) {
                part.delete();
                throw new FileNotFoundException("car tile: rename failed");
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "image/*";
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
