package com.newtube.mobile.ui.meetune;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Motion;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MEETUNE: the music side of MeeTube. Its own settings (prefs "meetune"), its worker, and the
 * music session the player reads: a song played from MeeTune carries a playlist id (the album's,
 * or YouTube Music's radio "RDAMVM" + id), and every video of that playlist - the radio's next
 * songs too - opens in the music player instead of the video one.
 */
public final class MeeTune {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Square art of the songs MeeTune showed, by video id; the player's cover. */
    private static final Map<String, String> ART = new HashMap<>();
    @Nullable
    private static String sPlaylistId;
    @Nullable
    private static String sVideoId;

    interface Result<T> {
        void done(@Nullable T value, @Nullable Exception error);
    }

    interface Task<T> {
        T run() throws Exception;
    }

    private MeeTune() {
    }

    /** MeeTube to MeeTune: MeeTube blurs and dims away, MeeTune comes in out of the blur. */
    public static void open(Activity from) {
        prefetch(from); // Home loads while MeeTube blurs away
        pausePlaying(false); // a MeeTube video doesn't play on into MeeTune
        blurAway(from, () -> {
            from.startActivity(new Intent(from, MeeTuneActivity.class));
            from.overridePendingTransition(R.anim.meetune_switch_enter, R.anim.mobile_stay);
        });
    }

    /** MeeTune back to MeeTube, the same way round: MeeTune blurs away, MeeTube comes back into focus. */
    static void leave(Activity meetune) {
        pausePlaying(true); // a MeeTune song doesn't play on into MeeTube
        blurAway(meetune, () -> {
            // Like issue #171's way back to Browse: the player MeeTune expanded sits between Browse and
            // MeeTune, and a bare finish() put the song's full player on screen over MeeTube.
            meetune.startActivity(new Intent(meetune, com.newtube.mobile.ui.browse.MobileBrowseActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            meetune.finish();
            meetune.overridePendingTransition(0, R.anim.mobile_fade_out);
        });
    }

    /** Pauses the minimized player when it plays the other side's: a MeeTune song ({@code music}) or a MeeTube video. Its card stays, to resume. */
    private static void pausePlaying(boolean music) {
        Video playing = com.newtube.mobile.ui.playback.MiniPlayerBridge.getVideo();
        androidx.media3.exoplayer.ExoPlayer player = com.newtube.mobile.ui.playback.MiniPlayerBridge.getPlayer();
        if (playing != null && player != null && isMusic(playing) == music) {
            player.pause();
        }
    }

    /** The screen being left blurs and dims to its ground, then {@code then}; when it shows again it frosts back in. */
    private static void blurAway(Activity from, Runnable then) {
        View content = from.findViewById(android.R.id.content);
        if (content.getTag(R.id.meetune_switching) != null) return; // a second tap mid-switch
        content.setTag(R.id.meetune_switching, true);
        float radius = 22 * content.getResources().getDisplayMetrics().density;
        ValueAnimator away = ValueAnimator.ofFloat(0f, 1f).setDuration(190);
        away.setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        away.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            content.setAlpha(1f - 0.75f * f);
            if (Build.VERSION.SDK_INT >= 31 && f > 0.01f) {
                content.setRenderEffect(RenderEffect.createBlurEffect(radius * f, radius * f, Shader.TileMode.CLAMP));
            }
        });
        away.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                then.run();
            }
        });
        // Coming back (MeeTube after MeeTune), the blurred screen comes into focus again.
        ((LifecycleOwner) from).getLifecycle().addObserver(new DefaultLifecycleObserver() {
            private boolean mLeft;

            @Override
            public void onStop(LifecycleOwner owner) {
                mLeft = true;
            }

            @Override
            public void onResume(LifecycleOwner owner) {
                if (!mLeft) return; // the catch-up call when added
                owner.getLifecycle().removeObserver(this);
                content.setTag(R.id.meetune_switching, null);
                Motion.frostIn(content);
            }
        });
        away.start();
    }

    // ----------------------------------------------------------------------------- Home, ready before it opens

    /** Home younger than this shows as it is; older shows at once too, a fresh one loading behind it. */
    private static final long HOME_FRESH_MS = 10 * 60_000L;
    @Nullable
    private static List<MeeTuneAdapter.Row> sHome;
    private static long sHomeAt;
    private static String sHomeAccount = "";
    private static boolean sHomeLoading;
    private static final List<Runnable> sHomeWaiting = new ArrayList<>();

    /** The account Home is for: the web sign-in's slot, or "" signed out. */
    static String account(Context context) {
        return com.newtube.mobile.ui.signin.WebSignInState.isSignedIn(context)
                ? String.valueOf(com.newtube.mobile.ui.signin.WebSignInState.selectedSlot(context)) : "";
    }

    /**
     * Starts loading Home unless it's fresh or already on its way: at the tap that opens MeeTune, so it
     * loads while MeeTube blurs away. Main thread.
     */
    public static void prefetch(Context context) {
        Context app = context.getApplicationContext();
        String account = account(app);
        boolean fresh = sHome != null && account.equals(sHomeAccount)
                && System.currentTimeMillis() - sHomeAt < HOME_FRESH_MS;
        if (sHomeLoading || fresh) return;
        sHomeLoading = true;
        WORKER.execute(() -> {
            List<MeeTuneAdapter.Row> rows = null;
            try {
                rows = MeeTuneAdapter.rows(home(app), false, true);
            } catch (Exception ignored) {
                // the page shows its retry
            }
            List<MeeTuneAdapter.Row> loaded = rows;
            MAIN.post(() -> {
                sHomeLoading = false;
                if (loaded != null) {
                    sHome = loaded;
                    sHomeAt = System.currentTimeMillis();
                    sHomeAccount = account;
                }
                List<Runnable> waiting = new ArrayList<>(sHomeWaiting);
                sHomeWaiting.clear();
                for (Runnable then : waiting) then.run();
            });
        });
    }

    /**
     * Home's rows for {@code then}: at once when MeeTube already has them for this account (an old
     * copy refreshes behind it, for next time), else when the load in flight lands (null: it failed).
     * {@code force}: a retry or an account switch, never the copy.
     */
    static void home(Activity owner, boolean force, java.util.function.Consumer<List<MeeTuneAdapter.Row>> then) {
        String account = account(owner);
        if (force) {
            sHome = null;
        } else if (sHome != null && account.equals(sHomeAccount)) {
            then.accept(sHome);
            prefetch(owner); // nothing while it's fresh
            return;
        }
        prefetch(owner);
        sHomeWaiting.add(() -> {
            if (owner.isFinishing() || owner.isDestroyed()) return;
            if (sHome != null && !account.equals(sHomeAccount)) {
                home(owner, true, then); // a load for the account before the switch landed
                return;
            }
            then.accept(sHome);
        });
    }

    /** YouTube Music's Home, then a "Find Your Mood" row from its moods page, both asked at once. */
    private static List<MusicApi.Shelf> home(Context context) throws Exception {
        java.util.concurrent.Future<List<MusicApi.Shelf>> moods =
                WORKER.submit(() -> MusicApi.browse(context, "FEmusic_moods_and_genres", null));
        List<MusicApi.Shelf> shelves = MusicApi.browse(context, "FEmusic_home", null);
        try {
            for (MusicApi.Shelf shelf : moods.get(8, java.util.concurrent.TimeUnit.SECONDS)) {
                if (shelf.allMoods()) {
                    shelf.title = context.getString(R.string.meetune_mood);
                    shelf.moreBrowseId = "FEmusic_moods_and_genres";
                    shelf.moreParams = null;
                    shelves.add(shelf);
                    break;
                }
            }
        } catch (Exception ignored) {
            // Home stands without its moods row.
        }
        return shelves;
    }

    static <T> void run(Activity owner, Task<T> task, Result<T> result) {
        WORKER.execute(() -> {
            T value = null;
            Exception error = null;
            try {
                value = task.run();
            } catch (Exception e) {
                error = e;
            }
            T finalValue = value;
            Exception finalError = error;
            MAIN.post(() -> {
                if (!owner.isFinishing() && !owner.isDestroyed()) result.done(finalValue, finalError);
            });
        });
    }

    static void play(Context context, MusicApi.Item item) {
        if (item.videoId == null) return;
        if (item.art != null) ART.put(item.videoId, item.art);
        Video video = video(item);
        sPlaylistId = video.playlistId;
        sVideoId = item.videoId;
        PlaybackPresenter.instance(context).openVideo(video);
    }

    /** A song as MeeTube's video: what plays it, and what its Add to playlist saves. */
    static Video video(MusicApi.Item item) {
        Video video = Video.from(item.videoId);
        video.title = item.title;
        video.author = artist(item.subtitle);
        video.cardImageUrl = item.art;
        video.playlistId = item.playlistId != null ? item.playlistId : "RDAMVM" + item.videoId;
        return video;
    }

    /** "Artist • Album • 3:21" -> "Artist". */
    static String artist(@Nullable String subtitle) {
        if (subtitle == null) return null;
        int dot = subtitle.indexOf(" • ");
        return dot > 0 ? subtitle.substring(0, dot) : subtitle;
    }

    /** A song picked from the music player's Up next stays in the music look. */
    public static void keep(Video video) {
        if (video != null) sVideoId = video.videoId;
    }

    /** True while {@code video} is a song MeeTune started (or its radio's next one). */
    public static boolean isMusic(@Nullable Video video) {
        if (video == null) return false;
        return (sVideoId != null && sVideoId.equals(video.videoId))
                || (sPlaylistId != null && sPlaylistId.equals(video.playlistId));
    }

    /** The player's cover: MeeTune's square art when it has it, else the video's own still. */
    public static String art(Video video) {
        String art = ART.get(video.videoId);
        return art != null ? art : "https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg";
    }

    @Nullable
    private static java.lang.ref.WeakReference<MeeTuneMiniPill> sPill;
    private static final android.graphics.Rect sPillArt = new android.graphics.Rect();
    private static final android.graphics.Rect sPillText = new android.graphics.Rect();

    @Nullable
    private static MeeTuneMiniPill pill() {
        MeeTuneMiniPill pill = sPill != null ? sPill.get() : null;
        // only when the music player minimizes into MeeTune (not into a MeeTube screen over it)
        return pill != null && com.newtube.mobile.ui.playback.MiniPlayerBridge.getMiniHost() instanceof MeeTuneActivity
                ? pill : null;
    }

    /** Where MeeTune's pill shows its cover (screen pixels) when the player minimizes into it, else null. */
    @Nullable
    public static android.graphics.Rect pillArt() {
        return pill() != null && !sPillArt.isEmpty() ? sPillArt : null;
    }

    /** Where the pill's title and artist sit (screen pixels); valid with {@link #pillArt()}. */
    public static android.graphics.Rect pillText() {
        return sPillText;
    }

    static void setPill(@Nullable MeeTuneMiniPill pill, @Nullable android.graphics.Rect art, @Nullable android.graphics.Rect text) {
        sPill = pill != null ? new java.lang.ref.WeakReference<>(pill) : null;
        if (art != null) sPillArt.set(art); else sPillArt.setEmpty();
        if (text != null) sPillText.set(text); else sPillText.setEmpty();
    }

    /**
     * The music player minimizing at {@code f} (0: back up or handed off, 1: landed). MeeTune's pill
     * comes up after the finger; {@code carried}: the player flies its own cover and name onto it,
     * so the pill's own stay hidden until it lands.
     */
    public static void flyToPill(float f, @Nullable Video video, boolean playing, boolean carried) {
        MeeTuneMiniPill pill = sPill != null ? sPill.get() : null;
        if (pill != null && (f <= 0f || pill() != null)) pill.fly(f, video, playing, carried);
    }

    // ----------------------------------------------------------------------------- settings

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("meetune", Context.MODE_PRIVATE);
    }

    /** Songs play as sound only (the video track off), as YouTube Music does. Always, since its switch went. */
    public static boolean audioOnly(Context context) {
        return true;
    }

    /** Songs open in MeeTune's music player. Always, since its switch went. */
    public static boolean musicPlayer(Context context) {
        return true;
    }

    /** The heart beside the name on MeeTube's You page. */
    public static boolean youButton(Context context) {
        return prefs(context).getBoolean("you_button", true);
    }

    public static void setYouButton(Context context, boolean on) {
        prefs(context).edit().putBoolean("you_button", on).apply();
    }

    /** "MeeTune" beside the heart on Home's brand pill; off, the heart alone. */
    static boolean brandName(Context context) {
        return prefs(context).getBoolean("brand_name", true);
    }

    static void setBrandName(Context context, boolean on) {
        prefs(context).edit().putBoolean("brand_name", on).apply();
    }
}
