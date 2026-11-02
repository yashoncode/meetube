package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.widget.EdgeEffect;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** MEETUBE(bounce): a pulled list follows with resistance, never past its limit, and lets go cleanly. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class BounceOverscrollTest {
    @Test
    public void aPulledListFollowsWithResistanceAndComesBack() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView list = new RecyclerView(activity);
        list.layout(0, 0, 100, 1000);
        EdgeEffect top = new BounceOverscroll().createEdgeEffect(list, RecyclerView.EdgeEffectFactory.DIRECTION_TOP);
        EdgeEffect bottom = new BounceOverscroll().createEdgeEffect(list, RecyclerView.EdgeEffectFactory.DIRECTION_BOTTOM);

        top.onPullDistance(0.1f, 0.5f); // 100px of finger: 50px at first
        assertEquals(50f, list.getTranslationY(), 1e-3f);
        top.onPullDistance(0.1f, 0.5f); // further is harder
        assertTrue(list.getTranslationY() < 100f);
        for (int i = 0; i < 100; i++) {
            top.onPullDistance(0.5f, 0.5f);
        }
        assertTrue(list.getTranslationY() <= 200f); // a fifth of the list at most
        assertTrue(list.getScaleY() > 1f);

        float back = top.onPullDistance(-10f, 0.5f); // pushed all the way back: only what it had
        assertEquals(0f, list.getTranslationY(), 0f);
        assertEquals(1f, list.getScaleY(), 0f);
        assertTrue(back > -10f && back < 0f);
        assertTrue(top.isFinished());

        bottom.onPullDistance(0.1f, 0.5f); // the end edge moves the other way
        assertEquals(-50f, list.getTranslationY(), 1e-3f);
    }
}
