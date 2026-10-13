package com.newtube.mobile.ui.search;

import android.util.Base64;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;

/**
 * METUBE(search filters): MediaServiceCore turns the filter dialog's choices into YouTube's
 * {@code sp} search param through a hand-filled table (SearchFilterHelper), and every combination
 * missing from it - This week + sort by upload date, a type with any feature... - searched with no
 * filter at all, silently. This fills the table's gaps with the param encoded the way YouTube does
 * (a small protobuf: field 1 = sort, field 2 = the filters), leaving its own entries untouched.
 *
 * <p>ponytail: fills a private table by reflection because the submodule is NewTube's and can't be
 * changed here; move {@link #encode} into SearchFilterHelper.toParams once MeTube has its own fork.</p>
 */
final class SearchFilterParams {
    private static final String TAG = SearchFilterParams.class.getSimpleName();
    // The table's index ranges (SearchFilterHelper's constants).
    private static final int DATES = 6, DURATIONS = 4, TYPES = 5, FEATURES = 7, SORTS = 4;
    private static boolean sFilled;

    private SearchFilterParams() {
    }

    /** Fill the table's empty combinations once; harmless if the class ever changes shape. */
    static void fillTable() {
        if (sFilled) {
            return;
        }
        sFilled = true;
        try {
            Class<?> helper = Class.forName("com.liskovsoft.youtubeapi.search.SearchFilterHelper");
            Method init = helper.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(null);
            Field field = helper.getDeclaredField("sParams");
            field.setAccessible(true);
            String[][][][][] table = (String[][][][][]) field.get(null);
            for (int date = 0; date < DATES; date++) {
                for (int duration = 0; duration < DURATIONS; duration++) {
                    for (int type = 0; type < TYPES; type++) {
                        for (int feature = 0; feature < FEATURES; feature++) {
                            for (int sort = 0; sort < SORTS; sort++) {
                                String[] row = table[date][duration][type][feature];
                                if (row[sort] == null && date + duration + type + feature + sort > 0) {
                                    row[sort] = encode(date, duration, type, feature, sort);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "Search filter table not filled: " + e);
        }
    }

    /** The {@code sp} param, URL-encoded, for the table's indices (0 = any / relevance). */
    static String encode(int date, int duration, int type, int feature, int sort) {
        byte[] proto = proto(date, duration, type, feature, sort);
        try {
            return URLEncoder.encode(Base64.encodeToString(proto, Base64.NO_WRAP), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The param's protobuf bytes (before base64). */
    static byte[] proto(int date, int duration, int type, int feature, int sort) {
        ByteArrayOutputStream filters = new ByteArrayOutputStream();
        if (date > 0) {
            field(filters, 1, date);
        }
        // YouTube narrows a date or duration filter to videos unless a type is chosen.
        int protoType = type > 0 ? type : (date > 0 || duration > 0 ? 1 : 0);
        if (protoType > 0) {
            field(filters, 2, protoType);
        }
        if (duration > 0) {
            field(filters, 3, new int[] {0, 1, 3, 2}[duration]); // under 4, 4-20, over 20
        }
        boolean live = feature == 1 || feature == 4 || feature == 6;
        boolean uhd = feature == 2 || feature == 4 || feature == 5 || feature == 6;
        boolean hdr = feature == 3 || feature == 5 || feature == 6;
        if (live) {
            field(filters, 8, 1);
        }
        if (uhd) {
            field(filters, 14, 1);
        }
        if (hdr) {
            field(filters, 25, 1);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (sort > 0) {
            field(out, 1, new int[] {0, 2, 3, 1}[sort]); // upload date, view count, rating
        }
        byte[] nested = filters.toByteArray();
        if (nested.length > 0 || sort == 0) {
            varint(out, 2 << 3 | 2);
            varint(out, nested.length);
            out.write(nested, 0, nested.length);
        }
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, int number, int value) {
        varint(out, number << 3);
        varint(out, value);
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
