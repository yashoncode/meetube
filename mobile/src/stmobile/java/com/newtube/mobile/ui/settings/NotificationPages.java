package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.newtube.mobile.notify.NewUploadsWorker;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.playback.BackgroundPlay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

/**
 * MEETUBE(issue #239): Settings > Notifications - new uploads on or off, how often they are checked,
 * which channels ring (the same bell as beside Subscribed on a channel page), and what keeps the
 * checks running with MeeTube closed.
 */
final class NotificationPages {
    private NotificationPages() {
    }

    static SettingsPages.Page notifications(Context context) {
        List<SettingsRow> rows = new ArrayList<>();

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_new_uploads),
                context.getString(R.string.mobile_settings_new_uploads_summary),
                () -> NewUploadsWorker.isEnabled(context), on -> NewUploadsWorker.setEnabled(context, on)));

        SettingsRow.Choice<Integer> every = SettingsRow.choice(context.getString(R.string.mobile_settings_notify_every));
        for (int minutes : NewUploadsWorker.EVERY_MINUTES) {
            every.option(everyLabel(context, minutes), minutes);
        }
        rows.add(every.bind(() -> NewUploadsWorker.everyMinutes(context),
                minutes -> NewUploadsWorker.setEveryMinutes(context, minutes)));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_notify_channels_master),
                context.getString(R.string.mobile_settings_notify_channels_master_summary),
                () -> NewUploadsWorker.isChannelsOn(context), on -> NewUploadsWorker.setChannelsOn(context, on)));

        rows.add(SettingsRow.action(0, context.getString(R.string.mobile_settings_notify_channels),
                () -> channelsSummary(context), NotificationPages::pickChannels)
                .enabledWhen(() -> NewUploadsWorker.isChannelsOn(context)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_notify_reliable)));
        rows.add(SettingsRow.action(0, context.getString(R.string.mobile_settings_notify_background),
                () -> context.getString(BackgroundPlay.isExempt(context)
                        ? R.string.mobile_settings_notify_background_on : R.string.mobile_settings_notify_background_off),
                page -> BackgroundPlay.request(page.requireActivity())));
        rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_notify_system),
                context.getString(R.string.mobile_settings_notify_system_summary), page -> {
                    Intent intent = Build.VERSION.SDK_INT >= 26
                            ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                            : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + context.getPackageName()));
                    try {
                        page.startActivity(intent);
                    } catch (RuntimeException ignored) {
                        // no such settings screen on this build
                    }
                }));
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_notify_note)));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_notifications), rows);
    }

    static CharSequence summary(Context context) {
        if (!NewUploadsWorker.isEnabled(context)) {
            return context.getString(R.string.mobile_settings_notify_off);
        }
        return context.getString(R.string.mobile_settings_notify_on, everyLabel(context, NewUploadsWorker.everyMinutes(context)));
    }

    private static String everyLabel(Context context, int minutes) {
        return minutes < 60
                ? context.getResources().getQuantityString(R.plurals.mobile_settings_notify_minutes, minutes, minutes)
                : context.getResources().getQuantityString(R.plurals.mobile_settings_notify_hours, minutes / 60, minutes / 60);
    }

    private static CharSequence channelsSummary(Context context) {
        int muted = NewUploadsWorker.muted(context).size();
        return muted == 0 ? context.getString(R.string.mobile_settings_notify_channels_all)
                : context.getResources().getQuantityString(R.plurals.mobile_settings_notify_channels_muted, muted, muted);
    }

    /** Every subscribed channel, ticked while its bell is on. */
    private static void pickChannels(SettingsPageFragment page) {
        Context context = page.requireContext();
        YouTubeServiceManager.instance().getContentService().getSubscribedChannelsByNameObserve()
                .observeOn(AndroidSchedulers.mainThread()).subscribe(group -> {
                    if (!page.isAdded()) {
                        return;
                    }
                    Map<String, String> offered = channels(group);
                    if (offered.isEmpty()) {
                        MobileSnackbar.show(context, R.string.mobile_settings_notify_no_channels);
                        return;
                    }
                    Map<String, String> muted = NewUploadsWorker.muted(context);
                    // MEETUBE(issue #333): row 0 is "Select all", ticked while every channel is.
                    List<String> ids = new ArrayList<>(offered.keySet());
                    ids.add(0, null);
                    List<CharSequence> names = new ArrayList<>(offered.values());
                    names.add(0, context.getString(R.string.mobile_settings_notify_select_all));
                    CharSequence[] titles = names.toArray(new CharSequence[0]);
                    boolean[] checked = new boolean[ids.size()];
                    boolean all = true;
                    for (int i = 1; i < ids.size(); i++) {
                        checked[i] = !muted.containsKey(ids.get(i));
                        all &= checked[i];
                    }
                    checked[0] = all;
                    page.showDialog(new MaterialAlertDialogBuilder(page.requireActivity(), R.style.MobileAlertDialog_GlassCheck)
                            .setTitle(R.string.mobile_settings_notify_channels)
                            .setMultiChoiceItems(titles, checked, (dialog, which, on) -> {
                                android.widget.ListView list = ((androidx.appcompat.app.AlertDialog) dialog).getListView();
                                if (which == 0) {
                                    for (int i = 1; i < checked.length; i++) {
                                        checked[i] = on;
                                        list.setItemChecked(i, on);
                                    }
                                }
                                checked[which] = on;
                                boolean every = true;
                                for (int i = 1; i < checked.length; i++) {
                                    every &= checked[i];
                                }
                                checked[0] = every;
                                list.setItemChecked(0, every);
                            })
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                for (int i = 1; i < ids.size(); i++) {
                                    if (checked[i]) {
                                        muted.remove(ids.get(i));
                                    } else {
                                        muted.put(ids.get(i), titles[i].toString());
                                    }
                                }
                                NewUploadsWorker.setMuted(context, muted);
                                page.rebuild();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show());
                }, error -> {
                    if (page.isAdded()) {
                        MobileSnackbar.show(context, R.string.mobile_settings_notify_no_channels);
                    }
                });
    }

    private static Map<String, String> channels(MediaGroup group) {
        Map<String, String> offered = new LinkedHashMap<>();
        List<MediaItem> items = group != null ? group.getMediaItems() : null;
        for (int i = 0; items != null && i < items.size(); i++) {
            MediaItem item = items.get(i);
            if (item.getChannelId() != null && item.getTitle() != null) {
                offered.put(item.getChannelId(), item.getTitle());
            }
        }
        return offered;
    }
}
