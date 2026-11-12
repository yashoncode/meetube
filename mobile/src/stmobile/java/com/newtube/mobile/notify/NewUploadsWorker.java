package com.newtube.mobile.notify;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.newtube.mobile.MobileMainApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * MEETUBE(issue #220): new uploads from your subscriptions in the notification shade, app closed or
 * not. Every 3 hours (online, battery not low) this reads the Subscriptions feed (the account's, or
 * the local subscriptions when signed out) and posts each video it hasn't seen; a tap plays it in
 * MeeTube. The first check only remembers what is there. Not push: a video shows up at the next check.
 */
public class NewUploadsWorker extends Worker {
    private static final String TAG = "NewUploads";
    private static final String WORK = "new_uploads";
    private static final String CHANNEL_ID = "meetube_new_uploads";
    private static final String PREFS = "new_uploads";
    private static final String KEY_ON = "on";
    private static final String KEY_SEEN = "seen";
    /** ponytail: a long gap (phone off a day) posts only the newest few; the rest are marked seen. */
    private static final int MAX_POSTS = 5;
    private static final int SEEN_MAX = 400;

    public NewUploadsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ON, true);
    }

    public static void setEnabled(Context context, boolean on) {
        // Off forgets what was seen: turning it back on starts quiet instead of posting the gap
        prefs(context).edit().putBoolean(KEY_ON, on).remove(KEY_SEEN).apply();
        schedule(context);
    }

    /** At app start and when the switch changes. */
    public static void schedule(Context context) {
        WorkManager work = WorkManager.getInstance(context);
        if (!isEnabled(context)) {
            work.cancelUniqueWork(WORK);
            return;
        }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(NewUploadsWorker.class, 3, TimeUnit.HOURS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .build())
                        .build());
        // Debug builds: adb shell setprop debug.arc.new_uploads_now 1, then start the app: one check now
        if (BuildConfig.DEBUG && "1".equals(MobileMainApplication.getDebugSystemProperty("debug.arc.new_uploads_now"))) {
            work.enqueueUniqueWork(WORK + "_now", ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(NewUploadsWorker.class).build());
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!isEnabled(context)) {
            return Result.success();
        }
        List<MediaItem> items;
        try {
            MediaGroup feed = YouTubeServiceManager.instance().getContentService().getSubscriptions();
            items = feed != null ? feed.getMediaItems() : null;
        } catch (Exception e) {
            Log.w(TAG, "feed: " + e);
            return Result.success(); // signed out with no subscriptions, offline: the next check tries again
        }
        if (items == null || items.isEmpty()) {
            return Result.success();
        }

        String stored = prefs(context).getString(KEY_SEEN, null);
        boolean first = stored == null;
        Set<String> seen = new LinkedHashSet<>(first ? new ArrayList<>() : Arrays.asList(TextUtils.split(stored, ",")));
        List<MediaItem> fresh = new ArrayList<>();
        for (MediaItem item : items) { // newest first
            String id = item.getVideoId();
            if (id == null || item.isUpcoming() || !seen.add(id)) {
                continue;
            }
            if (!first && fresh.size() < MAX_POSTS) {
                fresh.add(item);
            }
        }
        List<String> ids = new ArrayList<>(seen);
        prefs(context).edit().putString(KEY_SEEN,
                TextUtils.join(",", ids.subList(Math.max(0, ids.size() - SEEN_MAX), ids.size()))).apply();

        if (!fresh.isEmpty() && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            createChannel(context);
            for (int i = fresh.size() - 1; i >= 0; i--) { // oldest first, so the newest sits on top
                post(context, fresh.get(i));
            }
        }
        return Result.success();
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.mobile_new_uploads_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.mobile_new_uploads_channel_desc));
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @SuppressLint("MissingPermission") // the caller checked areNotificationsEnabled()
    private static void post(Context context, MediaItem item) {
        String videoId = item.getVideoId();
        Log.d(TAG, "new upload " + videoId);
        // The same router a shared YouTube link goes through: it opens the player in MeeTube's task
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId))
                .setComponent(new ComponentName(context, "com.liskovsoft.smartyoutubetv2.tv.ui.main.IntentRouterActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int id = videoId.hashCode();
        PendingIntent tap = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_about_notifications)
                .setContentTitle(channelName(item.getAuthor()))
                .setContentText(item.getTitle())
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);
        Bitmap thumb = thumbnail(context, videoId);
        if (thumb != null) {
            builder.setLargeIcon(thumb)
                    .setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb)
                            .bigLargeIcon((Bitmap) null).setSummaryText(item.getTitle()));
        }
        NotificationManagerCompat.from(context).notify(TAG, id, builder.build());
    }

    /** "Zee Tamil • @zeetamil" as "Zee Tamil". */
    private static String channelName(String author) {
        int dot = author != null ? author.indexOf(" • ") : -1;
        return dot > 0 ? author.substring(0, dot) : author;
    }

    private static Bitmap thumbnail(Context context, String videoId) {
        try {
            return Glide.with(context).asBitmap().load("https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg")
                    .submit(320, 180).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null; // text only
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
