package com.newtube.mobile.ui.meetune;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileSnackbar;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A song's menu on MeeTube's frosted glass, in YouTube Music's order: Like, Dislike and Share across
 * the top, then its rows. Each action is MeeTube's own where it has one. MeeTune's lists open it from
 * a song's More; the music player opens it from its More, with its own rows added.
 */
public final class MeeTuneSongMenu {
    /** A player action with an optional detail shown below its label. */
    public static final class Row {
        final int icon;
        final int label;
        @Nullable final Supplier<String> detail;
        final Runnable run;

        public Row(int icon, int label, Runnable run) {
            this(icon, label, null, run);
        }

        public Row(int icon, int label, @Nullable Supplier<String> detail, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.detail = detail;
            this.run = run;
        }
    }

    private MeeTuneSongMenu() {
    }

    /**
     * The music player's More: the playing song's menu, {@code upgrade} (when there is one) over
     * Convert to video and {@code tail} at the bottom. Open album or artist calls {@code minimize}
     * first: MeeTune shows the page when it comes back.
     */
    public static void showForPlayer(Activity activity, Video video, Runnable minimize, @Nullable Row upgrade, Row... tail) {
        MusicApi.Item item = MeeTune.item(video);
        PopupWindow popup = build(activity, item, browseId -> {
            MeeTune.openOnReturn(browseId);
            minimize.run();
        }, upgrade, withEqualizer(tail, minimize));
        place(activity, popup, null, false);
    }

    /** The player's menu alone: MeeTune's Equalizer, opened in MeeTune with the song minimized. */
    private static Row[] withEqualizer(Row[] tail, Runnable minimize) {
        Row[] rows = new Row[tail.length + 1];
        rows[0] = new Row(R.drawable.ic_player_equalizer, R.string.meetune_eq, () -> {
            MeeTune.openOnReturn(MeeTune.EQUALIZER);
            minimize.run();
        });
        System.arraycopy(tail, 0, rows, 1, tail.length);
        return rows;
    }

    static void show(Activity activity, View anchor, MusicApi.Item item, Consumer<String> openBrowse, Row... tail) {
        place(activity, build(activity, item, openBrowse, null, tail), anchor, false);
    }

