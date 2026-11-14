package com.newtube.mobile.ui.channel;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.text.util.LinkifyCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;
import com.liskovsoft.mediaserviceinterfaces.data.CommunityPost;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.HomeLayout;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.ChannelAvatars;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

final class CommunityPostAdapter extends ListAdapter<CommunityPost, CommunityPostAdapter.Holder> {
    private final Set<String> mExpanded = new HashSet<>();
    private final Consumer<CommunityPost.TextRun> mOpenLink;
    private final Consumer<CommunityPost> mOpenComments;
    private final Consumer<CommunityPost> mLike;
    /** Likes changed here, over what the page said. */
    private final java.util.Map<String, Boolean> mLiked = new java.util.HashMap<>();
    /** Comment counts after a comment was posted here, over what the page said. */
    private final java.util.Map<String, String> mReplies = new java.util.HashMap<>();
    private static final String LIKE = "like";

    boolean isLiked(CommunityPost post) {
        Boolean liked = mLiked.get(post.id);
        return liked != null ? liked : post.liked;
    }

    void setLiked(CommunityPost post, boolean liked) {
        mLiked.put(post.id, liked);
        int position = getCurrentList().indexOf(post);
        if (position >= 0) notifyItemChanged(position, LIKE); // a payload: the same row, so the pop plays
    }

    void setReplies(CommunityPost post, String count) {
        mReplies.put(post.id, count);
        int position = getCurrentList().indexOf(post);
        if (position >= 0) notifyItemChanged(position, LIKE);
    }

    /** YouTube's text moved by one when the like changed here; "1.2K" and the like stay as they are. */
    private String likeCount(CommunityPost post) {
        boolean liked = isLiked(post);
        String text = post.likeCountText;
        if (liked == post.liked) return text;
        int count = TextUtils.isEmpty(text) ? 0 : com.liskovsoft.sharedutils.helpers.Helpers.isInteger(text)
                ? Integer.parseInt(text) : -1;
        if (count < 0) return text;
        count = Math.max(0, count + (liked ? 1 : -1));
        return count > 0 ? String.valueOf(count) : "";
    }

