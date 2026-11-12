package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.fragment.app.Fragment;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

public class BrowseActivity extends LeanbackActivity {
    /**
     * MEETUBE: a mouse wheel moves focus like the remote's Up/Down. Scrolled directly, the rows slid under the top bar
     * while it stayed up (it follows the focused row, which the wheel never changed).
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (scroll != 0) {
                int code = scroll > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static final String TAG = BrowseActivity.class.getSimpleName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.fragment_main);
        } catch (NoClassDefFoundError e) {
            // Failed resolution of: Landroidx/lifecycle/ViewTreeLifecycleOwner;
            MessageHelpers.showMessage(this, e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        // MEETUBE: the title-bar Settings page returns to the previous browse section.
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_frame);
        if (fragment instanceof BrowseFragment && ((BrowseFragment) fragment).returnFromSettings(true)) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void initTheme() {
        int browseThemeResId = MainUIData.instance(this).getColorScheme().browseThemeResId;
        if (browseThemeResId > 0) {
            setTheme(browseThemeResId);
        }
    }
}
