package com.newtube.mobile.downloads;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** MEETUBE(playlist page): one quality choice, then the same format selection and queue as DownloadPicker. */
public final class PlaylistDownloads {
    public interface QualityCallback {
        void onQuality(int quality);
    }

    public interface Callback {
        void onQueued(int queued, int unavailable);
    }

    private PlaylistDownloads() {
    }

    public static void chooseQuality(Context context, QualityCallback callback) {
        int preset = AutoDownload.defaultQuality(context);
        if (preset != AutoDownload.ASK) {
            callback.onQuality(preset);
            return;
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        addQuality(dialog, context.getString(R.string.mobile_playlist_download_best), AutoDownload.BEST, callback);
        for (int height : AutoDownload.HEIGHTS) {
            addQuality(dialog, height + "p", height, callback);
        }
        addQuality(dialog, context.getString(R.string.mobile_download_audio_only), AutoDownload.AUDIO, callback);
        dialog.showDialog(context.getString(R.string.mobile_playlist_download_quality));
    }

    private static void addQuality(AppDialogPresenter dialog, String label, int quality, QualityCallback callback) {
        dialog.appendSingleButton(UiOptionItem.from(label, option -> {
            dialog.closeDialog();
            callback.onQuality(quality);
        }));
    }

    public static Disposable enqueue(Context context, List<Video> videos, int quality, Callback callback) {
        Context app = context.getApplicationContext();
        return Observable.<int[]>create(emitter -> {
            DownloadRegistry registry = DownloadRegistry.instance(app);
            Set<String> seen = new HashSet<>();
            int queued = 0;
            int unavailable = 0;
            for (Video video : videos) {
                if (emitter.isDisposed()) {
                    return;
                }
                if (!VideoDownloads.canDownload(video) || !seen.add(video.videoId) || alreadyHere(registry, video.videoId)) {
                    continue;
                }
                try {
                    MediaItemFormatInfo info = YouTubeMediaItemService.instance().getFormatInfo(video.videoId);
                    if (info == null || info.isUnplayable()) {
                        unavailable++;
                        continue;
                    }
                    DownloadOption option = AutoDownload.pick(
                            DownloadOptions.build(info.getAdaptiveFormats(), info.getUrlFormats()), quality);
                    if (option == null) {
                        unavailable++;
                        continue;
                    }
                    if (emitter.isDisposed()) {
                        return;
                    }
                    // MEETUBE(playlist page): a single download may have been queued while formats loaded.
                    String title = video.getTitle() != null ? video.getTitle() : info.getTitle();
                    String author = video.getAuthor() != null ? video.getAuthor() : info.getAuthor();
                    String thumb = video.getCardImageUrl();
                    if (thumb == null || !thumb.startsWith("http")) {
                        thumb = "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";
                    }
                    synchronized (registry) {
                        if (!alreadyHere(registry, video.videoId)) {
                            MobileDownloadService.enqueue(app, DownloadItem.create(video.videoId,
                                    title != null ? title : video.videoId, author, thumb, option));
                            queued++;
                        }
                    }
                } catch (Exception error) {
                    unavailable++;
                }
            }
            emitter.onNext(new int[] {queued, unavailable});
            emitter.onComplete();
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> callback.onQueued(result[0], result[1]), error -> callback.onQueued(0, videos.size()));
    }

    private static boolean alreadyHere(DownloadRegistry registry, String videoId) {
        return registry.findActive(videoId) != null
                || registry.findDone(videoId, DownloadOption.KIND_VIDEO) != null
                || registry.findDone(videoId, DownloadOption.KIND_AUDIO) != null;
    }
}
