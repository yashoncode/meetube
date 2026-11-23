package com.newtube.mobile.downloads;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Foreground service that drains the download queue one item at a time.
 *
 * <p>Same lifecycle shape as the cast and playback services: started from the foreground when
 * a download is enqueued, promoted with a progress notification (type dataSync, the one
 * Android 14 accepts for transfers), stopped when the queue is empty. A partial wake lock keeps
 * the CPU up between chunks with the screen off; Wi-Fi is left to the system, a paused radio
 * just makes a chunk retry.
 */
public final class MobileDownloadService extends Service {
    private static final String TAG = MobileDownloadService.class.getSimpleName();
    private static final String CHANNEL_ID = "newtube_downloads";
    private static final int NOTIFICATION_ID = 0x4E54_0010;
    private static final int DONE_NOTIFICATION_BASE = 0x4E54_0100;

    private static final String ACTION_START = "com.newtube.mobile.downloads.START";
    private static final String ACTION_CANCEL = "com.newtube.mobile.downloads.CANCEL";
    private static final String ACTION_PAUSE = "com.newtube.mobile.downloads.PAUSE";
    private static final String EXTRA_ITEM_ID = "item_id";

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "newtube-download"));
    private DownloadRegistry mRegistry;
    private NotificationManager mNotifications;
    @Nullable private PowerManager.WakeLock mWakeLock;
    private volatile boolean mDraining;
    private volatile boolean mForeground;
    /** The item the drain is running now, so a timeout can stop it. */
    @Nullable private volatile DownloadItem mRunning;
    /** Android 15+'s daily dataSync limit was reached: the drain stops and the queue waits for a resume. */
    private volatile boolean mTimedOut;

    /** Adds the item to the registry and makes sure the queue is being drained. */
    public static void enqueue(Context context, DownloadItem item) {
        DownloadRegistry.instance(context).add(item);
        start(context);
    }

    /** Re-runs a paused or failed item (part files, if any, resume where they stopped). */
    public static void resume(Context context, DownloadItem item) {
        item.state = DownloadItem.STATE_QUEUED;
        item.error = null;
        item.cancelRequested = false;
        item.pauseRequested = false;
        DownloadRegistry.instance(context).notifyChanged();
        start(context);
    }

    /**
     * Stops an item and keeps what it fetched. The running job polls the flag at its next
     * progress tick and marks itself paused; a queued item is paused right away.
     */
    public static void pause(Context context, DownloadItem item) {
        item.pauseRequested = true;
        if (item.state == DownloadItem.STATE_QUEUED) {
            item.state = DownloadItem.STATE_PAUSED;
            DownloadRegistry.instance(context).notifyChanged();
        }
    }

    /**
     * Stops an active item and removes it. The running job polls the flag at its next progress
     * tick and drops the part files itself; a queued item simply leaves the registry.
     */
    public static void cancel(Context context, DownloadItem item) {
        item.cancelRequested = true;
        if (item.state == DownloadItem.STATE_QUEUED) {
            DownloadRegistry.instance(context).remove(item);
        }
    }

    /**
     * MEETUBE(auto download): from a background worker. Android 12+ refuses a foreground start while
     * the app is not in front; then the worker drains the queue itself, with the same progress
     * notification (posted, not foreground). Issue #300: a worker only gets a window of minutes, so
     * when {@code stopped} turns true the running item stops at its next chunk, keeps its part files
     * and goes back in the queue, and a resume check picks it up where it stopped.
     */
    static void startOrDrain(Context context, BooleanSupplier stopped) {
        DownloadRegistry registry = DownloadRegistry.instance(context);
        if (registry.nextQueued() == null) {
            return;
        }
        try {
            start(context);
            return;
        } catch (RuntimeException e) {
            Log.w(TAG, "background start refused, draining in the worker: " + e);
        }
        NotificationManager notifications = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        createChannel(context, notifications);
        DownloadItem next;
        long[] lastCheck = {0};
        while (!stopped.getAsBoolean() && !AutoDownload.mustWait(context) && (next = registry.claimNextQueued()) != null) {
            boolean[] cut = {false};
            notifyProgress(context, notifications, next);
            new DownloadJob(context, next, registry, item -> {
                if (stopped.getAsBoolean() && !item.pauseRequested) {
                    cut[0] = true;
                    item.pauseRequested = true; // the job stops at its next chunk, parts kept
                }
                if (waitForWifi(context, item, lastCheck)) cut[0] = true;
                notifyProgress(context, notifications, item);
            }).run();
            if (cut[0]) {
                requeue(registry, next);
            }
            if (next.isDone()) {
                postDoneNotification(context, notifications, next);
            }
        }
        if (notifications != null) {
            notifications.cancel(NOTIFICATION_ID);
        }
        if (registry.nextQueued() != null) {
            AutoDownload.resumeQueue(context);
        }
    }

    /** An item stopped by the system, not by the user: back in the queue, its part files kept. */
    private static void requeue(DownloadRegistry registry, DownloadItem item) {
        if (item.state == DownloadItem.STATE_PAUSED) {
            item.state = DownloadItem.STATE_QUEUED;
            item.pauseRequested = false;
            registry.notifyChanged();
        }
    }

    private static void notifyProgress(Context context, @Nullable NotificationManager notifications, DownloadItem item) {
        if (notifications != null) {
            notifications.notify(NOTIFICATION_ID, buildProgressNotification(context, item));
        }
    }

    /** Starts the queue if anything waits in it (Settings turned Wi-Fi only off). */
    static void kick(Context context) {
        if (DownloadRegistry.instance(context).nextQueued() != null) {
            start(context);
        }
    }

    /**
     * Issue #318: Wi-Fi only is on and the network turned metered. The running item stops at its
     * next chunk with its part files and goes back in the queue (checked at most once a second).
     */
    private static boolean waitForWifi(Context context, DownloadItem item, long[] lastCheck) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (item.pauseRequested || now - lastCheck[0] < 1000) return false;
        lastCheck[0] = now;
        if (!AutoDownload.mustWait(context)) return false;
        item.pauseRequested = true;
        return true;
    }

    private static void start(Context context) {
        Intent intent = new Intent(context, MobileDownloadService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mRegistry = DownloadRegistry.instance(this);
        mNotifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel(this, mNotifications);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // Every start must promote promptly: a startForegroundService() that never reaches
        // startForeground() is an ANR-class crash on API 26+. Cancel and Pause arrive the same way
        // (the notification actions), so they promote too and step back down when nothing is running.
        promote(buildProgressNotification(this, mRegistry.nextQueued()));

        if (ACTION_CANCEL.equals(action) || ACTION_PAUSE.equals(action)) {
            DownloadItem item = mRegistry.find(intent.getStringExtra(EXTRA_ITEM_ID));
            if (item != null && ACTION_PAUSE.equals(action)) {
                pause(this, item);
            } else if (item != null) {
                cancel(this, item);
            }
            synchronized (mDrainLock) {
                if (!mDraining) {
                    stopForegroundCompat();
                    stopSelf();
                }
            }
            return START_NOT_STICKY;
        }

        drainIfIdle();
        return START_NOT_STICKY;
    }

    private final Object mDrainLock = new Object();

    private void drainIfIdle() {
        synchronized (mDrainLock) {
            if (mDraining) {
                return;
            }
            mDraining = true;
        }
        acquireWakeLock();
        mWorker.execute(this::drain);
    }

    private void drain() {
        try {
            long[] lastCheck = {0};
            while (!mTimedOut) {
                if (AutoDownload.mustWait(this)) { // issue #318: the queue waits; Wi-Fi resumes it
                    AutoDownload.resumeQueue(this);
                    break;
                }
                DownloadItem running = mRegistry.claimNextQueued();
                if (running == null) {
                    synchronized (mDrainLock) {
                        // Re-check under the lock: an enqueue that raced the empty check must not be
                        // stranded behind a mDraining flag nobody resets.
                        if (mRegistry.nextQueued() == null) {
                            mDraining = false;
                            break;
                        }
                    }
                    continue;
                }
                mRunning = running;
                updateProgressNotification(running);
                boolean[] cut = {false};
                new DownloadJob(this, running, mRegistry, item -> {
                    if (waitForWifi(this, item, lastCheck)) cut[0] = true;
                    updateProgressNotification(item);
                }).run();
                mRunning = null;
                if (mTimedOut || cut[0]) {
                    requeue(mRegistry, running);
                }
                if (running.isDone()) {
                    postDoneNotification(this, mNotifications, running);
                }
            }
        } finally {
            synchronized (mDrainLock) {
                mDraining = false;
            }
            releaseWakeLock();
            stopForegroundCompat();
            stopSelf();
        }
    }

    /**
     * Issue #300: Android 15+ gives a dataSync service 6 hours a day. Past that it must stop within
     * seconds: the running item stops at its next chunk and goes back in the queue, and a resume
     * check goes on in background windows until the service may run again.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "dataSync time limit reached, handing the queue to a resume check");
        mTimedOut = true;
        DownloadItem running = mRunning;
        if (running != null) {
            running.pauseRequested = true;
        }
        stopForegroundCompat();
        stopSelf();
        AutoDownload.resumeQueue(this);
    }

    // ---------------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------------

    private void promote(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            mForeground = true;
        } catch (RuntimeException e) {
            // API 31+ refuses a background promotion; the queue still drains while the process lives.
            Log.w(TAG, "foreground promotion refused: " + e);
        }
    }

    private void stopForegroundCompat() {
        if (!mForeground) {
            return;
        }
        mForeground = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void updateProgressNotification(DownloadItem item) {
        if (!mForeground) {
            return;
        }
        mNotifications.notify(NOTIFICATION_ID, buildProgressNotification(this, item));
    }

    private static Notification buildProgressNotification(Context context, @Nullable DownloadItem item) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_downloads_fill)
                .setContentIntent(openDownloadsIntent(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        if (item == null) {
            builder.setContentTitle(context.getString(R.string.header_downloads))
                    .setProgress(0, 0, true);
            return builder.build();
        }

        builder.setContentTitle(item.title);
        if (item.state == DownloadItem.STATE_PROCESSING) {
            builder.setContentText(context.getString(R.string.mobile_download_state_processing))
                    .setProgress(0, 0, true);
        } else {
            int percent = item.progressPercent();
            builder.setContentText(DownloadProgressText.of(context, item))
                    .setProgress(100, Math.max(0, percent), percent < 0);
            builder.addAction(R.drawable.ic_player_pause, context.getString(R.string.mobile_download_action_pause),
                    actionIntent(context, item, ACTION_PAUSE));
            builder.addAction(R.drawable.ic_sheet_close, context.getString(R.string.cancel),
                    actionIntent(context, item, ACTION_CANCEL));
        }
        return builder.build();
    }

    private static void postDoneNotification(Context context, @Nullable NotificationManager notifications, DownloadItem item) {
        if (notifications == null) {
            return;
        }
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_watch_downloaded)
                .setContentTitle(context.getString(R.string.mobile_download_done_title))
                .setContentText(item.title)
                .setContentIntent(openDownloadsIntent(context))
                .setAutoCancel(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
        notifications.notify(DONE_NOTIFICATION_BASE + (item.id.hashCode() & 0xFF), notification);
    }

    private static PendingIntent openDownloadsIntent(Context context) {
        Intent intent = new Intent(context, MobileBrowseActivity.class)
                .setAction(MobileBrowseActivity.ACTION_OPEN_DOWNLOADS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent actionIntent(Context context, DownloadItem item, String action) {
        Intent intent = new Intent(context, MobileDownloadService.class)
                .setAction(action)
                .putExtra(EXTRA_ITEM_ID, item.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(context, item.id.hashCode(), intent, flags)
                : PendingIntent.getService(context, item.id.hashCode(), intent, flags);
    }

    private static void createChannel(Context context, @Nullable NotificationManager notifications) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.mobile_download_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.mobile_download_channel_desc));
            channel.setShowBadge(false);
            notifications.createNotificationChannel(channel);
        }
    }

    // ---------------------------------------------------------------------------------
    // Locks
    // ---------------------------------------------------------------------------------

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && mWakeLock == null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeeTube:downloads");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire(6 * 60 * 60 * 1000L);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "wake lock unavailable: " + e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (RuntimeException ignored) {
        }
        mWakeLock = null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        mWorker.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
