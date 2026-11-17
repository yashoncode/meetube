package com.newtube.mobile.ui.meetune;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * MEETUNE: Bit-perfect USB DAC output. On Android 14+, with a USB DAC plugged in, a song's PCM goes
 * to the DAC untouched and at its own sample rate: just before the song's AudioTrack opens, Android
 * is asked for the DAC's BIT_PERFECT mixer in exactly that format (AudioManager
 * setPreferredMixerAttributes), so the system mixer neither resamples nor mixes it. MeeTube videos,
 * a DAC that doesn't offer that format, and the switch off all clear the request (the shared mixer
 * as usual). The equalizer, Spatial sound and a volume other than 100% still shape the samples.
 */
public final class MeeTuneBitPerfect {
    private static final String TAG = "MeeTuneBitPerfect";
    /** The usage the request is keyed by: every player here plays USAGE_MEDIA. */
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();
    @Nullable
    private static AudioDeviceInfo sRequested;

    private MeeTuneBitPerfect() {
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 34;
    }

    public static boolean isEnabled(Context context) {
        return supported() && MeeTune.prefs(context).getBoolean("bit_perfect", false);
    }

    static void setEnabled(Context context, boolean enabled) {
        MeeTune.prefs(context).edit().putBoolean("bit_perfect", enabled).apply();
        if (!enabled && supported()) {
            clear(context);
        }
    }

    /** The player's sink, asking for the DAC's bit-perfect mixer each time it opens for a song. */
    @OptIn(markerClass = UnstableApi.class)
    public static AudioSink wrap(Context context, AudioSink sink) {
        Context app = context.getApplicationContext();
        return new ForwardingAudioSink(sink) {
            @Override
            public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
                    throws ConfigurationException {
                if (supported()) {
                    try {
                        request(app, inputFormat);
                    } catch (RuntimeException e) { // never let this stop a song playing
                        Log.w(TAG, "request failed: " + e);
                    }
                }
                super.configure(inputFormat, specifiedBufferSize, outputChannels);
            }
        };
    }

    @OptIn(markerClass = UnstableApi.class)
    @RequiresApi(34)
    private static synchronized void request(Context context, Format format) {
        AudioDeviceInfo dac = isEnabled(context) && MeeTuneEq.isPlaying()
                && MimeTypes.AUDIO_RAW.equals(format.sampleMimeType) ? usbDac(context) : null;
        AudioManager audio = context.getSystemService(AudioManager.class);
        AudioMixerAttributes match = null;
        if (dac != null) {
            int mask = format.channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO
                    : format.channelCount == 2 ? AudioFormat.CHANNEL_OUT_STEREO : 0;
            // media3's PCM encodings carry android.media.AudioFormat's values.
            for (AudioMixerAttributes mixer : audio.getSupportedMixerAttributes(dac)) {
                AudioFormat f = mixer.getFormat();
                if (mixer.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                        && f.getEncoding() == format.pcmEncoding && f.getSampleRate() == format.sampleRate
                        && f.getChannelMask() == mask) {
                    match = mixer;
                    break;
                }
            }
            if (match == null) {
                Log.i(TAG, "DAC " + dac.getProductName() + " offers no bit-perfect " + format.pcmEncoding
                        + "/" + format.sampleRate + "Hz/" + format.channelCount + "ch");
            }
        }
        if (match == null) {
            clear(context);
            return;
        }
        if (audio.setPreferredMixerAttributes(MEDIA, dac, match)) {
            sRequested = dac;
            Log.i(TAG, "bit-perfect " + format.sampleRate + "Hz enc=" + format.pcmEncoding + " -> " + dac.getProductName());
        } else {
            Log.w(TAG, "bit-perfect refused by the platform");
            clear(context);
        }
    }

    @RequiresApi(34)
    private static synchronized void clear(Context context) {
        if (sRequested != null) {
            try { // the DAC may be gone already
                context.getSystemService(AudioManager.class).clearPreferredMixerAttributes(MEDIA, sRequested);
            } catch (RuntimeException e) {
                Log.w(TAG, "clear failed: " + e);
            }
            sRequested = null;
        }
    }

    /** The plugged-in USB DAC, if any. */
    @Nullable
    private static AudioDeviceInfo usbDac(Context context) {
        for (AudioDeviceInfo device : context.getSystemService(AudioManager.class)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return device;
            }
        }
        return null;
    }
}
