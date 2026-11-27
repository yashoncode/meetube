package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Arrays;
import java.util.concurrent.Executors;

/**
 * MEETUBE(issue #378): the app icon's menu (hold the icon): Shorts, Search, Subscriptions and MeeTune,
 * each one draggable onto the home screen. Every one goes through Splash, so a cold start runs its
 * set-up (prefs, master password) as the icon does; Home then hands over ({@link #take}).
 */
public final class AppShortcuts {
    public static final String SHORTS = "shorts";
    public static final String SEARCH = "search";
    public static final String SUBSCRIPTIONS = "subscriptions";
    public static final String MEETUNE = "meetune";
    /** Quick actions widget only. */
    public static final String HOME = "home";
    public static final String VOICE = "voice";
    private static final String ACTION = "io.yash.meetube.action.SHORTCUT";
    private static final String EXTRA_TARGET = "target";
    private static final String SPLASH = "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity";
    /** Red of the app's heart. */
    private static final int RED = 0xFFFF2D55;

    @Nullable
    private static String sPending;

    private AppShortcuts() {
    }

    /** Called from Home: sets the menu's entries (a binder call, so off the main thread). */
    public static void publish(Context context) {
        Context app = context.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ShortcutManagerCompat.setDynamicShortcuts(app, Arrays.asList(
                        build(app, SHORTS, R.string.header_shorts, glyph(app, R.drawable.ic_nav_shorts_fill), 0),
                        build(app, SEARCH, R.string.title_search, glyph(app, R.drawable.ic_mobile_search), 1),
                        build(app, SUBSCRIPTIONS, R.string.header_subscriptions,
                                glyph(app, R.drawable.ic_nav_subscriptions_fill), 2),
                        meetune(app)));
            } catch (RuntimeException ignored) { // a rate limit or a vendor quirk: the menu is optional
            }
        });
    }

    /** MeeTune settings' Add to Home screen: the launcher asks where to place it. */
    public static boolean pinMeeTune(Activity activity) {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(activity)
                && ShortcutManagerCompat.requestPinShortcut(activity, meetune(activity), null);
    }

    /** Splash got an intent: one of ours waits for Home. */
    public static void fromIntent(@Nullable Intent intent) {
        if (intent != null && ACTION.equals(intent.getAction())) sPending = intent.getStringExtra(EXTRA_TARGET);
    }

    /** Home, resumed: the shortcut it should open, once. */
    @Nullable
    public static String take() {
        String target = sPending;
        sPending = null;
        return target;
    }

    private static ShortcutInfoCompat meetune(Context context) {
        return build(context, MEETUNE, R.string.meetune_name, IconCompat.createWithResource(context,
                Build.VERSION.SDK_INT >= 26 ? R.mipmap.ic_meetune_shortcut : R.drawable.ic_meetune), 3);
    }

    private static Intent intent(Context context, String target) {
        return new Intent(ACTION).setClassName(context, SPLASH).putExtra(EXTRA_TARGET, target);
    }

    /** A widget's tap: the same way in as the icon's menu. */
    static android.app.PendingIntent open(Context context, String target) {
        return android.app.PendingIntent.getActivity(context, target.hashCode(),
                intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    private static ShortcutInfoCompat build(Context context, String id, int label, IconCompat icon, int rank) {
        Intent intent = intent(context, id);
        return new ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(context.getString(label))
                .setIcon(icon)
                .setIntent(intent)
                .setRank(rank)
                .build();
    }

    /** A tab's glyph in the app's red on white, as an adaptive icon (the launcher's shape). */
    private static IconCompat glyph(Context context, int drawable) {
        int size = Math.round(108 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Drawable d = ContextCompat.getDrawable(context, drawable).mutate();
        d.setTint(RED);
        int inset = size * 34 / 108; // a 40dp glyph inside the 72dp safe zone
        d.setBounds(inset, inset, size - inset, size - inset);
        d.draw(canvas);
        return Build.VERSION.SDK_INT >= 26 ? IconCompat.createWithAdaptiveBitmap(bitmap) : IconCompat.createWithBitmap(bitmap);
    }
}
