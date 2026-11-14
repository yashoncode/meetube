package com.newtube.mobile.downloads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputLayout;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;
import com.newtube.mobile.ui.common.MetaSeparator;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.playback.SeekFilmstrip;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * "Download" tapped: resolve the formats (the same single-flight /player fetch the player uses,
 * so from the watch page it is instant), then offer the rungs as one app dialog - the same
 * bottom sheet every other picker in the app is - and hand the choice to the service.
 */
public final class DownloadPicker {
    private static final long SLOW_FETCH_NOTICE_MS = 700;
    /** A little over the action Snackbar's 4 s on screen. */
    private static final long REWORD_WINDOW_MS = 6_000;

    @Nullable private static Disposable sFetch;

    private DownloadPicker() {
    }

    public static void show(Context context, Video video) {
        if (context == null || video == null || video.videoId == null) {
            return;
        }
        if (video.isLive || video.isUpcoming) {
            MobileSnackbar.show(context, R.string.mobile_download_unavailable_live);
            return;
        }

        RxHelper.disposeActions(sFetch);

        Handler main = new Handler(Looper.getMainLooper());
        Runnable slowNotice = () -> MobileSnackbar.show(context, R.string.mobile_download_preparing);
        main.postDelayed(slowNotice, SLOW_FETCH_NOTICE_MS);

        sFetch = YouTubeMediaItemService.instance()
                .getFormatInfoObserve(video.videoId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(info -> {
                    main.removeCallbacks(slowNotice);
                    present(context, video, info);
                }, error -> {
                    main.removeCallbacks(slowNotice);
                    MobileSnackbar.show(context, R.string.mobile_download_unavailable);
                });
    }

    private static void present(Context context, Video video, @Nullable MediaItemFormatInfo info) {
        if (info == null || info.isUnplayable()) {
            String reason = info != null ? info.getPlayabilityReason() : null;
            MobileSnackbar.show(context, reason != null ? reason : context.getString(R.string.mobile_download_unavailable), null, null);
            return;
        }

        List<DownloadOption> options = DownloadOptions.build(info.getAdaptiveFormats(), info.getUrlFormats());
        if (options.isEmpty()) {
            MobileSnackbar.show(context, R.string.mobile_download_unavailable);
            return;
        }

        // The format info carries the canonical title/author; the card may only have a partial one.
        String title = video.getTitle() != null ? video.getTitle() : info.getTitle();
        String author = video.getAuthor() != null ? video.getAuthor() : info.getAuthor();
        // A card built from a download carries its local file:// thumbnail, which a new job cannot
        // fetch (the file goes with the deleted copy): only http(s) card images are used as is.
        String cardImage = video.getCardImageUrl();
        String thumb = cardImage != null && cardImage.startsWith("http") ? cardImage
                : "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";

        // MEETUBE(download quality): Settings > Downloads can set one, then a tap downloads at once.
        DownloadOption preset = AutoDownload.defaultQuality(context) == AutoDownload.ASK ? null
                : AutoDownload.pick(options, AutoDownload.defaultQuality(context));
        if (preset != null) {
            DownloadItem existing = DownloadRegistry.instance(context).findDone(video.videoId, preset.kind);
            start(context, video, title, author, thumb, preset, existing != null && (preset.isAudioOnly()
                    || (existing.qualityLabel != null && existing.qualityLabel.equals(preset.qualityLabel))));
            return;
        }

        showOptions(context, video, title, author, thumb, options, lengthMs(info), 0, 0);
    }

    /** The rungs; with {@code clipEndMs > clipStartMs}, for that part of the video only (issue #241). */
    private static void showOptions(Context context, Video video, String title, String author, String thumb,
                                    List<DownloadOption> options, long lengthMs, long clipStartMs, long clipEndMs) {
        boolean clip = clipEndMs > clipStartMs;
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        DownloadRegistry registry = DownloadRegistry.instance(context);

        for (DownloadOption option : options) {
            String label = option.isAudioOnly()
                    ? context.getString(R.string.mobile_download_audio_only)
                    : option.qualityLabel;
            String format = option.isAudioOnly() ? "M4A" : option.isWebm() ? "WEBM" : "MP4";
            // A part's MP4 fetches only its share; WebM (above 1080p) still downloads whole.
            long bytes = clip && !option.isWebm() ? partBytes(option.totalBytes, lengthMs, clipStartMs, clipEndMs)
                    : option.totalBytes;
            String size = DownloadOptions.formatBytes(bytes);
            String description = size.isEmpty() ? format : format + MetaSeparator.DOT + size;

            DownloadItem existing = clip ? null : registry.findDone(video.videoId, option.kind);
            boolean alreadyHere = existing != null && (option.isAudioOnly()
                    || (existing.qualityLabel != null && existing.qualityLabel.equals(option.qualityLabel)));
            if (alreadyHere) {
                description += MetaSeparator.DOT + context.getString(R.string.mobile_download_already);
            }

            dialog.appendSingleButton(UiOptionItem.from(label, description, optionItem -> {
                dialog.closeDialog();
                start(context, video, title, author, thumb, option, alreadyHere, clipStartMs, clipEndMs, bytes);
            }));
        }

        if (!clip && context instanceof Activity) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_part),
                    context.getString(R.string.mobile_download_part_description), optionItem -> {
                        dialog.closeDialog();
                        askPart(context, video, title, author, thumb, options, lengthMs);
                    }));
        }

        dialog.showDialog(clip
                ? context.getString(R.string.mobile_download_part_title, DownloadOptions.clipLabel(clipStartMs, clipEndMs))
                : context.getString(R.string.dialog_download));
    }

    private static long lengthMs(MediaItemFormatInfo info) {
        try {
            return Long.parseLong(info.getLengthSeconds()) * 1000;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Where the part starts and ends, then the rungs for it: the start and end frames from the
     * storyboard (the seek preview's), a two-handle slider, and the times to type for precision.
     */
    private static void askPart(Context context, Video video, String title, String author, String thumb,
                                List<DownloadOption> options, long lengthMs) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.MobileCastCodeDialog);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_mobile_download_part, null);
        TextInputLayout endLayout = content.findViewById(R.id.download_part_end_layout);
        EditText start = content.findViewById(R.id.download_part_start);
        EditText end = content.findViewById(R.id.download_part_end);
        RangeSlider slider = content.findViewById(R.id.download_part_slider);
        SeekFilmstrip startFrame = content.findViewById(R.id.download_part_start_frame);
        SeekFilmstrip endFrame = content.findViewById(R.id.download_part_end_frame);

        long lengthSec = lengthMs / 1000;
        boolean previews = lengthSec > 1;
        long startSec = previews ? Math.min(currentPositionMs(context, video) / 1000, lengthSec - 1) : 0;
        boolean[] syncing = {false};
        Runnable showFrames = () -> {
            long from = DownloadOptions.parseTime(start.getText());
            long to = DownloadOptions.parseTime(end.getText());
            if (from >= 0) {
                startFrame.update(from);
            }
            if (to > 0) {
                endFrame.update(Math.max(0, to - 1000)); // the last frame kept, not the one after
            }
        };

        if (previews) {
            for (SeekFilmstrip frame : new SeekFilmstrip[] {startFrame, endFrame}) {
                frame.setDimmed(false);
                frame.prepare(video.videoId);
            }
            slider.setValueTo(lengthSec);
            slider.setValues((float) startSec, (float) lengthSec);
            slider.addOnChangeListener((s, value, fromUser) -> {
                if (!fromUser) {
                    return;
                }
                List<Float> values = s.getValues();
                syncing[0] = true;
                start.setText(DownloadOptions.formatDuration(values.get(0).longValue() * 1000));
                end.setText(DownloadOptions.formatDuration(values.get(1).longValue() * 1000));
                syncing[0] = false;
                endLayout.setError(null);
                showFrames.run();
            });
        } else {
            content.findViewById(R.id.download_part_start_card).setVisibility(View.GONE);
            content.findViewById(R.id.download_part_end_card).setVisibility(View.GONE);
            slider.setVisibility(View.GONE);
        }
        start.setText(DownloadOptions.formatDuration(startSec * 1000));
        if (lengthMs > 0) {
            end.setText(DownloadOptions.formatDuration(lengthMs));
        }
        showFrames.run();

        // Typed times move the slider and the frames once they read as a time.
        TextWatcher typed = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence text, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable text) {
                if (syncing[0]) {
                    return;
                }
                endLayout.setError(null);
                long from = DownloadOptions.parseTime(start.getText());
                long to = DownloadOptions.parseTime(end.getText());
                if (previews && from >= 0 && to > from && to <= lengthMs) {
                    slider.setValues((float) (from / 1000), (float) (to / 1000));
                }
                showFrames.run();
            }
        };
        start.addTextChangedListener(typed);
        end.addTextChangedListener(typed);

        // Haptics.watch: frosted like every other alert (SheetGlass.frostAlert), and its taps click.
        AlertDialog alert = com.newtube.mobile.ui.common.Haptics.watch(builder
                .setTitle(R.string.mobile_download_part)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobile_download_part_next, null)
                .create());
        alert.setOnShowListener(d -> {
            View next = alert.getButton(AlertDialog.BUTTON_POSITIVE);
            next.setOnClickListener(v -> {
                long startMs = DownloadOptions.parseTime(start.getText());
                long endMs = DownloadOptions.parseTime(end.getText());
                if (lengthMs > 0 && endMs > lengthMs) {
                    endMs = lengthMs;
                }
                if (startMs < 0 || endMs <= startMs) {
                    endLayout.setError(context.getString(R.string.mobile_download_part_invalid));
                    return;
                }
                alert.dismiss();
                showOptions(context, video, title, author, thumb, options, lengthMs, startMs, endMs);
            });
            end.setOnEditorActionListener((v, actionId, event) -> next.performClick());
        });
        alert.show();
    }

    /** Where the player is in this video, when it is the one playing; else 0. */
    private static long currentPositionMs(Context context, Video video) {
        try {
            PlaybackPresenter playback = PlaybackPresenter.instance(context);
            Video playing = playback.getVideo();
            if (playing != null && video.videoId.equals(playing.videoId) && playback.getPlayer() != null) {
                return Math.max(0, playback.getPlayer().getPositionMs());
            }
        } catch (RuntimeException ignored) {
            // no player: from the start
        }
        return 0;
    }

    /** A part's share of the whole download's size; the whole size when the length is unknown. */
    private static long partBytes(long totalBytes, long lengthMs, long clipStartMs, long clipEndMs) {
        if (totalBytes <= 0 || lengthMs <= 0 || clipEndMs <= clipStartMs) {
            return totalBytes;
        }
        return totalBytes * Math.min(lengthMs, clipEndMs - clipStartMs) / lengthMs;
    }

    private static void start(Context context, Video video, String title, String author, String thumb,
                              DownloadOption option, boolean alreadyHere) {
        start(context, video, title, author, thumb, option, alreadyHere, 0, 0, option.totalBytes);
    }

    private static void start(Context context, Video video, String title, String author, String thumb,
                              DownloadOption option, boolean alreadyHere, long clipStartMs, long clipEndMs,
                              long bytes) {
        if (alreadyHere) {
            // NEWTUBE(snackbar): the same file is already on the device - say so rather
            // than queue a second copy under a "Download started".
            MobileSnackbar.show(context, context.getString(R.string.mobile_download_already_here),
                    context.getString(R.string.mobile_download_view), () -> openDownloads(context));
            return;
        }
        String size = DownloadOptions.formatBytes(bytes);
        DownloadItem item = DownloadItem.create(video.videoId, title != null ? title : video.videoId,
                author, thumb, option);
        item.clipStartMs = clipStartMs;
        item.clipEndMs = clipEndMs;
        MobileDownloadService.enqueue(context, item);
        // NEWTUBE(snackbar): confirm with the size, and a way to the Downloads tab.
        String started = size.isEmpty()
                ? context.getString(R.string.mobile_download_started)
                : context.getString(R.string.mobile_download_started_size, size);
        MobileSnackbar.show(context, started,
                context.getString(R.string.mobile_download_view), () -> openDownloads(context));
        rewordWhenDone(context, item, started, size);
    }

    /**
     * A small file can finish while its "Download started" message is still up (next to a pill that
     * already says "Downloaded"); reword the message when that happens. The listener lets go after
     * the item ends or after a few seconds, whichever comes first.
     */
    private static void rewordWhenDone(Context context, DownloadItem item, String started, String size) {
        DownloadRegistry registry = DownloadRegistry.instance(context);
        Handler main = new Handler(Looper.getMainLooper());
        DownloadRegistry.Listener[] holder = new DownloadRegistry.Listener[1];
        Runnable release = () -> registry.removeListener(holder[0]);
        holder[0] = () -> {
            if (item.isDone()) {
                MobileSnackbar.replaceText(started, size.isEmpty()
                        ? context.getString(R.string.mobile_download_done)
                        : context.getString(R.string.mobile_download_done_size, size));
                release.run();
            } else if (item.isFailed()) {
                release.run();
            }
        };
        registry.addListener(holder[0]);
        main.postDelayed(release, REWORD_WINDOW_MS);
    }

    /** The Downloads tab, as the download notification opens it. */
    private static void openDownloads(Context context) {
        context.startActivity(new Intent(context, MobileBrowseActivity.class)
                .setAction(MobileBrowseActivity.ACTION_OPEN_DOWNLOADS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
}