    CommunityPostAdapter(Consumer<CommunityPost.TextRun> openLink, Consumer<CommunityPost> openComments,
                         Consumer<CommunityPost> like) {
        super(new DiffUtil.ItemCallback<CommunityPost>() {
            @Override public boolean areItemsTheSame(@NonNull CommunityPost a, @NonNull CommunityPost b) {
                return a.id.equals(b.id);
            }
            @Override public boolean areContentsTheSame(@NonNull CommunityPost a, @NonNull CommunityPost b) {
                return a == b;
            }
        });
        mOpenLink = openLink;
        mOpenComments = openComments;
        mLike = like;
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_community_post, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull java.util.List<Object> payloads) {
        if (payloads.contains(LIKE) && holder.bound == getItem(position)) {
            holder.bindCounts(holder.bound, true);
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.bound = null;
        Glide.with(holder.avatar.getContext().getApplicationContext()).clear(holder.avatar);
        holder.clearAttachment();
        super.onViewRecycled(holder);
    }

    private SpannableStringBuilder linkedText(CommunityPost post) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (CommunityPost.TextRun run : post.text) text.append(run.text);
        LinkifyCompat.addLinks(text, Linkify.WEB_URLS);
        // Plain URLs get the same internal video/channel routing as endpoint-backed runs.
        for (URLSpan url : text.getSpans(0, text.length(), URLSpan.class)) {
            int start = text.getSpanStart(url);
            int end = text.getSpanEnd(url);
            text.removeSpan(url);
            text.setSpan(link(new CommunityPost.TextRun(text.subSequence(start, end).toString(),
                    url.getURL(), null, null)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int start = 0;
        for (CommunityPost.TextRun run : post.text) {
            int end = start + run.text.length();
            if (end > start && (run.url != null || run.videoId != null || run.channelId != null)) {
                for (ClickableSpan span : text.getSpans(start, end, ClickableSpan.class)) text.removeSpan(span);
                text.setSpan(link(run), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end;
        }
        return text;
    }

    private ClickableSpan link(CommunityPost.TextRun run) {
        return new ClickableSpan() {
            @Override public void onClick(@NonNull View widget) { mOpenLink.accept(run); }
        };
    }

    final class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView author;
        final TextView time;
        final TextView text;
        final TextView more;
        final TextView likes;
        final TextView replies;
        final LinearLayout attachment;
        final RecyclerView videoList;
        final VideoCardAdapter videoAdapter;
        CommunityPost bound;

        Holder(View root) {
            super(root);
            avatar = root.findViewById(R.id.mobile_post_avatar);
            author = root.findViewById(R.id.mobile_post_author);
            time = root.findViewById(R.id.mobile_post_time);
            text = root.findViewById(R.id.mobile_post_text);
            more = root.findViewById(R.id.mobile_post_read_more);
            likes = root.findViewById(R.id.mobile_post_likes);
            replies = root.findViewById(R.id.mobile_post_replies);
            attachment = root.findViewById(R.id.mobile_post_attachment);
            text.setMovementMethod(LinkMovementMethod.getInstance());
            Context context = root.getContext();
            videoAdapter = new VideoCardAdapter(video -> PlaybackPresenter.instance(context).openVideo(video))
                    .setListRows(HomeLayout.isVideoList(context, HomeLayout.PAGE_CHANNELS));
            videoList = new RecyclerView(context);
            videoList.setLayoutManager(new LinearLayoutManager(context));
            videoList.setNestedScrollingEnabled(false);
            videoList.setAdapter(videoAdapter);
            more.setOnClickListener(v -> {
                if (bound == null) return;
                if (!mExpanded.add(bound.id)) mExpanded.remove(bound.id);
                bindText(bound);
            });
        }

        void bind(CommunityPost post) {
            bound = post;
            author.setText(post.author);
            time.setText(post.timeText);
            Glide.with(avatar).load(post.avatarUrl).circleCrop()
                    .placeholder(ChannelAvatars.placeholder(post.author)).transition(ChannelAvatars.FADE).into(avatar);
            View.OnClickListener openAuthor = v -> mOpenLink.accept(new CommunityPost.TextRun(
                    post.author, null, null, post.authorChannelId));
            avatar.setOnClickListener(post.authorChannelId != null ? openAuthor : null);
            author.setOnClickListener(post.authorChannelId != null ? openAuthor : null);
            bindText(post);
            bindCounts(post, false);
            likes.setOnClickListener(v -> mLike.accept(post));
            replies.setVisibility(post.commentsParams == null ? View.GONE : View.VISIBLE);
            replies.setOnClickListener(v -> mOpenComments.accept(post));
            itemView.setOnClickListener(post.commentsParams == null ? null : v -> mOpenComments.accept(post));
            bindAttachment(post.attachment);
        }

        void bindCounts(CommunityPost post, boolean animate) {
            boolean liked = isLiked(post);
            String likeText = likeCount(post);
            boolean wasLiked = likes.getTag() == Boolean.TRUE;
            likes.setTag(liked);
            likes.setText(likeText);
            likes.setCompoundDrawablesRelativeWithIntrinsicBounds(liked
                    ? R.drawable.ic_watch_thumb_up : R.drawable.ic_watch_thumb_up_outline, 0, 0, 0);
            likes.setContentDescription(TextUtils.isEmpty(likeText)
                    ? itemView.getContext().getString(R.string.mobile_posts_like)
                    : itemView.getContext().getString(R.string.mobile_posts_likes, likeText));
            String replyText = mReplies.containsKey(post.id) ? mReplies.get(post.id) : post.replyCountText;
            replies.setText(replyText);
            replies.setContentDescription(itemView.getContext().getString(R.string.mobile_posts_replies, replyText));
            if (animate && liked && !wasLiked) pop(likes);
        }

        /** The like: the thumb springs up with a tilt and settles, as a press that took. */
        private void pop(View view) {
            view.animate().cancel();
            view.setScaleX(0.85f);
            view.setScaleY(0.85f);
            view.setRotation(-12f);
            view.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(420)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(4f)).start();
        }

        void bindText(CommunityPost post) {
            boolean expanded = mExpanded.contains(post.id);
            text.setText(linkedText(post));
            text.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            text.setMaxLines(expanded ? Integer.MAX_VALUE : 6);
            text.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
            more.setText(expanded ? R.string.mobile_comments_show_less : R.string.mobile_comments_read_more);
            more.setVisibility(expanded ? View.VISIBLE : View.GONE);
            text.post(() -> {
                if (bound != post || text.getLayout() == null) return;
                int last = text.getLayout().getLineCount() - 1;
                boolean clipped = last >= 0 && (last >= 6 || text.getLayout().getEllipsisCount(last) > 0);
                more.setVisibility(mExpanded.contains(post.id) || clipped ? View.VISIBLE : View.GONE);
            });
        }

        void clearAttachment() {
            clearImages(attachment);
            attachment.removeAllViews();
            videoAdapter.submitList(Collections.emptyList());
        }

        void bindAttachment(CommunityPost.Attachment value) {
            clearAttachment();
            attachment.setVisibility(value == null ? View.GONE : View.VISIBLE);
            if (value == null) return;
            Context context = attachment.getContext();
            if (value.images != null && !value.images.isEmpty()) {
                if (value.images.size() == 1) {
                    CommunityPost.Image image = value.images.get(0);
                    ConstraintLayout frame = new ConstraintLayout(context);
                    MaterialCardView card = imageCard(image);
                    ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(0, 0);
                    params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                    params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                    params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                    params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                    params.dimensionRatio = Math.max(1, image.width) + ":" + Math.max(1, image.height);
                    frame.addView(card, params);
                    attachment.addView(frame, new LinearLayout.LayoutParams(-1, -2));
                } else {
                    HorizontalScrollView scroll = new HorizontalScrollView(context);
                    scroll.setHorizontalScrollBarEnabled(false);
                    LinearLayout row = new LinearLayout(context);
                    scroll.addView(row);
                    for (CommunityPost.Image image : value.images) {
                        MaterialCardView card = imageCard(image);
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(240),
                                Math.max(1, Math.round(dp(240) * (Math.max(1, image.height) / (float) Math.max(1, image.width)))));
                        params.setMarginEnd(dp(8));
                        row.addView(card, params);
                    }
                    scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                        if (r - l == or - ol) return;
                        int width = Math.max(1, Math.round((r - l) * 0.85f));
                        for (int i = 0; i < row.getChildCount(); i++) {
                            CommunityPost.Image image = value.images.get(i);
                            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width,
                                    Math.max(1, Math.round(width * (Math.max(1, image.height) / (float) Math.max(1, image.width)))));
                            params.setMarginEnd(dp(8));
                            row.getChildAt(i).setLayoutParams(params);
                        }
                    });
                    attachment.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
                    TextView count = label(context.getResources().getQuantityString(
                            R.plurals.mobile_posts_images, value.images.size(), value.images.size()), true);
                    attachment.addView(count);
                }
            } else if (value.video != null) {
                CommunityPost.AttachedVideo attached = value.video;
                Video video = Video.from(attached.id);
                video.title = attached.title;
                video.cardImageUrl = attached.thumbnailUrl;
                video.badge = attached.lengthText;
                video.author = attached.author;
                video.secondTitle = attached.author;
                attachment.addView(videoList, new LinearLayout.LayoutParams(-1, -2));
                videoAdapter.submitList(Collections.singletonList(video));
            } else if (value.poll != null) {
                for (String choice : value.poll.choices) {
                    MaterialCardView card = new MaterialCardView(context);
                    card.setRadius(dp(8));
                    card.setCardElevation(0);
                    card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.mobile_color_surface));
                    card.setStrokeWidth(dp(1));
                    card.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_color_divider));
                    TextView row = label(choice, false);
                    row.setPadding(dp(12), dp(12), dp(12), dp(12));
                    card.addView(row);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.bottomMargin = dp(8);
                    attachment.addView(card, params);
                }
                if (value.poll.totalVotesText != null) attachment.addView(label(value.poll.totalVotesText, true));
            }
        }

        private MaterialCardView imageCard(CommunityPost.Image image) {
            Context context = attachment.getContext();
            MaterialCardView card = new MaterialCardView(context);
            card.setRadius(context.getResources().getDimension(R.dimen.mobile_card_corner_radius));
            card.setCardElevation(0);
            card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.mobile_color_placeholder));
            ImageView view = new ImageView(context);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setContentDescription(context.getString(R.string.mobile_posts_image));
            card.addView(view, new ViewGroup.LayoutParams(-1, -1));
            Glide.with(view).load(image.url).fitCenter().into(view);
            return card;
        }

        private TextView label(String value, boolean secondary) {
            TextView label = new TextView(attachment.getContext());
            label.setText(value);
            label.setTextSize(secondary ? 12 : 14);
            label.setTypeface(null, Typeface.NORMAL);
            label.setTextColor(ContextCompat.getColor(label.getContext(), secondary
                    ? R.color.mobile_color_on_surface_secondary : R.color.mobile_color_on_surface));
            label.setPadding(0, dp(6), 0, dp(2));
            return label;
        }

        private int dp(int value) {
            return Math.round(value * itemView.getResources().getDisplayMetrics().density);
        }

        private void clearImages(View view) {
            if (view instanceof ImageView) Glide.with(view.getContext().getApplicationContext()).clear((ImageView) view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) clearImages(group.getChildAt(i));
            }
        }
    }
}
