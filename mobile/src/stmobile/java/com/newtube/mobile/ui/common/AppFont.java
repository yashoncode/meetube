package com.newtube.mobile.ui.common;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * MEETUBE(font): Settings > Customization > Font, for all of MeeTube and MeeTune. The pick is a
 * theme overlay (ThemeOverlay.MeeTube.Font.*) on every screen: text with no family of its own takes
 * its android:fontFamily, and styles and layouts that want medium or black text name
 * ?attr/appFontMedium / ?attr/appFontBlack. Text set or drawn in code asks {@link #regular},
 * {@link #medium} or {@link #bold}. Android 7 keeps the system font: its TextView can't read a font
 * resource from a theme.
 *
 * {@link #CUSTOM} is a TTF/OTF the user picks ({@link Picker}), copied to the files dir. A file
 * can't be a font resource, so it goes into Typeface's family map as {@value #FAMILY} and the
 * theme names that family as a string, the way the base theme names sans-serif.
 */
public final class AppFont {
    public static final String SYSTEM = "system";
    public static final String GOOGLE_SANS = "google_sans";
    public static final String MINECRAFT = "minecraft";
    public static final String VAZIRMATN = "vazirmatn";
    public static final String CUSTOM = "custom";

    private static final String TAG = "AppFont";
    private static final String PREFS = "meetube_font";
    private static final String FAMILY = "meetube-custom";
    private static final long MAX_BYTES = 30L * 1024 * 1024; // CJK fonts run 15-20 MB
    @Nullable private static Typeface sCustom;
    private static boolean sRegistered;

    private AppFont() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    public static String current(Context context) {
        String font = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("font", SYSTEM);
        // A font no longer offered (SF Pro Display), or a font file that's gone, reads as System default.
        return GOOGLE_SANS.equals(font) || MINECRAFT.equals(font) || VAZIRMATN.equals(font)
                || CUSTOM.equals(font) && file(context).exists() ? font : SYSTEM;
    }

    /** {@link #CUSTOM} opens the file picker, which sets it once a file reads as a font. */
    public static void set(Context context, String font) {
        if (CUSTOM.equals(font)) {
            context.startActivity(new Intent(context, Picker.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("font", font).apply();
    }

    /** The picked file's name, or null when there is none. */
    @Nullable
    public static String customName(Context context) {
        return file(context).exists() ? context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("custom_name", null) : null;
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "custom_font");
    }

    /** Puts the file into Typeface's family map once per process; false when that can't be done. */
    private static boolean register(Context context) {
        if (sCustom != null) {
            return sRegistered;
        }
        sCustom = new Typeface.Builder(file(context)).build();
        if (sCustom == null) {
            return false;
        }
        try {
            // ponytail: hidden API (unsupported list, allowed today); if an Android release blocks
            // it, themed text keeps the system font and only text set in code takes the file.
            Field field = Typeface.class.getDeclaredField("sSystemFontMap");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Typeface> map = (Map<String, Typeface>) field.get(null);
            map.put(FAMILY, sCustom);
            map.put(FAMILY + "-black", Typeface.create(sCustom, Typeface.BOLD));
            sRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "custom font not registered", e);
        }
        return sRegistered;
    }

    public static void applyTo(Context context, Resources.Theme theme) {
        if (!isSupported()) {
            return;
        }
        String font = current(context);
        int overlay = GOOGLE_SANS.equals(font) ? R.style.ThemeOverlay_MeeTube_Font_GoogleSans
                : MINECRAFT.equals(font) ? R.style.ThemeOverlay_MeeTube_Font_Minecraft
                : VAZIRMATN.equals(font) ? R.style.ThemeOverlay_MeeTube_Font_Vazirmatn
                : CUSTOM.equals(font) && register(context) ? R.style.ThemeOverlay_MeeTube_Font_Custom : 0;
        if (overlay != 0) {
            theme.applyStyle(overlay, true);
        }
    }

    public static Typeface regular(Context context) {
        return resolve(context, R.attr.appFontRegular, "sans-serif");
    }

    public static Typeface medium(Context context) {
        return resolve(context, R.attr.appFontMedium, "sans-serif-medium");
    }

    public static Typeface bold(Context context) {
        return Typeface.create(regular(context), Typeface.BOLD);
    }

    private static Typeface resolve(Context context, int attr, String system) {
        TypedValue value = new TypedValue();
        // A font resource resolves to its file path with a resource id; a family name to a bare string.
        if (isSupported() && context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                try {
                    Typeface font = ResourcesCompat.getFont(context, value.resourceId);
                    if (font != null) {
                        return font;
                    }
                } catch (Resources.NotFoundException ignored) {
                    // fall through to the system family
                }
            } else if (value.string != null) {
                system = value.string.toString();
            }
        }
        return Typeface.create(system, Typeface.NORMAL);
    }

    /**
     * The invisible host for the system file picker (like SeekThumb): copies the pick, keeps it only
     * if Android reads it as a font, then offers the restart.
     */
    public static final class Picker extends LocalizedPickerActivity {
        private static final int REQUEST_FONT = 1;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null) {
                return; // the picker is already up
            }
            // Any type: file managers label fonts font/ttf, application/x-font-ttf or octet-stream.
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try {
                startActivityForResult(pick, REQUEST_FONT);
            } catch (ActivityNotFoundException e) {
                finish();
            }
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (uri != null) {
                if (copy(uri)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("font", CUSTOM)
                            .putString("custom_name", name(uri)).apply();
                    Context app = getApplicationContext();
                    MobileSnackbar.show(this, getString(R.string.mobile_settings_restart_needed),
                            getString(R.string.mobile_settings_restart), () -> Utils.restartTheApp(app));
                } else {
                    MobileSnackbar.show(this, R.string.mobile_settings_font_file_failed);
                }
            }
            finish();
        }

        /** The pick replaces the current file only when it reads as a font; a bad pick keeps the old. */
        private boolean copy(Uri uri) {
            File tmp = new File(getFilesDir(), "custom_font.tmp");
            long total = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (in != null && (n = in.read(buf)) > 0 && (total += n) <= MAX_BYTES) {
                    out.write(buf, 0, n);
                }
            } catch (Exception e) {
                total = -1;
            }
            boolean ok = total > 0 && total <= MAX_BYTES && new Typeface.Builder(tmp).build() != null
                    && tmp.renameTo(file(this));
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return ok;
        }

        @Nullable
        private String name(Uri uri) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) {
                    return c.getString(0).replaceFirst("\\.[^.]+$", ""); // "Inter.ttf" -> "Inter"
                }
            } catch (Exception ignored) {
                // no name: the option keeps its own label
            }
            return null;
        }
    }
}
