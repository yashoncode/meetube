package com.newtube.mobile.update;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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

import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.ReleaseNotes;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.MobileMainApplication;
import com.newtube.mobile.ui.update.MobileUpdateActivity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MEETUBE(update notify): a new version is announced in the notification shade, also when the app
 * isn't opened. Every 12 hours (online, battery not low) this runs the same quiet manifest check as
 * Home's ({@link AppUpdates#checkIfDue}) and posts one notification per version; a tap opens the
 * update sheet. Follows Settings > About > Notify about updates. Not posted while MeeTube is on
 * screen (Home's pop-up says it there) or once the user opened that version's sheet.
 */
public class UpdateNotifyWorker extends Worker {
    private static final String TAG = "UpdateNotifyWorker";
    private static final String WORK = "update_notify";
    private static final String CHANNEL_ID = "meetube_new_version";
    private static final int NOTIFICATION_ID = 0x4E5503;
    /** The manifest is ~2 KB; a check that hasn't answered by then failed quietly. */
    private static final long CHECK_TIMEOUT_S = 30;
    private static final int NOTES_LINES = 3;

    public UpdateNotifyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** At app start and when "Notify about updates" changes: on with update checks, off without. */
    public static void schedule(Context context) {
        WorkManager work = WorkManager.getInstance(context);
        if (!BuildConfig.IN_APP_UPDATES || !new AppUpdateChecker(context, null).isUpdateCheckEnabled()) {
            work.cancelUniqueWork(WORK);
            return;
        }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(UpdateNotifyWorker.class, 12, TimeUnit.HOURS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .build())
                        .build());
        // Debug builds: run it once now, with the app closed - with debug.arc.update_manifest pointing
        // at a test manifest that offers a newer version:
        // adb shell setprop debug.arc.update_notify_now 1, then wake the process without opening a
        // screen (am broadcast -n <pkg>/androidx.work.impl.diagnostics.DiagnosticsReceiver)
        if (BuildConfig.DEBUG && "1".equals(MobileMainApplication.getDebugSystemProperty("debug.arc.update_notify_now"))) {
            work.enqueueUniqueWork(WORK + "_now", ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(UpdateNotifyWorker.class).build());
        }
    }

    /** The update sheet is open: the notification has done its job. */
    public static void clear(Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AppUpdates updates = AppUpdates.instance(context);
        CountDownLatch answered = new CountDownLatch(1);
        AppUpdates.Listener listener = answered::countDown;
        Handler main = new Handler(Looper.getMainLooper());
        // AppUpdates is main-thread state, like everything that calls it
        main.post(() -> {
            updates.addListener(listener);
            if (!updates.checkIfDue("background")) {
                answered.countDown(); // not due (or off/offline): judge what the last check found
            }
        });
        await(answered, CHECK_TIMEOUT_S);
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            updates.removeListener(listener);
            if (updates.shouldNotify() && !inForeground() && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                post(context, updates);
                updates.markNotified();
            }
            done.countDown();
        });
        await(done, 5); // the work ends (and the process may go) only after the post
        return Result.success();
    }

    private static void await(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean inForeground() {
        ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(state);
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    @SuppressLint("MissingPermission") // the caller checked areNotificationsEnabled()
    private static void post(Context context, AppUpdates updates) {
        String version = updates.getUpdateVersionName();
        Log.d(TAG, "new version " + version);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.mobile_update_notify_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.mobile_update_notify_channel_desc));
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        // Expanded: the newest release's first lines as bullets, then a soft coffee line
        String text = context.getString(R.string.mobile_update_notify_text, version);
        StringBuilder big = new StringBuilder(text);
        ReleaseNotes newest = updates.getNewestNotes();
        if (newest != null && newest.lines != null && !newest.lines.isEmpty()) {
            big.append("\n");
            for (int i = 0; i < Math.min(NOTES_LINES, newest.lines.size()); i++) {
                big.append("\n• ").append(newest.lines.get(i));
            }
        }
        big.append("\n\n").append(context.getString(R.string.mobile_update_notify_love));

        PendingIntent tap = PendingIntent.getActivity(context, NOTIFICATION_ID, MobileUpdateActivity.showIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mobile_update)
                .setContentTitle(context.getString(R.string.mobile_update_notify_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(big))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build());
    }
}