    private static PopupWindow build(Activity a, MusicApi.Item item, Consumer<String> openBrowse,
                                     @Nullable Row upgrade, Row... tail) {
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = glassPopup(a, content, 264, 28);
        LinearLayout top = new LinearLayout(a);
        top.setPadding(MeeTuneAdapter.dp(a, 8), MeeTuneAdapter.dp(a, 6), MeeTuneAdapter.dp(a, 8), MeeTuneAdapter.dp(a, 2));
        content.addView(top);
        button(top, R.drawable.ic_meetune_heart, R.string.meetune_like, () -> {
            popup.dismiss();
            rate(a, item, "like", R.string.meetune_liked_done);
        });
        button(top, R.drawable.ic_meetune_heart_broken, R.string.meetune_dislike, () -> {
            popup.dismiss();
            rate(a, item, "dislike", R.string.meetune_disliked);
        });
        button(top, R.drawable.ic_meetune_share, R.string.mobile_watch_share, () -> {
            popup.dismiss();
            a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Uri.parse(MusicApi.ORIGIN + "/watch?v=" + item.videoId).toString()), null));
        });
        content.addView(divider(a));
        if (upgrade != null) {
            row(content, upgrade.icon, a.getString(upgrade.label), upgrade.detail != null ? upgrade.detail.get() : null, () -> {
                popup.dismiss();
                upgrade.run.run();
            });
        }
        row(content, R.drawable.ic_meetune_video, a.getString(R.string.meetune_convert_video), null, () -> {
            popup.dismiss();
            MeeTune.watch(a, item);
        });
        content.addView(divider(a));
        row(content, R.drawable.ic_player_playlist_add, a.getString(R.string.dialog_add_to_playlist), null, () -> {
            popup.dismiss();
            MeeTuneLibrary.addSheet(a, item); // a phone playlist, no account needed; a YouTube one when signed in
        });
        content.addView(divider(a));
        row(content, R.drawable.ic_meetune_radio, a.getString(R.string.meetune_start_radio), null, () -> {
            popup.dismiss();
            MusicApi.Item radio = copy(item);
            radio.playlistId = null; // MeeTune.video makes it the song's own radio (RDAMVM)
            MeeTune.play(a, radio);
        });
        row(content, R.drawable.ic_meetune_play_next, a.getString(R.string.meetune_play_next), null, () -> {
            popup.dismiss();
            MeeTune.queue(a, item, true);
            MobileSnackbar.show(a, R.string.meetune_queued_next);
        });
        row(content, R.drawable.ic_player_queue, a.getString(R.string.meetune_add_queue), null, () -> {
            popup.dismiss();
            MeeTune.queue(a, item, false);
            MobileSnackbar.show(a, R.string.meetune_queued);
        });
        com.newtube.mobile.downloads.DownloadItem saved = MeeTune.songDownload(a, item.videoId);
        if (saved != null) { // already on the phone (or coming): its Delete instead of a second Download
            row(content, R.drawable.ic_meetune_delete, a.getString(R.string.mobile_download_delete), null, () -> {
                popup.dismiss();
                com.newtube.mobile.downloads.DownloadMenu.delete(a, saved);
                com.newtube.mobile.ui.playback.MeeTunePlayerSkin.forgetLyrics(a, saved.videoId);
                MobileSnackbar.show(a, R.string.meetune_download_deleted);
                if (a instanceof MeeTuneActivity) ((MeeTuneActivity) a).downloadsChanged();
            });
        } else row(content, R.drawable.ic_meetune_download, a.getString(R.string.meetune_download), null, () -> {
            popup.dismiss();
            com.newtube.mobile.downloads.DownloadPicker.show(a, MeeTune.video(item), MeeTune.downloadQuality(a));
            if (MeeTune.downloadLyrics(a)) {
                com.newtube.mobile.ui.playback.MeeTunePlayerSkin.keepLyrics(a, MeeTune.video(item));
            }
        });
        row(content, R.drawable.ic_meetune_broom, a.getString(R.string.meetune_uncache), null, () -> {
            popup.dismiss();
            String id = item.videoId;
            MeeTune.run(a, () -> MeeTune.uncache(a, id), (removed, error) ->
                    MobileSnackbar.show(a, removed != null && removed > 0
                            ? R.string.meetune_uncached : R.string.meetune_uncache_none));
        });
        content.addView(divider(a));
        if (item.albumId != null) {
            row(content, R.drawable.ic_meetune_album, a.getString(R.string.meetune_open_album), item.album, () -> {
                popup.dismiss();
                openBrowse.accept(item.albumId);
            });
        }
        if (item.artistId != null) {
            row(content, R.drawable.ic_meetune_artist, a.getString(R.string.meetune_open_artist), item.artist, () -> {
                popup.dismiss();
                openBrowse.accept(item.artistId);
            });
        }
        row(content, R.drawable.ic_meetune_info, a.getString(R.string.meetune_song_info), null, () -> {
            popup.dismiss();
            songInfo(a, item);
        });
        if (tail.length > 0) {
            content.addView(divider(a));
            for (Row extra : tail) {
                if (extra == null) continue;
                String label = a.getString(extra.label);
                String detail = extra.detail != null ? extra.detail.get() : null;
                TextView view = row(content, extra.icon, label, detail, () -> {
                    popup.dismiss();
                    extra.run.run();
                });
                if (extra.detail != null) refreshDetail(popup, view, label, extra.detail, detail);
            }
        }
        return popup;
    }

    private static void refreshDetail(PopupWindow popup, TextView view, String label,
                                      Supplier<String> detail, String previous) {
        view.postDelayed(() -> {
            if (!popup.isShowing()) return;
            String current = detail.get();
            if (!TextUtils.equals(current, previous)) setRowText(view, label, current);
            refreshDetail(popup, view, label, detail, current);
        }, 500);
    }

    @Nullable
    public static Row karaoke(Activity activity) {
        if (KaraokePack.abi(activity) == null) return null;
        return new Row(R.drawable.ic_mobile_mic, R.string.meetune_karaoke_name,
                () -> karaokeDetail(activity), () -> {
                    switch (KaraokePack.state(activity)) {
                        case NOT_DOWNLOADED:
                            downloadKaraoke(activity, true);
                            break;
                        case DOWNLOADING:
                            MobileSnackbar.show(activity, R.string.meetune_karaoke_still_downloading);
                            break;
                        case READY:
                            if (MeeTuneKaraoke.isOn()) {
                                MeeTuneKaraoke.setOn(false);
                                MobileSnackbar.show(activity, R.string.meetune_karaoke_off_snack);
                            } else {
                                enableKaraoke(activity);
                            }
                            break;
                    }
                });
    }

    private static String karaokeDetail(Context context) {
        String detail;
        switch (KaraokePack.state(context)) {
            case DOWNLOADING:
                detail = context.getString(R.string.meetune_downloading, KaraokePack.percent());
                break;
            case READY:
                if (MeeTuneKaraoke.isOn()) return context.getString(R.string.mobile_menu_on);
                detail = context.getString(R.string.mobile_menu_off);
                break;
            case NOT_DOWNLOADED:
                detail = context.getString(R.string.meetune_karaoke_download, karaokeMb(context));
                break;
            default:
                return "";
        }
        return context.getString(R.string.meetune_karaoke_beta_detail, detail);
    }

    /** The engine comes with the first download and goes with the model, so it is nearly always both. */
    static int karaokeMb(Context context) {
        return KaraokePack.runtimeReady(context) ? 28 : 39;
    }

    /** Asks before the one-time download. From the player, karaoke turns on when it finishes. */
    static void downloadKaraoke(Activity activity, boolean enableWhenReady) {
        glassConfirm(activity, activity.getString(R.string.meetune_karaoke_beta),
                activity.getString(R.string.meetune_karaoke_download_message, karaokeMb(activity)),
                android.R.string.cancel, R.string.meetune_download,
                () -> startKaraokeDownload(activity, enableWhenReady));
    }

    private static void startKaraokeDownload(Activity activity, boolean enableWhenReady) {
        Context app = activity.getApplicationContext();
        boolean started = KaraokePack.download(app, success -> {
            if (!success) MobileSnackbar.show(app, R.string.meetune_karaoke_download_failed);
            else if (enableWhenReady) enableKaraoke(activity);
        });
        MobileSnackbar.show(activity, started ? R.string.meetune_karaoke_downloading_snack
                : R.string.meetune_karaoke_still_downloading);
    }

    private static void enableKaraoke(Activity activity) {
        Context app = activity.getApplicationContext();
        MeeTune.run(activity, () -> KaraokePack.enable(app), (loaded, error) -> MobileSnackbar.show(activity,
                Boolean.TRUE.equals(loaded) ? R.string.meetune_karaoke_on_snack : R.string.meetune_karaoke_load_failed));
    }

    static void glassConfirm(Activity a, @Nullable String title, String message, int cancelLabel,
                             int actionLabel, Runnable action) {
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = glassPrompt(a, content, title, message, cancelLabel);
        TextView confirm = content.findViewById(android.R.id.button1);
        confirm.setText(actionLabel);
        confirm.setVisibility(View.VISIBLE);
        confirm.setOnClickListener(v -> dismissPrompt(popup, action));
        place(a, popup, null, true);
    }

    private static PopupWindow glassPrompt(Activity a, LinearLayout content, @Nullable String title,
                                           String message, int cancelLabel) {
        a.getLayoutInflater().inflate(R.layout.meetune_glass_prompt, content);
        PopupWindow popup = glassPopup(a, content, 320, 28);
        TextView heading = content.findViewById(android.R.id.title);
        heading.setText(title);
        heading.setVisibility(title == null ? View.GONE : View.VISIBLE);
        ((TextView) content.findViewById(android.R.id.message)).setText(message);
        TextView cancel = content.findViewById(android.R.id.button2);
        cancel.setText(cancelLabel);
        cancel.setOnClickListener(v -> dismissPrompt(popup, null));
        return popup;
    }

    private static void dismissPrompt(PopupWindow popup, @Nullable Runnable action) {
        // The glass remains visible during its closing animation. Accept only the first tap.
        if (!popup.isTouchable()) return;
        popup.setTouchable(false);
        popup.dismiss();
        if (action != null) action.run();
    }

    /** Lyrics offset: earlier or later by half a second, or back to none; the menu stays while it's tuned. */
    public static void lyricsOffset(Activity a, LongSupplier get, LongConsumer set) {
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = glassPopup(a, content, 280, 28);
        TextView value = new TextView(a);
        value.setTextSize(17);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(a.getColor(R.color.mobile_color_on_surface));
        int pad = MeeTuneAdapter.dp(a, 14);
        value.setPadding(pad, pad, pad, MeeTuneAdapter.dp(a, 4));
        Runnable show = () -> value.setText(a.getString(R.string.meetune_lyrics_offset_value,
                (get.getAsLong() > 0 ? "+" : "") + String.format(java.util.Locale.getDefault(), "%.1f", get.getAsLong() / 1000f)));
        show.run();
        content.addView(value);
        LinearLayout buttons = new LinearLayout(a);
        content.addView(buttons);
        button(buttons, R.drawable.ic_meetune_rewind, R.string.meetune_lyrics_later, () -> {
            set.accept(get.getAsLong() - 500);
            show.run();
        });
        button(buttons, R.drawable.ic_mobile_tune, R.string.meetune_lyrics_reset, () -> {
            set.accept(0);
            show.run();
        });
        button(buttons, R.drawable.ic_meetune_forward, R.string.meetune_lyrics_sooner, () -> {
            set.accept(get.getAsLong() + 500);
            show.run();
        });
        place(a, popup, null, true);
    }

    /**
     * The playing song's canvas: on or off for this song, and with a music-video loop, another part of
     * it or not this video. {@code loop}: a music-video loop is what shows now. {@code reload} asks for
     * the song's canvas again after a pick.
     */
    public static void canvas(Activity a, Video video, boolean loop, Runnable reload) {
        String id = video.videoId;
        if (id == null) return;
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = glassPopup(a, content, 264, 28);
        boolean off = MeeTuneCanvas.isOff(a, id);
        row(content, R.drawable.ic_meetune_video, a.getString(off ? R.string.meetune_canvas_song_on : R.string.meetune_canvas_song_off), null, () -> {
            popup.dismiss();
            MeeTuneCanvas.setOff(a, id, !off);
            reload.run();
        });
        if (!off && MeeTuneCanvas.videoLoops(a) && MeeTuneCanvas.hasVideo(id)) {
            boolean notVideo = MeeTuneCanvas.isNotVideo(a, id);
            if (loop && !notVideo) {
                row(content, R.drawable.ic_meetune_forward, a.getString(R.string.meetune_canvas_next_part), null, () -> {
                    popup.dismiss();
                    MeeTuneCanvas.nextPart(a, id);
                    reload.run();
                });
            }
            if (loop || notVideo) row(content, R.drawable.ic_meetune_video, a.getString(notVideo ? R.string.meetune_canvas_use_video
                    : R.string.meetune_canvas_not_video), null, () -> {
                popup.dismiss();
                MeeTuneCanvas.setNotVideo(a, id, !notVideo);
                reload.run();
            });
        }
        place(a, popup, null, true);
    }

    /** A frosted popup like MeeTube's: the page copied as it opens, blurred inside its corners; scrolls when taller than the screen. */
    static PopupWindow glassPopup(Activity a, LinearLayout content, int widthDp, int cornerDp) {
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = MeeTuneAdapter.dp(a, 6);
        content.setPadding(0, pad, 0, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        // More above or below: the items fade out at that edge instead of being cut by it.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(MeeTuneAdapter.dp(a, 40));
        scroll.addView(content);
        // The glass (its rim is an overlay) on a frame around the list: on the ScrollView itself the rim
        // scrolled with the items, and a tall menu showed its rounded edge cutting through them.
        android.widget.FrameLayout glass = new android.widget.FrameLayout(a);
        glass.addView(scroll);
        float corner = MeeTuneAdapter.dp(a, cornerDp);
        glass.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        glass.setClipToOutline(true);
        com.newtube.mobile.ui.common.SheetGlass.frost(glass, a.getWindow(), corner);
        PopupWindow popup = new PopupWindow(glass, MeeTuneAdapter.dp(a, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT, true) {
            private boolean mClosing;

            /** Shrinks back into where it popped from (place's pivot), then closes. */
            @Override
            public void dismiss() {
                if (mClosing) return;
                if (!isShowing() || !glass.isAttachedToWindow()) {
                    super.dismiss();
                    return;
                }
                mClosing = true;
                glass.animate().cancel();
                glass.animate().setUpdateListener(null).scaleX(0.15f).scaleY(0.15f).alpha(0f).setDuration(220)
                        .setInterpolator(new android.view.animation.AnticipateInterpolator(0.8f))
                        .withEndAction(super::dismiss).start();
            }
        };
        popup.setElevation(MeeTuneAdapter.dp(a, 12));
        Haptics.watch(popup);
        return popup;
    }

    /**
     * Shows {@code popup} whole on screen and pops it open. Beside {@code anchor} (below it, or above
     * when there is no room below, slid up to fit); with no anchor, at the screen's end edge or centre.
     * Only a menu taller than the space between the bars scrolls.
     */
    static void place(Activity a, PopupWindow popup, @Nullable View anchor, boolean center) {
        View decor = a.getWindow().getDecorView();
        View glass = popup.getContentView();
        int width = popup.getWidth();
        glass.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        android.view.WindowInsets insets = decor.getRootWindowInsets();
        int barTop = insets != null ? insets.getSystemWindowInsetTop() : 0;
        int barBottom = insets != null ? insets.getSystemWindowInsetBottom() : 0;
        int margin = MeeTuneAdapter.dp(a, 12);
        int screenW = decor.getWidth();
        int screenH = decor.getHeight();
        int minY = barTop + margin;
        int maxBottom = screenH - barBottom - margin;
        int height = Math.min(glass.getMeasuredHeight(), maxBottom - minY);
        popup.setHeight(height);
        int x;
        int y;
        float pivotX;
        float pivotY;
        if (anchor != null) {
            int[] at = new int[2];
            anchor.getLocationInWindow(at);
            x = Math.max(margin, Math.min(at[0] + anchor.getWidth() - width, screenW - width - margin));
            y = at[1] + anchor.getHeight();
            if (y + height > maxBottom) y = at[1] - height; // no room below: open upwards
            y = Math.max(minY, Math.min(y, maxBottom - height));
            pivotX = at[0] + anchor.getWidth() / 2f - x;
            pivotY = at[1] + anchor.getHeight() / 2f - y;
        } else {
            x = center ? (screenW - width) / 2 : screenW - width - MeeTuneAdapter.dp(a, 16);
            y = minY + (maxBottom - minY - height) / 2;
            pivotX = center ? width / 2f : width;
            pivotY = height / 2f;
        }
        popup.setAnimationStyle(0); // the pop below instead of the window's fade
        popup.showAtLocation(decor, Gravity.TOP | Gravity.START, x, y);
        glass.setPivotX(Math.max(0, Math.min(pivotX, width)));
        glass.setPivotY(Math.max(0, Math.min(pivotY, height)));
        // a bubble blown out of where it was asked for: from a dot, a soft overshoot, settles
        glass.setScaleX(0.15f);
        glass.setScaleY(0.15f);
        glass.setAlpha(0f);
        glass.animate().scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.0f))
                .setUpdateListener(v -> glass.setAlpha(Math.min(1f, v.getAnimatedFraction() * 3f))).start();
    }

    /** A song's thumbs up or down on YouTube Music: the account's likes, so signed in only. */
    private static void rate(Activity a, MusicApi.Item item, String action, int done) {
        if (!MusicApi.signedIn(a)) {
            MobileSnackbar.show(a, R.string.meetune_rate_sign_in);
            return;
        }
        MeeTune.run(a, () -> MusicApi.rate(a, item.videoId, action), (ok, error) ->
                MobileSnackbar.show(a, ok != null ? done : R.string.meetune_rate_failed));
    }

    private static MusicApi.Item copy(MusicApi.Item item) {
        MusicApi.Item copy = new MusicApi.Item();
        copy.title = item.title;
        copy.subtitle = item.subtitle;
        copy.art = item.art;
        copy.videoId = item.videoId;
        copy.playlistId = item.playlistId;
        copy.kind = item.kind;
        copy.artist = item.artist;
        return copy;
    }

    /** Song info: its title, artist, album and length on the same frosted glass. */
    private static void songInfo(Activity a, MusicApi.Item item) {
        LinearLayout content = new LinearLayout(a);
        PopupWindow popup = glassPopup(a, content, 300, 28);
        infoLine(content, R.string.meetune_info_title, item.title);
        infoLine(content, R.string.meetune_info_artist, item.artist != null ? item.artist : MeeTune.artist(item.subtitle));
        infoLine(content, R.string.meetune_info_album, item.album);
        infoLine(content, R.string.meetune_info_length, item.duration);
        place(a, popup, null, true);
    }

    private static void infoLine(ViewGroup parent, int label, @Nullable String value) {
        if (value == null || value.isEmpty()) return;
        android.content.Context c = parent.getContext();
        TextView line = new TextView(c);
        int h = MeeTuneAdapter.dp(c, 20);
        int v = MeeTuneAdapter.dp(c, 10);
        line.setPadding(h, v, h, v);
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(c.getString(label));
        text.setSpan(new android.text.style.ForegroundColorSpan(c.getColor(R.color.mobile_color_on_surface_secondary)),
                0, text.length(), 0);
        text.setSpan(new android.text.style.RelativeSizeSpan(0.85f), 0, text.length(), 0);
        text.append('\n').append(value);
        line.setText(text);
        line.setTextSize(16);
        line.setTextColor(c.getColor(R.color.mobile_color_on_surface));
        line.setTextIsSelectable(true);
        parent.addView(line, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** One of the menu's top three: its icon over its name. */
    private static void button(ViewGroup parent, int icon, int label, Runnable onClick) {
        android.content.Context c = parent.getContext();
        TextView button = new TextView(c);
        button.setText(label);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(c.getColor(R.color.mobile_color_on_surface));
        android.graphics.drawable.Drawable drawable = c.getDrawable(icon).mutate();
        drawable.setTint(c.getColor(R.color.mobile_color_on_surface));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, drawable, null, null);
        button.setCompoundDrawablePadding(MeeTuneAdapter.dp(c, 6));
        int v = MeeTuneAdapter.dp(c, 10);
        button.setPadding(0, v, 0, v);
        android.util.TypedValue ripple = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        button.setOnClickListener(x -> {
            Haptics.click(x);
            onClick.run();
        });
        parent.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** A menu row with its icon, and a second line under the name when there is one (the album, the artist). */
    static TextView row(ViewGroup parent, int icon, String label, @Nullable String detail, Runnable onClick) {
        android.content.Context c = parent.getContext();
        TextView row = new TextView(c);
        setRowText(row, label, detail);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setTextSize(16);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTextColor(c.getColor(R.color.mobile_color_on_surface));
        android.graphics.drawable.Drawable drawable = c.getDrawable(icon).mutate();
        drawable.setTint(c.getColor(R.color.mobile_color_on_surface));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null);
        row.setCompoundDrawablePadding(MeeTuneAdapter.dp(c, 20));
        int h = MeeTuneAdapter.dp(c, 22);
        int v = MeeTuneAdapter.dp(c, 10);
        row.setPadding(h, v, h, v);
        android.util.TypedValue ripple = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(x -> {
            Haptics.click(x);
            onClick.run();
        });
        parent.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return row;
    }

    private static void setRowText(TextView row, String label, @Nullable String detail) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(label);
        if (!TextUtils.isEmpty(detail)) {
            int start = text.length() + 1;
            text.append('\n').append(detail);
            text.setSpan(new android.text.style.ForegroundColorSpan(row.getContext().getColor(R.color.mobile_color_on_surface_secondary)),
                    start, text.length(), 0);
            text.setSpan(new android.text.style.RelativeSizeSpan(0.82f), start, text.length(), 0);
        }
        row.setText(text);
        row.setMaxLines(detail != null ? 2 : 1);
    }

    static View divider(Activity a) {
        View line = new View(a);
        line.setBackgroundColor(a.getColor(R.color.mobile_color_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, MeeTuneAdapter.dp(a, 1) / 2));
        params.setMargins(MeeTuneAdapter.dp(a, 20), MeeTuneAdapter.dp(a, 4), MeeTuneAdapter.dp(a, 20),
                MeeTuneAdapter.dp(a, 4));
        line.setLayoutParams(params);
        return line;
    }
}
