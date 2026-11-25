package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.content.Context;

import com.liskovsoft.sharedutils.locale.LocaleContextWrapper;
import com.liskovsoft.sharedutils.locale.LocaleUpdater;

/** Gives translucent file-picker hosts the app language for their result and error messages. */
public abstract class LocalizedPickerActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleContextWrapper.wrap(base, LocaleUpdater.getSavedLocale(base)));
    }
}
