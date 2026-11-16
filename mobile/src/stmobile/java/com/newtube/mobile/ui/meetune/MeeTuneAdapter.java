package com.newtube.mobile.ui.meetune;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.newtube.mobile.ui.common.Motion;
import com.google.android.material.card.MaterialCardView;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * MEETUNE: one page as rows - shelf titles, sideways shelves (big cards, tiles, moods, or songs
 * four to a column as Home's Recents), a page head, plain song rows, and Settings' switches.
 */
final class MeeTuneAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Host {
        void open(MusicApi.Item item, List<MusicApi.Item> siblings);

        void showAll(MusicApi.Shelf shelf);

        void songMenu(View anchor, MusicApi.Item item);

        /** The head's Add: save the page to the library, or take it out (the icon follows). */
        void save(MusicApi.Item head, ImageView icon);

        /** The head's More. */
        void pageMenu(View anchor, MusicApi.Item head);

        /** The head's find box changed. */
        void find(String query);

        /** Search's top result's Playlist: MeeTube's Add to playlist. */
        void addToPlaylist(MusicApi.Item item);
    }

    static final int TITLE = 0;
    static final int CAROUSEL = 1;
    static final int SONG = 2;
    static final int HEADER = 3;
    /** A page that is one view of its own (MeeTune's settings and Equalizer). */
    static final int VIEW = 4;
    /** Search's top result. */
    static final int TOP = 5;

    static final int BIG = 0;
    static final int TILE = 1;
    static final int MOODS = 2;
    static final int COLUMNS = 3;
    /** The Library's cards: all of them, three a row, instead of one sideways row. */
    static final int GRID = 4;

    static final class Row {
        final int type;
        MusicApi.Shelf shelf;
        MusicApi.Item item;
        List<MusicApi.Item> siblings;
        int style;
        View view;

        Row(int type) {
            this.type = type;
        }
    }

    private final Host mHost;
    private final List<Row> mRows = new ArrayList<>();
    @Nullable
    private RecyclerView mList;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView list) {
        mList = list;
        CoverGlow.attach(list);
        list.setClipChildren(false); // a shelf's covers glow past its own top and bottom
    }

    /** New rows under the same head: the head stays bound (its find box keeps focus and keyboard). */
    void setTail(List<Row> rows) {
        int old = mRows.size();
        mRows.clear();
        mRows.addAll(rows);
        if (old > 1) notifyItemRangeRemoved(1, old - 1);
        if (rows.size() > 1) notifyItemRangeInserted(1, rows.size() - 1);
    }

    /** Hairlines between a list's songs, from under their titles. */
    RecyclerView.ItemDecoration dividers(Context context) {
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(androidx.core.content.ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
        paint.setAlpha(0x2E);
        paint.setStrokeWidth(Math.max(1, dp(context, 1) / 2));
        int end = dp(context, 20);
        return new RecyclerView.ItemDecoration() {
            @Override
            public void onDraw(@NonNull android.graphics.Canvas canvas, @NonNull RecyclerView parent,
                    @NonNull RecyclerView.State state) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    int at = parent.getChildAdapterPosition(child);
                    if (at == RecyclerView.NO_POSITION || at + 1 >= mRows.size()
                            || mRows.get(at).type != SONG || mRows.get(at + 1).type != SONG) continue;
                    View text = (View) child.findViewById(R.id.meetune_subtitle).getParent();
                    float y = child.getBottom() + child.getTranslationY();
                    canvas.drawLine(child.getLeft() + text.getLeft(), y, child.getRight() - end, y, paint);
                }
            }
        };
    }

    MeeTuneAdapter(Host host) {
        mHost = host;
    }

    void setRows(List<Row> rows) {
        mRows.clear();
        mRows.addAll(rows);
        notifyDataSetChanged();
    }

    /** Shelves to rows. {@code vertical}: songs list down the page (album, playlist, search). */
    static List<Row> rows(List<MusicApi.Shelf> shelves, boolean vertical, boolean bigFirst) {
        List<Row> rows = new ArrayList<>();
        List<MusicApi.Item> pageSongs = new ArrayList<>();
        for (MusicApi.Shelf shelf : shelves) {
            for (MusicApi.Item item : shelf.items) {
                if (item.kind == MusicApi.SONG) pageSongs.add(item);
            }
        }
        // An album's songs come without art (they share the album's): its cover, from the head.
        String headArt = null;
        for (MusicApi.Shelf shelf : shelves) {
            if (shelf.header && !shelf.items.isEmpty()) headArt = shelf.items.get(0).art;
        }
        int number = 0;
        for (MusicApi.Item song : pageSongs) {
            if (song.art == null) {
                song.art = headArt;
                song.number = ++number; // an album's tracks: numbered, as the album lists them
            }
        }
        // A page whose head has no art (a mood, Charts): the first song's cover, else the first cover on it.
        String pageArt = null;
        for (MusicApi.Item song : pageSongs) if (pageArt == null) pageArt = song.art;
        for (MusicApi.Shelf shelf : shelves) {
            if (shelf.header) continue;
            for (MusicApi.Item item : shelf.items) if (pageArt == null) pageArt = item.art;
        }
        for (MusicApi.Shelf shelf : shelves) {
            if (shelf.header && !shelf.items.isEmpty() && shelf.items.get(0).art == null) shelf.items.get(0).art = pageArt;
        }
        boolean bigUsed = !bigFirst;
        for (MusicApi.Shelf shelf : shelves) {
            if (shelf.header) {
                Row row = new Row(HEADER);
                row.item = shelf.items.get(0);
                row.siblings = pageSongs;
                rows.add(row);
                continue;
            }
            if (shelf.title != null && !shelf.title.isEmpty()) {
                Row title = new Row(TITLE);
                title.shelf = shelf;
                rows.add(title);
            }
            if (vertical && shelf.allSongs()) {
                for (MusicApi.Item item : shelf.items) {
                    Row row = new Row(SONG);
                    row.item = item;
                    row.siblings = shelf.items;
                    rows.add(row);
                }
                continue;
            }
            Row row = new Row(CAROUSEL);
            row.shelf = shelf;
            if (shelf.allMoods()) {
                row.style = MOODS;
            } else if (shelf.allSongs()) {
                row.style = COLUMNS;
            } else if (!bigUsed) {
                row.style = BIG;
                bigUsed = true;
            } else {
                row.style = TILE;
            }
            rows.add(row);
        }
        if (bigFirst) { // Home opens on its big cards (with their title), the song columns under them
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.type == CAROUSEL && row.style == BIG) {
                    int from = i > 0 && rows.get(i - 1).type == TITLE && rows.get(i - 1).shelf == row.shelf ? i - 1 : i;
                    List<Row> lead = new ArrayList<>(rows.subList(from, i + 1));
                    rows.subList(from, i + 1).clear();
                    rows.addAll(0, lead);
                    break;
                }
            }
        }
        return rows;
    }

    /**
     * Search's results, as YouTube Music lists them: the top result big with Play and Playlist, then
     * each section (Songs, Videos, Albums, Artists, Playlists) down the page, songs by their artists.
     */
    static List<Row> searchRows(List<MusicApi.Shelf> shelves) {
        List<Row> rows = new ArrayList<>();
        for (MusicApi.Shelf shelf : shelves) {
            List<MusicApi.Item> playable = new ArrayList<>();
            for (MusicApi.Item item : shelf.items) {
                if (!item.playable()) continue;
                if (item.artist != null) item.subtitle = item.artist;
                playable.add(item);
            }
            if (shelf.title != null && !shelf.title.isEmpty()) {
                Row title = new Row(TITLE);
                title.shelf = shelf;
                rows.add(title);
            }
            for (int i = 0; i < shelf.items.size(); i++) {
                Row row = new Row(shelf.top && i == 0 ? TOP : SONG);
                row.item = shelf.items.get(i);
                row.siblings = playable;
                rows.add(row);
            }
        }
        return rows;
    }

    static Row viewRow(View view) {
        Row row = new Row(VIEW);
        row.view = view;
        return row;
    }

    @Override
    public int getItemViewType(int position) {
        return mRows.get(position).type;
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view;
        switch (type) {
            case TITLE:
                view = inflater.inflate(R.layout.meetune_shelf_title, parent, false);
                TextView title = view.findViewById(R.id.meetune_shelf_title);
                ((TextView) view.findViewById(R.id.meetune_shelf_more)).setTextColor(title.getCurrentTextColor()); // white too
                glow(title, 10);
                glow(view.findViewById(R.id.meetune_shelf_more), 8);
                break;
            case SONG:
                view = inflater.inflate(R.layout.meetune_song_row, parent, false);
                break;
            case TOP:
                view = inflater.inflate(R.layout.meetune_top_result, parent, false);
                break;
            case HEADER:
                view = inflater.inflate(R.layout.meetune_page_header, parent, false);
                view.findViewById(R.id.meetune_hero).getLayoutParams().height = Math.round(parent.getWidth() * 0.9f);
                break;
            case VIEW:
                view = new android.widget.FrameLayout(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                break;
            default:
                RecyclerView list = new RecyclerView(parent.getContext());
                list.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                list.setLayoutManager(new LinearLayoutManager(parent.getContext(), RecyclerView.HORIZONTAL, false));
                list.setClipToPadding(false);
                int pad = dp(parent.getContext(), 20);
                list.setPadding(pad, 0, pad, 0);
                list.setNestedScrollingEnabled(false);
                view = list;
        }
        return new RecyclerView.ViewHolder(view) { };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = mRows.get(position);
        View view = holder.itemView;
        switch (row.type) {
            case TITLE:
                ((TextView) view.findViewById(R.id.meetune_shelf_title)).setText(row.shelf.title);
                View more = view.findViewById(R.id.meetune_shelf_more);
                more.setVisibility(row.shelf.moreBrowseId != null ? View.VISIBLE : View.GONE);
                more.setOnClickListener(v -> mHost.showAll(row.shelf));
                break;
            case SONG:
                bindItem(view, row.item);
                TextView number = view.findViewById(R.id.meetune_number);
                number.setText(String.valueOf(row.item.number));
                number.setVisibility(row.item.number > 0 ? View.VISIBLE : View.GONE);
                view.findViewById(R.id.meetune_art_card).setVisibility(row.item.number > 0 ? View.GONE : View.VISIBLE);
                TextView length = view.findViewById(R.id.meetune_duration);
                length.setText(row.item.duration);
                length.setVisibility(row.item.duration != null && !row.item.duration.isEmpty() ? View.VISIBLE : View.GONE);
                view.setOnClickListener(v -> {
                    Motion.nudge(v);
                    mHost.open(row.item, row.siblings);
                });
                View songMore = view.findViewById(R.id.meetune_row_more);
                songMore.setVisibility(row.item.playable() ? View.VISIBLE : View.INVISIBLE);
                songMore.setOnClickListener(v -> mHost.songMenu(v, row.item));
                break;
            case TOP:
                bindItem(view, row.item);
                view.findViewById(R.id.meetune_top_head).setOnClickListener(v -> {
                    Motion.nudge(v);
                    mHost.open(row.item, row.siblings);
                });
                View topMore = view.findViewById(R.id.meetune_row_more);
                topMore.setVisibility(row.item.playable() ? View.VISIBLE : View.GONE);
                topMore.setOnClickListener(v -> mHost.songMenu(v, row.item));
                view.findViewById(R.id.meetune_top_play).setOnClickListener(v -> {
                    Motion.pop(v);
                    mHost.open(row.item, row.siblings);
                });
                View playlist = view.findViewById(R.id.meetune_top_playlist);
                playlist.setVisibility(row.item.playable() ? View.VISIBLE : View.GONE);
                playlist.setOnClickListener(v -> {
                    Motion.pop(v);
                    mHost.addToPlaylist(row.item);
                });
                break;
            case HEADER:
                bindHead(view, row);
                break;
            case VIEW:
                ViewGroup host = (ViewGroup) view;
                if (row.view.getParent() != host) {
                    if (row.view.getParent() != null) ((ViewGroup) row.view.getParent()).removeView(row.view);
                    host.removeAllViews();
                    host.addView(row.view);
                }
                break;
            default:
                RecyclerView list = (RecyclerView) view;
                boolean grid = row.style == GRID;
                if (grid != list.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager) {
                    list.setLayoutManager(grid ? new androidx.recyclerview.widget.GridLayoutManager(view.getContext(), 3)
                            : new LinearLayoutManager(view.getContext(), RecyclerView.HORIZONTAL, false));
                    int side = dp(view.getContext(), grid ? 15 : 20); // a grid cell's 5dp margins make it 20
                    list.setPadding(side, 0, side, 0);
                }
                boolean dial = row.shelf.dial && MeeTune.speedDial(view.getContext());
                CoverGlow.attach(list);
                list.setAdapter(new Carousel(row, dial));
                list.setOnFlingListener(null);
                if (row.style == COLUMNS || dial) new PagerSnapHelper().attachToRecyclerView(list);
        }
    }

    /**
     * The page's head: its cover across the top (up under the bar, fading into the page), name,
     * artist, what it is, and its buttons.
     */
    private void bindHead(View view, Row row) {
        MusicApi.Item head = row.item;
        ImageView hero = view.findViewById(R.id.meetune_hero);
        Glide.with(hero).load(head.art).placeholder(new MeeTuneCover(head.title)).into(hero);
        RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) view.getLayoutParams();
        lp.topMargin = mList != null ? -mList.getPaddingTop() : 0;
        ((TextView) view.findViewById(R.id.meetune_title)).setText(head.title);
        line(view.findViewById(R.id.meetune_artist), head.artist);
        line(view.findViewById(R.id.meetune_meta), head.meta != null ? head.meta.toUpperCase(java.util.Locale.getDefault()) : null);
        view.findViewById(R.id.meetune_header_actions).setVisibility(row.siblings.isEmpty() ? View.GONE : View.VISIBLE);
        // Liquid glass over the blurred cover behind the head (the list can't be its own source).
        View cover = ((android.app.Activity) view.getContext()).findViewById(R.id.meetune_cover_source);
        for (int id : new int[] {R.id.meetune_add, R.id.meetune_shuffle, R.id.meetune_find, R.id.meetune_page_more,
                R.id.meetune_find_glass}) {
            com.newtube.mobile.ui.common.Glass.blur(view.findViewById(id), cover);
        }
        View add = view.findViewById(R.id.meetune_add);
        ImageView addIcon = view.findViewById(R.id.meetune_add_icon);
        add.setVisibility(head.saveId != null ? View.VISIBLE : View.GONE);
        addIcon.setImageResource(head.saved ? R.drawable.ic_mobile_check : R.drawable.ic_mobile_add);
        add.setOnClickListener(v -> {
            Motion.pop(v);
            mHost.save(head, addIcon);
        });
        view.findViewById(R.id.meetune_play).setOnClickListener(v -> {
            Motion.pop(v);
            if (!row.siblings.isEmpty()) mHost.open(row.siblings.get(0), row.siblings);
        });
        view.findViewById(R.id.meetune_shuffle).setOnClickListener(v -> {
            Motion.pop(v);
            if (!row.siblings.isEmpty()) {
                mHost.open(row.siblings.get((int) (Math.random() * row.siblings.size())), row.siblings);
            }
        });
        view.findViewById(R.id.meetune_page_more).setOnClickListener(v -> {
            Motion.pop(v);
            mHost.pageMenu(v, head);
        });
        View findGlass = view.findViewById(R.id.meetune_find_glass);
        android.widget.EditText field = view.findViewById(R.id.meetune_find_field);
        if (field.getTag() == null) {
            field.setTag(Boolean.TRUE);
            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    mHost.find(s.toString());
                }
            });
        }
        view.findViewById(R.id.meetune_find).setOnClickListener(v -> {
            Motion.pop(v);
            android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager)
                    v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (findGlass.getVisibility() == View.VISIBLE) {
                field.setText(null); // all the songs again
                findGlass.setVisibility(View.GONE);
                if (input != null) input.hideSoftInputFromWindow(field.getWindowToken(), 0);
            } else {
                findGlass.setVisibility(View.VISIBLE);
                Motion.frostIn(findGlass);
                field.requestFocus();
                if (input != null) input.showSoftInput(field, 0);
            }
        });
    }

    private static void line(TextView view, @Nullable String text) {
        view.setText(text);
        view.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Title, subtitle and art into a view that has them (any of the item layouts). */
    /**
     * A shelf's title and Show all glow softly, a little see-through: the text's own colour (white in
     * dark, ink in light) haloed in itself.
     */
    private static void glow(TextView text, int radiusDp) {
        int color = text.getCurrentTextColor();
        int radius = dp(text.getContext(), radiusDp);
        text.setTextColor((color & 0x00FFFFFF) | 0xF5000000); // bright, the glow carries the softness
        text.setShadowLayer(radius, 0, 0, (color & 0x00FFFFFF) | 0x59000000);
        // The halo needs room to fade to nothing: a text's shadow is cut at its own edges, which drew
        // a hard block. Padding as wide as the halo, given back by negative margins (layout unchanged).
        int room = radius + dp(text.getContext(), 2);
        text.setPadding(text.getPaddingLeft() + room, text.getPaddingTop() + room,
                text.getPaddingRight() + room, text.getPaddingBottom() + room);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) text.getLayoutParams();
        lp.setMargins(lp.leftMargin - room, lp.topMargin - room, lp.rightMargin - room, lp.bottomMargin - room);
        text.setLayoutParams(lp);
        ViewGroup row = (ViewGroup) text.getParent();
        row.setClipChildren(false);
        row.setClipToPadding(false);
    }

    static void bindItem(View view, MusicApi.Item item) {
        TextView title = view.findViewById(R.id.meetune_title);
        TextView subtitle = view.findViewById(R.id.meetune_subtitle);
        ImageView art = view.findViewById(R.id.meetune_art);
        if (title != null) title.setText(item.title);
        if (subtitle != null) {
            subtitle.setText(item.subtitle);
            subtitle.setVisibility(item.subtitle == null || item.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        }
        View badge = view.findViewById(R.id.meetune_explicit);
        if (badge != null) badge.setVisibility(item.explicit ? View.VISIBLE : View.GONE);
        View card = view.findViewById(R.id.meetune_art_card);
        if (card instanceof MaterialCardView) { // an artist round, the rest as the layout has them (rows are reused)
            MaterialCardView cover = (MaterialCardView) card;
            if (cover.getTag() == null) cover.setTag(cover.getRadius());
            cover.setRadius(item.round ? card.getLayoutParams().width / 2f : (Float) cover.getTag());
        }
        if (art != null) {
            android.graphics.drawable.Drawable cover = new MeeTuneCover(item.title);
            if (item.icon != 0) { // its sign in the middle of its cover
                android.graphics.drawable.Drawable glyph = art.getContext().getDrawable(item.icon).mutate();
                glyph.setTint(0xF2FFFFFF);
                android.graphics.drawable.LayerDrawable layers =
                        new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                                new MeeTuneCover(item.title, 0xFF2E7DFF), glyph});
                int side = dp(art.getContext(), 56);
                layers.setLayerSize(1, side, side);
                layers.setLayerGravity(1, android.view.Gravity.CENTER);
                cover = layers;
            }
            Glide.with(art).load(item.art).placeholder(cover) // no art: its own cover
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                Object model, @NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                boolean first) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(@NonNull android.graphics.drawable.Drawable resource, @NonNull Object model,
                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                @NonNull com.bumptech.glide.load.DataSource source, boolean first) {
                            art.post(() -> { // the list redraws its glow in the new cover's colour
                                for (android.view.ViewParent p = art.getParent(); p != null; p = p.getParent()) {
                                    if (p instanceof RecyclerView) {
                                        ((RecyclerView) p).invalidate();
                                        break;
                                    }
                                }
                            });
                            return false;
                        }
                    })
                    .into(art);
            art.setForeground(MeeTunePlaying.on(art.getContext(), item.videoId)); // bars while it plays
        }
    }

    private final class Carousel extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Row mRow;
        private final List<MusicApi.Item> mItems;

        /** Speed dial: pages of 3x3 square tiles, the name over each. */
        private final boolean mDial;

        Carousel(Row row, boolean dial) {
            mRow = row;
            mItems = row.shelf.items;
            mDial = dial;
        }

        @Override
        public int getItemCount() {
            return mDial ? (mItems.size() + 8) / 9 : mRow.style == COLUMNS ? (mItems.size() + 3) / 4 : mItems.size();
        }

        private View dialPage(ViewGroup parent) {
            Context context = parent.getContext();
            int gap = dp(context, 8);
            int width = parent.getResources().getDisplayMetrics().widthPixels - dp(context, 68);
            int size = (width - 2 * gap) / 3;
            LinearLayout page = new LinearLayout(context);
            page.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(context, 12));
            page.setLayoutParams(lp);
            for (int r = 0; r < 3; r++) {
                LinearLayout line = new LinearLayout(context);
                for (int c = 0; c < 3; c++) {
                    MaterialCardView card = new MaterialCardView(context);
                    card.setId(R.id.meetune_art_card);
                    card.setRadius(dp(context, 12));
                    card.setCardElevation(0);
                    card.setStrokeWidth(0);
                    card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.mobile_color_card));
                    ImageView art = new ImageView(context);
                    art.setId(R.id.meetune_art);
                    art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    card.addView(art, new android.widget.FrameLayout.LayoutParams(-1, -1));
                    View scrim = new View(context); // the name stays clear on any cover
                    scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                            new int[] {0xB3000000, 0x00000000}));
                    card.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, size / 2, android.view.Gravity.BOTTOM));
                    TextView title = new TextView(context);
                    title.setId(R.id.meetune_title);
                    title.setTextColor(0xFFFFFFFF);
                    title.setTextSize(12);
                    title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                    title.setMaxLines(2);
                    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    title.setShadowLayer(dp(context, 3), 0, 0, 0x66000000);
                    int pad = dp(context, 8);
                    title.setPadding(pad, pad, pad, pad);
                    card.addView(title, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(size, size);
                    if (c > 0) cell.setMarginStart(gap);
                    line.addView(card, cell);
                }
                LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
                if (r > 0) row.topMargin = gap;
                page.addView(line, row);
            }
            return page;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view;
            if (mDial) {
                view = dialPage(parent);
            } else if (mRow.style == COLUMNS) {
                LinearLayout column = new LinearLayout(parent.getContext());
                column.setOrientation(LinearLayout.VERTICAL);
                int width = parent.getResources().getDisplayMetrics().widthPixels - dp(parent.getContext(), 56);
                column.setLayoutParams(new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
                for (int i = 0; i < 4; i++) {
                    View song = inflater.inflate(R.layout.meetune_song_row, column, false);
                    song.setPadding(0, 0, 0, 0);
                    column.addView(song);
                }
                view = column;
            } else {
                int layout = mRow.style == BIG ? R.layout.meetune_card_big
                        : mRow.style == MOODS ? R.layout.meetune_mood : R.layout.meetune_tile;
                view = inflater.inflate(layout, parent, false);
                if (mRow.style == GRID) { // a third of the row, square cover, smaller type
                    int gap = dp(parent.getContext(), 5);
                    int size = (parent.getResources().getDisplayMetrics().widthPixels - dp(parent.getContext(), 30)) / 3 - 2 * gap;
                    RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(gap, 0, gap, dp(parent.getContext(), 14));
                    view.setLayoutParams(lp);
                    View card = view.findViewById(R.id.meetune_art_card);
                    card.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                    card.getLayoutParams().height = size;
                    ((TextView) view.findViewById(R.id.meetune_title)).setTextSize(13);
                    ((TextView) view.findViewById(R.id.meetune_subtitle)).setTextSize(12);
                }
            }
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            View view = holder.itemView;
            if (mDial) {
                ViewGroup page = (ViewGroup) view;
                for (int i = 0; i < 9; i++) {
                    View tile = ((ViewGroup) page.getChildAt(i / 3)).getChildAt(i % 3);
                    int index = position * 9 + i;
                    if (index >= mItems.size()) {
                        tile.setVisibility(View.INVISIBLE);
                        continue;
                    }
                    MusicApi.Item item = mItems.get(index);
                    tile.setVisibility(View.VISIBLE);
                    bindItem(tile, item);
                    tile.setOnClickListener(v -> {
                        Motion.pop(v);
                        mHost.open(item, mItems);
                    });
                    if (item.playable()) {
                        tile.setOnLongClickListener(v -> {
                            mHost.songMenu(v, item);
                            return true;
                        });
                    } else {
                        tile.setOnLongClickListener(null);
                    }
                }
                return;
            }
            if (mRow.style == COLUMNS) {
                LinearLayout column = (LinearLayout) view;
                for (int i = 0; i < 4; i++) {
                    View song = column.getChildAt(i);
                    int index = position * 4 + i;
                    if (index >= mItems.size()) {
                        song.setVisibility(View.INVISIBLE);
                        continue;
                    }
                    MusicApi.Item item = mItems.get(index);
                    song.setVisibility(View.VISIBLE);
                    bindItem(song, item);
                    song.setOnClickListener(v -> {
                        Motion.nudge(v);
                        mHost.open(item, mItems);
                    });
                    song.findViewById(R.id.meetune_row_more).setOnClickListener(v -> mHost.songMenu(v, item));
                }
                return;
            }
            MusicApi.Item item = mItems.get(position);
            if (mRow.style == MOODS) {
                ((TextView) view.findViewById(R.id.meetune_title)).setText(item.title);
                bindStack(view, item, moodColor(item, position));
            } else {
                bindItem(view, item);
            }
            view.setOnClickListener(v -> {
                Motion.pop(v);
                mHost.open(item, mItems);
            });
        }
        /** An Explore card's front cover and the two behind it: its first three songs' covers. */
        private void bindStack(View view, MusicApi.Item item, int color) {
            int[] cards = {R.id.meetune_mood_card, R.id.meetune_mood_back1, R.id.meetune_mood_back2};
            int[] arts = {R.id.meetune_mood_art, R.id.meetune_mood_art_back1, R.id.meetune_mood_art_back2};
            String key = item.browseId + "|" + item.params;
            List<String> covers = STACK_COVERS.get(key);
            for (int i = 0; i < 3; i++) {
                ((MaterialCardView) view.findViewById(cards[i])).setCardBackgroundColor(color);
                ImageView art = view.findViewById(arts[i]);
                String cover = covers == null || covers.isEmpty() ? null : covers.get(Math.min(i, covers.size() - 1));
                Glide.with(art).load(cover).placeholder(new MeeTuneCover(item.title + i, color)).into(art);
            }
            if (covers != null || item.browseId == null || !STACK_LOADING.add(key)) return;
            MeeTune.run((Activity) mHost, () -> {
                List<String> found = new ArrayList<>(); // ponytail: one browse per card, cached for the process
                for (MusicApi.Shelf shelf : MusicApi.browse(view.getContext(), item.browseId, item.params)) {
                    if (shelf.header) continue;
                    for (MusicApi.Item song : shelf.items) {
                        if (song.art != null && found.size() < 3 && !found.contains(song.art)) found.add(song.art);
                    }
                }
                return found;
            }, (found, error) -> {
                STACK_LOADING.remove(key);
                if (found == null) return; // offline: keeps its lights, tries again next time
                STACK_COVERS.put(key, found);
                notifyDataSetChanged();
            });
        }
    }

    private static final java.util.Map<String, List<String>> STACK_COVERS = new java.util.HashMap<>();
    private static final java.util.Set<String> STACK_LOADING = new java.util.HashSet<>();

    private static final int[] MOOD_COLORS = {0xFF1DB954, 0xFF2D9CDB, 0xFF3F51B5, 0xFFE91E63, 0xFFFF9800, 0xFF9C27B0};

    private static int moodColor(MusicApi.Item item, int position) {
        int color = item.color != 0 ? item.color | 0xFF000000 : MOOD_COLORS[position % MOOD_COLORS.length];
        // YouTube's stripe colors are vivid accents; a touch darker keeps white text readable.
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(hsv[2], 0.78f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    @Nullable
    Row rowAt(int position) {
        return position >= 0 && position < mRows.size() ? mRows.get(position) : null;
    }
}
