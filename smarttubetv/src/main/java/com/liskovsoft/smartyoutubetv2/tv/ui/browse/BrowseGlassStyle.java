package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.RowHeaderPresenter;
import androidx.leanback.widget.RowPresenter;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.CustomListRowPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.complexcardview.ComplexImageCardView;

/** Opt-in decoration for descendants of the browse fragment, never activity-wide styling. */
public final class BrowseGlassStyle {
    private BrowseGlassStyle() {
    }

    public static boolean isBrowse(Fragment fragment) {
        for (Fragment parent = fragment.getParentFragment(); parent != null; parent = parent.getParentFragment()) {
            if (parent instanceof BrowseFragment) {
                return true;
            }
        }
        return false;
    }

    public static void styleCard(ComplexImageCardView card) {
        card.setBackground(null);
        View info = card.findViewById(R.id.info_field);
        if (info != null) {
            // Propagate actual focus without replacing Leanback's focus listener or zoom animation.
            info.setDuplicateParentStateEnabled(true);
            info.setBackgroundResource(R.drawable.browse_glass_caption);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            ViewOutlineProvider outline = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline result) {
                    result.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            12 * view.getResources().getDisplayMetrics().density);
                }
            };
            card.setOutlineProvider(outline);
            card.setClipToOutline(true);
            View image = card.findViewById(R.id.main_image_wrapper);
            if (image != null) {
                image.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline result) {
                        float r = 12 * view.getResources().getDisplayMetrics().density;
                        // bottom edge pushed past the view: square bottom corners, flush with the caption
                        result.setRoundRect(0, 0, view.getWidth(), view.getHeight() + Math.round(r), r);
                    }
                });
                image.setClipToOutline(true);
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            card.setForeground(card.getContext().getDrawable(R.drawable.browse_glass_card_ring));
        }
        View badge = card.findViewById(R.id.extra_text_badge);
        if (badge != null) {
            // MEETUBE: the time badge becomes a rounded glass pill, lifted off the progress line
            float density = badge.getResources().getDisplayMetrics().density;
            int padH = Math.round(7 * density);
            int padV = Math.round(2 * density);
            badge.setPaddingRelative(padH, padV, padH, padV);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) badge.getLayoutParams();
            params.bottomMargin = Math.round(5 * density);
            params.setMarginEnd(Math.round(6 * density));
            badge.setLayoutParams(params);
        }
    }

    /** MeeTube's own sidebar icons in place of SmartTube's; pinned channels and playlists keep their avatars. */
    static int headerIcon(long sectionId, int fallback) {
        switch ((int) sectionId) {
            case MediaGroup.TYPE_HOME: return R.drawable.browse_ic_home;
            case MediaGroup.TYPE_SHORTS: return R.drawable.browse_ic_shorts;
            case MediaGroup.TYPE_TRENDING: return R.drawable.browse_ic_trending;
            case MediaGroup.TYPE_KIDS_HOME: return R.drawable.browse_ic_kids;
            case MediaGroup.TYPE_SPORTS: return R.drawable.browse_ic_sports;
            case MediaGroup.TYPE_LIVE: return R.drawable.browse_ic_live;
            case MediaGroup.TYPE_MY_VIDEOS: return R.drawable.browse_ic_my_videos;
            case MediaGroup.TYPE_GAMING: return R.drawable.browse_ic_gaming;
            case MediaGroup.TYPE_NEWS: return R.drawable.browse_ic_news;
            case MediaGroup.TYPE_MUSIC: return R.drawable.browse_ic_music;
            case MediaGroup.TYPE_CHANNEL_UPLOADS: return R.drawable.browse_ic_channels;
            case MediaGroup.TYPE_SUBSCRIPTIONS: return R.drawable.browse_ic_subscriptions;
            case MediaGroup.TYPE_HISTORY: return R.drawable.browse_ic_history;
            case MediaGroup.TYPE_BLOCKED_CHANNELS: return R.drawable.browse_ic_blocked;
            case MediaGroup.TYPE_USER_PLAYLISTS: return R.drawable.browse_ic_playlists;
            case MediaGroup.TYPE_NOTIFICATIONS: return R.drawable.browse_ic_notifications;
            case MediaGroup.TYPE_PLAYBACK_QUEUE: return R.drawable.browse_ic_queue;
            default: return fallback;
        }
    }

    /** Called after each bind: the card's badge color (black, or red for LIVE / NEW) as a glass pill. */
    public static void styleBadge(ComplexImageCardView card, boolean red) {
        View badge = card.findViewById(R.id.extra_text_badge);
        if (badge != null) {
            badge.setBackgroundResource(red ? R.drawable.browse_glass_badge_red : R.drawable.browse_glass_badge);
        }
    }

    /** A row whose cards scrolled past its start fade out across the left padding instead of being cut at the screen edge. */
    public static CustomListRowPresenter rowPresenter() {
        return new CustomListRowPresenter() {
            @Override
            protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
                super.initializeRowViewHolder(holder);
                HorizontalGridView grid = ((ListRowPresenter.ViewHolder) holder).getGridView();
                // Leanback fades [paddingLeft - offset - length, paddingLeft - offset] and draws nothing left
                // of it: the fade starts on the line the cards start on and is gone 20dp left of it.
                float density = grid.getResources().getDisplayMetrics().density;
                int length = Math.round(20 * density);
                grid.setFadingLeftEdge(true);
                grid.setFadingLeftEdgeLength(length);
                grid.setFadingLeftEdgeOffset(0);
            }
        };
    }

    public static RowHeaderPresenter rowHeaderPresenter() {
        return new RowHeaderPresenter() {
            @Override
            protected void onSelectLevelChanged(ViewHolder holder) {
                // Keep the existing typeface, dimensions and layout; only change the title opacity.
                holder.view.setAlpha(1f);
                TextView title = holder.view.findViewById(androidx.leanback.R.id.row_header);
                if (title != null) {
                    title.setTextColor(0xD9FFFFFF);
                }
            }
        };
    }
}
