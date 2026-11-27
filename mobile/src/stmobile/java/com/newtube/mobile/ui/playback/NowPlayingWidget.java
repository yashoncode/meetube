package com.newtube.mobile.ui.playback;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Objects;

/**
 * MEETUBE(widget): the home screen's Now playing, songs and videos alike. MobilePlaybackService
 * pushes its state ({@link #show}); the buttons drive its media session. With nothing playing,
 * the whole widget opens the app.
 */
public class NowPlayingWidget extends AppWidgetProvider {
    private static final String ACTION_PREVIOUS = "io.yash.meetube.widget.PREVIOUS";
    private static final String ACTION_PLAY_PAUSE = "io.yash.meetube.widget.PLAY_PAUSE";
    private static final String ACTION_NEXT = "io.yash.meetube.widget.NEXT";

    /** The playing session, set by MobilePlaybackService while a player is attached. */
    @Nullable
    static MediaSessionCompat sSession;
    private static boolean sPlaying;
    /** The last state pushed: the session syncs on every player event, the widget only on a change. */
    @Nullable
    private static Object[] sShown;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (sSession == null) idle(context); // a widget just placed, or the app was gone
        else sShown = null; // the next sync redraws it
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (!ACTION_PREVIOUS.equals(action) && !ACTION_PLAY_PAUSE.equals(action) && !ACTION_NEXT.equals(action)) return;
        MediaSessionCompat session = sSession;
        if (session == null) { // the app was killed under a playing widget
            idle(context);
            return;
        }
        MediaControllerCompat.TransportControls controls = session.getController().getTransportControls();
        if (ACTION_PREVIOUS.equals(action)) controls.skipToPrevious();
        else if (ACTION_NEXT.equals(action)) controls.skipToNext();
        else if (sPlaying) controls.pause();
        else controls.play();
    }

    /** Playing (or paused on) a video: its title, channel, art and the controls. */
    static void show(Context context, String title, @Nullable String author, @Nullable Bitmap art,
                     boolean playing, @Nullable PendingIntent open) {
        sPlaying = playing;
        Object[] state = {title, author, art, playing};
        if (java.util.Arrays.equals(state, sShown) || !hasWidgets(context)) return;
        sShown = state;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_now_playing);
        views.setTextViewText(R.id.widget_title, title);
        views.setTextViewText(R.id.widget_author, author != null ? author : "");
        // A 56dp square: the session's 480px art (~900 KB) nears the binder's 1 MB parcel limit.
        if (art != null) views.setImageViewBitmap(R.id.widget_art, Bitmap.createScaledBitmap(art,
                192, Math.max(1, 192 * art.getHeight() / art.getWidth()), true));
        else views.setImageViewResource(R.id.widget_art, R.mipmap.app_icon);
        views.setImageViewResource(R.id.widget_play, playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        views.setOnClickPendingIntent(R.id.widget_root, open != null ? open : openApp(context));
        views.setOnClickPendingIntent(R.id.widget_previous, button(context, ACTION_PREVIOUS));
        views.setOnClickPendingIntent(R.id.widget_play, button(context, ACTION_PLAY_PAUSE));
        views.setOnClickPendingIntent(R.id.widget_next, button(context, ACTION_NEXT));
        push(context, views);
    }

    /** Nothing playing: the app's name, and a tap anywhere opens it. */
    static void idle(Context context) {
        sPlaying = false;
        Object[] state = {null};
        if (java.util.Arrays.equals(state, sShown) || !hasWidgets(context)) return;
        sShown = state;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_now_playing);
        views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name));
        views.setTextViewText(R.id.widget_author, context.getString(R.string.widget_nothing_playing));
        views.setImageViewResource(R.id.widget_art, R.mipmap.app_icon);
        views.setImageViewResource(R.id.widget_play, R.drawable.ic_player_play);
        PendingIntent open = openApp(context);
        for (int id : new int[] {R.id.widget_root, R.id.widget_previous, R.id.widget_play, R.id.widget_next}) {
            views.setOnClickPendingIntent(id, open);
        }
        push(context, views);
    }

    private static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager != null && manager.getAppWidgetIds(new ComponentName(context, NowPlayingWidget.class)).length > 0;
    }

    private static void push(Context context, RemoteViews views) {
        AppWidgetManager.getInstance(context).updateAppWidget(new ComponentName(context, NowPlayingWidget.class), views);
    }

    private static PendingIntent button(Context context, String action) {
        Intent intent = new Intent(context, NowPlayingWidget.class).setAction(action);
        return PendingIntent.getBroadcast(context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openApp(Context context) {
        Intent launch = Objects.requireNonNull(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        return PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
