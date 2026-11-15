package com.newtube.mobile.downloads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileSnackbar;

/**
 * MEETUBE(download-folder): a folder the user picks through the system picker (internal storage,
 * an SD card, anything the picker offers), one for videos and one for music. Unset means the default:
 * Movies/MeeTube for videos, Music/MeeTube for music.
 * Opened from Settings > General and by a long press on Download.
 */
public final class DownloadFolder {
    private static final String PREFS = "meetube_downloads";
    private static final String KEY_TREE = "folder_tree_uri"; // videos (and, before 3.4.3, music too)
    private static final String KEY_TREE_AUDIO = "folder_tree_uri_audio"; // "" = the default; absent = as videos
    public static final String EXTRA_AUDIO = "audio";

    private DownloadFolder() {
    }

    /** The muxer writes into a file descriptor, which needs Android 8; older phones keep the default. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    @Nullable
    static Uri get(Context context, boolean audio) {
        SharedPreferences prefs = prefs(context);
        String tree = audio && prefs.contains(KEY_TREE_AUDIO)
                ? prefs.getString(KEY_TREE_AUDIO, null)
                : prefs.getString(KEY_TREE, null); // a music folder never set follows the video one, as before
        return isSupported() && tree != null && !tree.isEmpty() ? Uri.parse(tree) : null;
    }

    static void set(Context context, boolean audio, @Nullable Uri tree) {
        Uri old = get(context, audio);
        Uri other = get(context, !audio);
        // a music folder never set follows the video one: pin it before the video folder changes
        if (!audio && !prefs(context).contains(KEY_TREE_AUDIO)) {
            prefs(context).edit().putString(KEY_TREE_AUDIO, old != null ? old.toString() : "").apply();
            other = old;
        }
        if (old != null && !old.equals(tree) && !old.equals(other)) {
            try {
                context.getContentResolver().releasePersistableUriPermission(old,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // already gone (folder deleted, permission revoked)
            }
        }
        prefs(context).edit().putString(audio ? KEY_TREE_AUDIO : KEY_TREE, tree != null ? tree.toString() : "").apply();
    }

    /** "Download/MeeTube", or the default ("Movies/MeeTube", "Music/MeeTube"). */
    public static CharSequence label(Context context, boolean audio) {
        Uri tree = get(context, audio);
        if (tree == null) {
            return context.getString(audio ? R.string.mobile_download_folder_default_audio
                    : R.string.mobile_download_folder_default_video);
        }
        String id = DocumentsContract.getTreeDocumentId(tree); // "primary:Download/MeeTube", "1A2B-3C4D:Videos"
        int colon = id.indexOf(':');
        String volume = colon >= 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if ("primary".equals(volume)) {
            return path.isEmpty() ? context.getString(R.string.mobile_download_folder_internal) : path;
        }
        return path.isEmpty() ? volume : volume + "/" + path;
    }

    /** A long press on Download: which folder to change, videos or music. */
    public static AlertDialog showKinds(Activity activity) {
        CharSequence[] items = {
                activity.getString(R.string.mobile_download_folder_video) + ": " + label(activity, false),
                activity.getString(R.string.mobile_download_folder_audio) + ": " + label(activity, true)
        };
        return new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_download_folder)
                .setItems(items, (dialog, which) -> showOptions(activity, which == 1))
                .show();
    }

    /** Keep the default or pick a folder for videos or music; returns the shown dialog. */
    public static AlertDialog showOptions(Activity activity, boolean audio) {
        CharSequence[] items = {
                activity.getString(R.string.mobile_download_folder_choose),
                activity.getString(R.string.mobile_download_folder_use_default)
        };
        return new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(activity.getString(R.string.mobile_download_folder_title, label(activity, audio)))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        activity.startActivity(new Intent(activity, DownloadFolderActivity.class)
                                .putExtra(EXTRA_AUDIO, audio));
                    } else {
                        set(activity, audio, null);
                        MobileSnackbar.show(activity, activity.getString(R.string.mobile_download_folder_set,
                                label(activity, audio)));
                    }
                })
                .show();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
