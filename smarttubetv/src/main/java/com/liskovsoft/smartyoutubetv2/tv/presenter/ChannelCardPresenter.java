package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.LongClickPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * MEETUBE: a Subscriptions (Channels) list row in the glass style - round avatar and name in a pill that turns white
 * on focus (dark text), like the sidebar; faint glass marks the channel whose videos are shown (the fragment sets
 * it activated) and a red dot replaces SmartTube's dark red background for new videos. "All" gets a white disc with
 * the subscriptions icon.
 */
public class ChannelCardPresenter extends LongClickPresenter {
    private static final String TAG = ChannelCardPresenter.class.getSimpleName();
    private static final int TEXT_COLOR = 0xF2FFFFFF;
    private static final int TEXT_COLOR_FOCUSED = 0xFF141414;
    private static final int ALL_ICON_COLOR = 0xFF141414;
    private int mWidth;
    private Video mSelected;
    private View mSelectedView;

    /** Marks the channel whose videos are shown: faint glass while the focus is on its videos. */
    public void setSelected(@Nullable ViewHolder holder, @Nullable Video item) {
        if (mSelectedView != null) {
            mSelectedView.setActivated(false);
        }
        mSelected = item;
        mSelectedView = holder != null ? holder.view : null;
        if (mSelectedView != null) {
            mSelectedView.setActivated(true);
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();

        updateDimensions(context);

        @SuppressLint("InflateParams")
        View container = LayoutInflater.from(context).inflate(R.layout.channel_card, null);

        TextView textView = container.findViewById(R.id.channel_title);
        textView.setTextColor(TEXT_COLOR);

        boolean autoScrollEnabled = isCardTextAutoScrollEnabled(context);
        if (autoScrollEnabled) {
            ViewUtil.setTextScrollSpeed(textView, getCardTextScrollSpeed(context));
        }

        container.setOnFocusChangeListener((v, hasFocus) -> {
            textView.setTextColor(hasFocus ? TEXT_COLOR_FOCUSED : TEXT_COLOR);

            if (!autoScrollEnabled) {
                return;
            }

            if (hasFocus) {
                ViewUtil.enableMarquee(textView);
            } else {
                ViewUtil.disableMarquee(textView);
            }
        });

        return new ViewHolder(container);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        super.onBindViewHolder(viewHolder, item);

        Context context = viewHolder.view.getContext();
        Video video = (Video) item;

        ViewUtil.setDimensions(viewHolder.view.findViewById(R.id.channel_card_wrapper), mWidth, -1); // don't do auto height

        TextView textView = viewHolder.view.findViewById(R.id.channel_title);
        textView.setText(video.getTitle());

        // We should setup props each time because object may be reused by the underlying RecyclerView
        boolean selected = video.equals(mSelected);
        viewHolder.view.setActivated(selected);
        if (selected) {
            mSelectedView = viewHolder.view;
        }
        viewHolder.view.findViewById(R.id.channel_new_dot).setVisibility(video.hasNewContent ? View.VISIBLE : View.GONE);

        ImageView imageView = viewHolder.view.findViewById(R.id.channel_image);

        if (BrowsePresenter.isAllChannelsItem(video)) {
            Glide.with(context).clear(imageView);
            int pad = Math.round(9 * context.getResources().getDisplayMetrics().density);
            imageView.setPadding(pad, pad, pad, pad);
            imageView.setBackgroundResource(R.drawable.channel_avatar_circle);
            imageView.setImageResource(R.drawable.browse_ic_subscriptions);
            imageView.setColorFilter(ALL_ICON_COLOR);
            return;
        }

        imageView.setPadding(0, 0, 0, 0);
        imageView.setBackground(null);
        imageView.clearColorFilter();

        Glide.with(context)
                .load(video.cardImageUrl)
                .apply(ViewUtil.glideOptions())
                .circleCrop()
                .listener(mErrorListener)
                .into(imageView);
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        // Remove references to images so that the garbage collector can free up memory.
        ImageView imageView = viewHolder.view.findViewById(R.id.channel_image);
        imageView.setImageDrawable(null);
    }

    private void updateDimensions(Context context) {
        Pair<Integer, Integer> dimens = getCardDimensPx(context);

        mWidth = dimens.first;
    }

    protected Pair<Integer, Integer> getCardDimensPx(Context context) {
        return GridFragmentHelper.getCardDimensPx(
                context, R.dimen.channel_card_width,
                R.dimen.channel_card_height,
                MainUIData.instance(context).getVideoGridScale(),
                true);
    }

    protected boolean isCardTextAutoScrollEnabled(Context context) {
        return MainUIData.instance(context).isCardTextAutoScrollEnabled();
    }

    protected float getCardTextScrollSpeed(Context context) {
        return MainUIData.instance(context).getCardTextScrollSpeed();
    }

    private final RequestListener<Drawable> mErrorListener = new RequestListener<Drawable>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
