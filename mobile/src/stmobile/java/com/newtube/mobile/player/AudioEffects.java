package com.newtube.mobile.player;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.function.Consumer;

/** MEETUBE(issue #177): optional effects on the main player's session; failures never stop audio. */
public final class AudioEffects {
    private static final String TAG = AudioEffects.class.getSimpleName();
    @Nullable private static AudioEffects sCurrent;

    private final Context mContext;
    private final Consumer<Float> mSetPlayerVolume;
    @Nullable private Equalizer mEqualizer;
    @Nullable private LoudnessEnhancer mEnhancer;
    private int mSessionId;
    private float mVolume = 1f;

    AudioEffects(Context context, Consumer<Float> setPlayerVolume) {
        mContext = context.getApplicationContext();
        mSetPlayerVolume = setPlayerVolume;
        sCurrent = this;
    }

    void attach(int sessionId) {
        if (mSessionId == sessionId) {
            return;
        }
        releaseEffects();
        announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, mSessionId);
        mSessionId = sessionId;
        if (sessionId <= 0) {
            return; // Never attach effects to the deprecated global output session (0).
        }
        announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, sessionId);
        try {
            mEqualizer = new Equalizer(0, sessionId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Equalizer unavailable: " + e);
        }
        try {
            mEnhancer = new LoudnessEnhancer(sessionId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Volume boost unavailable: " + e);
        }
        applyEqualizer(mContext, mEqualizer, AudioPreferences.isEqualizerEnabled(mContext));
        setVolume(mVolume);
    }

    void setVolume(float volume) {
        mVolume = volume;
        if (mEnhancer != null) {
            try {
                // MEETUBE(issue #272): +10 dB per doubling (200% = +10 dB), what the ear hears as twice as loud;
                // the amplitude's +6 dB was hardly heard once the effect's limiter met loud music.
                mEnhancer.setTargetGain(boostMb(volume));
                mEnhancer.setEnabled(volume > 1f);
            } catch (RuntimeException e) {
                Log.w(TAG, "Can't apply volume boost: " + e);
            }
        }
    }

    private static int boostMb(float volume) {
        return volume > 1f ? (int) Math.round(1000 * Math.log(volume) / Math.log(2)) : 0;
    }

    /**
     * MEETUNE(crossfade): the main player's equalizer and volume boost on the crossfade tail's own
     * session (see {@link #tailSessionId}), released with it. Without them the outgoing song lost
     * the curve at the handoff, a jump in loudness and tone right where the blend starts.
     */
    static void attachTail(Context context, androidx.media3.exoplayer.ExoPlayer tail) {
        AudioEffects current = sCurrent;
        int sessionId = tail.getAudioSessionId();
        if (current == null || sessionId <= 0) {
            return;
        }
        Equalizer equalizer = null;
        LoudnessEnhancer enhancer = null;
        if (AudioPreferences.isEqualizerEnabled(context)) {
            try {
                equalizer = new Equalizer(0, sessionId);
                applyEqualizer(context, equalizer, true);
            } catch (RuntimeException e) {
                Log.w(TAG, "Tail equalizer unavailable: " + e);
            }
        }
        if (current.mVolume > 1f) {
            try {
                enhancer = new LoudnessEnhancer(sessionId);
                enhancer.setTargetGain(boostMb(current.mVolume));
                enhancer.setEnabled(true);
            } catch (RuntimeException e) {
                Log.w(TAG, "Tail volume boost unavailable: " + e);
            }
        }
        Equalizer eq = equalizer;
        LoudnessEnhancer boost = enhancer;
        tail.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override
            public void onPlayerReleased(@androidx.annotation.NonNull EventTime eventTime) {
                releaseEqualizer(eq);
                if (boost != null) {
                    try {
                        boost.release();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Can't release tail volume boost: " + e);
                    }
                }
            }
        });
    }

    /** The volume row also updates media3's base volume, including values below 100%. */
    public static void applyPlayerVolume(float volume) {
        AudioEffects current = sCurrent;
        if (current != null) {
            current.mSetPlayerVolume.accept(volume);
        }
    }

    public static void reapplyEqualizer() {
        AudioEffects current = sCurrent;
        if (current != null) {
            applyEqualizer(current.mContext, current.mEqualizer,
                    AudioPreferences.isEqualizerEnabled(current.mContext));
        }
    }

    private static void applyEqualizer(Context context, @Nullable Equalizer equalizer, boolean enabled) {
        if (equalizer == null) {
            return;
        }
        try {
            // MEETUBE(eq switch): the effect stays on and Off is a flat curve. setEnabled() rebuilds
            // the phone's effect chain, which dropped the sound for about a second on every switch.
            int preset = AudioPreferences.getPreset(context);
            if (!enabled) {
                for (short band = 0; band < equalizer.getNumberOfBands(); band++) {
                    equalizer.setBandLevel(band, (short) 0);
                }
            } else if (preset >= 0 && preset < equalizer.getNumberOfPresets()) {
                equalizer.usePreset((short) preset);
            } else {
                short[] range = equalizer.getBandLevelRange();
                short[] levels = AudioPreferences.getCustomLevels(context, equalizer.getNumberOfBands());
                for (short band = 0; band < levels.length; band++) {
                    equalizer.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], levels[band])));
                }
            }
            if (!equalizer.getEnabled()) {
                equalizer.setEnabled(true); // once, at attach, before the sound starts
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't apply equalizer: " + e);
        }
    }

    /** Device bands/presets and the configured curve, even when the equalizer is switched off. */
    public static final class Bands {
        public final int minLevel;
        public final int maxLevel;
        public final int[] frequencies;
        public final short[] levels;
        public final String[] presets;

        private Bands(short[] range, int[] frequencies, short[] levels, String[] presets) {
            minLevel = range[0];
            maxLevel = range[1];
            this.frequencies = frequencies;
            this.levels = levels;
            this.presets = presets;
        }
    }

    @Nullable
    public static Bands bands(Context context) {
        AudioEffects current = sCurrent;
        // Switched off, the live equalizer is flat: the configured curve comes from the probe below.
        if (current != null && current.mEqualizer != null && AudioPreferences.isEqualizerEnabled(context)) {
            return readBands(current.mEqualizer);
        }
        // Settings can be opened before a video: query a private, silent session and release it.
        Equalizer probe = null;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int sessionId = manager != null ? manager.generateAudioSessionId() : 0;
            if (sessionId <= 0) {
                return null;
            }
            probe = new Equalizer(0, sessionId);
            applyEqualizer(context, probe, true); // a private silent session: the curve, never heard
            return readBands(probe);
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't query equalizer: " + e);
            return null;
        } finally {
            releaseEqualizer(probe);
        }
    }

    @Nullable
    private static Bands readBands(Equalizer equalizer) {
        try {
            short[] range = equalizer.getBandLevelRange();
            int[] frequencies = new int[equalizer.getNumberOfBands()];
            if (frequencies.length == 0 || range.length < 2 || range[0] >= range[1]) {
                return null;
            }
            short[] levels = new short[frequencies.length];
            for (short band = 0; band < frequencies.length; band++) {
                frequencies[band] = equalizer.getCenterFreq(band);
                levels[band] = equalizer.getBandLevel(band);
            }
            String[] presets = new String[equalizer.getNumberOfPresets()];
            for (short preset = 0; preset < presets.length; preset++) {
                presets[preset] = equalizer.getPresetName(preset);
            }
            return new Bands(range, frequencies, levels, presets);
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't read equalizer bands: " + e);
            return null;
        }
    }

    void release() {
        if (sCurrent == this) {
            sCurrent = null;
        }
        releaseEffects();
        announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, mSessionId);
        mSessionId = 0;
    }

    /**
     * MEETUBE(dolby-session): tells the phone's own effect engines (Dolby Atmos / Dolby Audio on the
     * phones that ship it, Wavelet, OEM sound modes) about the main player's session, so the system's
     * Dolby switch shapes MeeTube too, like it does Spotify and YT Music. Without it they never attach.
     */
    private void announce(String action, int sessionId) {
        if (sessionId <= 0) {
            return;
        }
        try {
            mContext.sendBroadcast(new Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mContext.getPackageName())
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC));
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't announce audio session: " + e);
        }
    }

    /**
     * The session the crossfade's tail (the outgoing song's ending) plays on: the main one, so the
     * phone's Dolby keeps shaping it through the blend; 0 (its own) while the equalizer or the volume
     * boost is working, as two players on one session make the platform rebuild that chain as the
     * tail comes and goes (Prism measured it with its equalizer).
     */
    static int tailSessionId(Context context) {
        AudioEffects current = sCurrent;
        if (current == null || AudioPreferences.isEqualizerEnabled(context) || current.mVolume > 1f) {
            return 0;
        }
        return current.mSessionId;
    }

    private void releaseEffects() {
        releaseEqualizer(mEqualizer);
        mEqualizer = null;
        if (mEnhancer != null) {
            try {
                mEnhancer.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Can't release volume boost: " + e);
            }
            mEnhancer = null;
        }
    }

    private static void releaseEqualizer(@Nullable Equalizer equalizer) {
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Can't release equalizer: " + e);
            }
        }
    }
}
