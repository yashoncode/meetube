package com.newtube.mobile.ui.common;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

import com.liskovsoft.smartyoutubetv2.tv.R;

/** MEETUBE(widget): Quick actions: Search, Voice search, Home, Shorts, Subscriptions and MeeTune. */
public class QuickActionsWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_actions);
        views.setOnClickPendingIntent(R.id.widget_search, AppShortcuts.open(context, AppShortcuts.SEARCH));
        views.setOnClickPendingIntent(R.id.widget_voice, AppShortcuts.open(context, AppShortcuts.VOICE));
        views.setOnClickPendingIntent(R.id.widget_home, AppShortcuts.open(context, AppShortcuts.HOME));
        views.setOnClickPendingIntent(R.id.widget_shorts, AppShortcuts.open(context, AppShortcuts.SHORTS));
        views.setOnClickPendingIntent(R.id.widget_subscriptions, AppShortcuts.open(context, AppShortcuts.SUBSCRIPTIONS));
        views.setOnClickPendingIntent(R.id.widget_meetune, AppShortcuts.open(context, AppShortcuts.MEETUNE));
        manager.updateAppWidget(ids, views);
    }
}
