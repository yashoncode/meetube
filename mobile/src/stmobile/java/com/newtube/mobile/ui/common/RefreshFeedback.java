package com.newtube.mobile.ui.common;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.view.View;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(refresh): a pull-to-refresh lets go with a light tick and a soft two-note pop
 * (res/raw/refresh_pop.wav). The pop stays quiet while the phone is on silent or vibrate, and
 * is off unless switched on in Settings > Customization (issue #120).
 */
public final class RefreshFeedback {
    private static final String PREFS = "meetube_app";
    private static final String KEY_SOUND = "refresh_sound";
    private static SoundPool sPool;
    /** raw resource to its loaded sound id. */
    private static final java.util.Map<Integer, Integer> sSounds = new java.util.HashMap<>();

    private RefreshFeedback() {
    }

    public static boolean isSoundOn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, false);
    }

    public static void setSoundOn(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOUND, on).apply();
    }

    public static void play(View view) {
        Haptics.click(view);
        Context app = view.getContext().getApplicationContext();
        if (isSoundOn(app)) {
            sound(app, R.raw.refresh_pop, 0.6f);
        }
    }

    /**
     * A short UI sound from res/raw, quiet while the phone is on silent or vibrate. MEETUBE(issue
     * #239): also the channel bell's chime.
     */
    public static void sound(Context context, int rawRes, float volume) {
        Context app = context.getApplicationContext();
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
            return;
        }
        if (sPool == null) {
            sPool = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
        }
        Integer loaded = sSounds.get(rawRes);
        if (loaded != null) {
            sPool.play(loaded, volume, volume, 1, 0, 1f);
            return;
        }
        // The first time plays it once loaded (a few ms); later ones play at once.
        sPool.setOnLoadCompleteListener((pool, id, status) -> {
            if (status == 0) {
                pool.play(id, volume, volume, 1, 0, 1f);
            }
        });
        sSounds.put(rawRes, sPool.load(app, rawRes, 1));
    }
}
