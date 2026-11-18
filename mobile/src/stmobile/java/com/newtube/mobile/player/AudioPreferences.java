package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

/** MEETUBE(issue #177): phone-only audio settings, separate from the shared player prefs. */
public final class AudioPreferences {
    public static final int CUSTOM = -1;
    private static final String PREFS = "meetube_audio";

    private AudioPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isStableVolumeEnabled(Context context) {
        return prefs(context).getBoolean("stable_volume", false);
    }

    public static void setStableVolumeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("stable_volume", enabled).apply();
    }

    /** MEETUBE(issue #256): a dubbed video starts on its original track (the default). */
    public static final int LANGUAGE_ORIGINAL = 0;
    /** ... on a dub in the app's language (MeeTube's Language setting, else Android's list). */
    public static final int LANGUAGE_APP = 1;
    /** ... on a dub in the first of Android's preferred languages it has. */
    public static final int LANGUAGE_SYSTEM = 2;

    public static int getAudioLanguageMode(Context context) {
        return prefs(context).getInt("audio_language", LANGUAGE_ORIGINAL);
    }

    public static void setAudioLanguageMode(Context context, int mode) {
        prefs(context).edit().putInt("audio_language", mode).apply();
    }

    /** MEETUBE(issue #256): YouTube's AI dubs ("dubbed-auto") count as a language's track. Off by default. */
    public static boolean isAutoDubAllowed(Context context) {
        return prefs(context).getBoolean("audio_auto_dubs", false);
    }

    public static void setAutoDubAllowed(Context context, boolean allowed) {
        prefs(context).edit().putBoolean("audio_auto_dubs", allowed).apply();
    }

    /**
     * MEETUBE(issue #256): the languages a dubbed video should start in, most wanted first, as
     * language tags ("pt-BR"); empty for {@link #LANGUAGE_ORIGINAL}.
     */
    public static java.util.List<String> preferredAudioLanguages(Context context) {
        java.util.List<String> languages = new java.util.ArrayList<>();
        int mode = getAudioLanguageMode(context);
        if (mode == LANGUAGE_ORIGINAL) {
            return languages;
        }
        if (mode == LANGUAGE_APP) {
            String app = com.liskovsoft.sharedutils.prefs.GlobalPreferences.instance(context).getPreferredLanguage();
            if (app != null && !app.isEmpty()) { // "ru", "pt_BR"; empty: the app follows the system
                languages.add(java.util.Locale.forLanguageTag(app.replace('_', '-')).toLanguageTag());
                return languages;
            }
        }
        android.os.LocaleList system = android.content.res.Resources.getSystem().getConfiguration().getLocales();
        for (int i = 0; i < system.size(); i++) {
            languages.add(system.get(i).toLanguageTag());
        }
        return languages;
    }

    /**
     * MEETUBE(dolby): prefer YouTube's Dolby track (E-AC-3 / AC-3, itags 328 and 380, 5.1, Atmos
     * when JOC) where a video has one. On by default; only takes effect where this phone has a
     * Dolby decoder ({@link #decodesDolby}). Applies to the next video opened.
     */
    public static boolean isDolbyEnabled(Context context) {
        return prefs(context).getBoolean("dolby_atmos", true);
    }

    public static void setDolbyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("dolby_atmos", enabled).apply();
    }

    /** ponytail: asked once per process; a decoder can't appear while the app runs. */
    private static Boolean sDecodesDolby;

    /**
     * Whether E-AC-3 can be decoded here: the platform's decoder (licensed, so many phones have
     * none), else the bundled FFmpeg one (5.1, without Atmos's height objects).
     */
    public static boolean decodesDolby() {
        if (sDecodesDolby == null) {
            boolean found = androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(
                    androidx.media3.common.MimeTypes.AUDIO_E_AC3);
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    found |= type.equalsIgnoreCase("audio/eac3") || type.equalsIgnoreCase("audio/eac3-joc");
                }
            }
            sDecodesDolby = found;
        }
        return sDecodesDolby;
    }

    public static boolean isEqualizerEnabled(Context context) {
        return prefs(context).getBoolean("equalizer_enabled", false);
    }

    public static void setEqualizerEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("equalizer_enabled", enabled).apply();
        AudioEffects.reapplyEqualizer();
    }

    public static int getPreset(Context context) {
        return prefs(context).getInt("equalizer_preset", CUSTOM);
    }

    public static String getPresetName(Context context) {
        return prefs(context).getString("equalizer_preset_name", "");
    }

    public static void setPreset(Context context, int preset, String name) {
        prefs(context).edit().putInt("equalizer_preset", preset)
                .putString("equalizer_preset_name", name).apply();
        AudioEffects.reapplyEqualizer();
    }

    public static short[] getCustomLevels(Context context, int bands) {
        short[] levels = new short[bands];
        String[] stored = prefs(context).getString("equalizer_levels", "").split(",");
        for (int i = 0; i < Math.min(bands, stored.length); i++) {
            try {
                levels[i] = Short.parseShort(stored[i]);
            } catch (NumberFormatException ignored) {
                // A missing band starts flat, including on devices with a different band count.
            }
        }
        return levels;
    }

    /** A band edit saves the whole displayed curve, including the preset it started from. */
    public static void setCustomLevels(Context context, short[] levels) {
        StringBuilder stored = new StringBuilder();
        for (short level : levels) {
            if (stored.length() > 0) {
                stored.append(',');
            }
            stored.append(level);
        }
        prefs(context).edit().putInt("equalizer_preset", CUSTOM)
                .putString("equalizer_preset_name", "")
                .putString("equalizer_levels", stored.toString()).apply();
        AudioEffects.reapplyEqualizer();
    }
}
