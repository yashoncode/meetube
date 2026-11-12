package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowPresenter;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** MEETUBE: compact feed selectors with their own row, without card zoom, shadows or a heading. */
public final class HomeChipPresenter extends Presenter {
    public static final class ChipRow extends ListRow {
        public ChipRow(ObjectAdapter adapter) {
            super(adapter);
        }
    }

    public static ListRowPresenter rowPresenter() {
        ListRowPresenter presenter = new ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false) {
            @Override
            protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
                super.initializeRowViewHolder(holder);
                HorizontalGridView grid = ((ListRowPresenter.ViewHolder) holder).getGridView();
                float density = grid.getResources().getDisplayMetrics().density;
                boolean segment = isSegment(grid);
                grid.setHorizontalSpacing(segment ? 0 : Math.round(8 * density));
                grid.setRowHeight(Math.round((segment ? 46 : 38) * density));
                if (segment) {
                    grid.addItemDecoration(new SegmentTrack(density));
                }
                applyChipPadding(holder);
            }

            // MEETUBE: a headerless chip row must not reserve Leanback's expanded header gap.
            private void applyChipPadding(RowPresenter.ViewHolder holder) {
                HorizontalGridView grid = ((ListRowPresenter.ViewHolder) holder).getGridView();
                int bottom = grid.getResources().getDimensionPixelSize(
                        androidx.leanback.R.dimen.lb_browse_expanded_row_no_hovercard_bottom_padding);
                grid.setPadding(grid.getPaddingLeft(), 0, grid.getPaddingRight(), bottom);
            }

            @Override
            protected void onRowViewSelected(RowPresenter.ViewHolder holder, boolean selected) {
                super.onRowViewSelected(holder, selected);
                applyChipPadding(holder);
            }

            @Override
            protected void onRowViewExpanded(RowPresenter.ViewHolder holder, boolean expanded) {
                super.onRowViewExpanded(holder, expanded);
                applyChipPadding(holder);
            }

            @Override
            protected void onBindRowViewHolder(RowPresenter.ViewHolder holder, Object item) {
                super.onBindRowViewHolder(holder, item);
                ObjectAdapter adapter = ((ListRow) item).getAdapter();
                BrowsePresenter browse = BrowsePresenter.instance(holder.view.getContext());
                for (int i = 0; i < adapter.size(); i++) {
                    if (browse.isHomeChipActive((Video) adapter.get(i))) {
                        ((ListRowPresenter.ViewHolder) holder).getGridView().setSelectedPosition(i);
                        break;
                    }
                }
            }
        };
        presenter.setHeaderPresenter(null);
        presenter.setShadowEnabled(false);
        presenter.enableChildRoundedCorners(false);
        presenter.setSelectEffectEnabled(false);
        presenter.setSyncActivatePolicy(RowPresenter.SYNC_ACTIVATED_CUSTOM); // MEETUBE: each chip owns its active state.
        return presenter;
    }

    /** Read once per row: the setting asks for a restart. */
    private static boolean isSegment(View view) {
        return !MainUIData.instance(view.getContext()).isHomeChipPillsEnabled();
    }

    /** MEETUBE: one glass track behind the chips, rounded at the first and last chip. */
    private static final class SegmentTrack extends RecyclerView.ItemDecoration {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final int mPad;

        SegmentTrack(float density) {
            mPad = Math.round(4 * density);
            mFill.setColor(0x1AFFFFFF);
            mStroke.setColor(0x33FFFFFF);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(density);
        }

        @Override
        public void getItemOffsets(Rect out, View view, RecyclerView parent, RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            out.left = position == 0 ? mPad : 0;
            out.right = position == state.getItemCount() - 1 ? mPad : 0;
        }

        @Override
        public void onDraw(Canvas canvas, RecyclerView parent, RecyclerView.State state) {
            if (parent.getChildCount() == 0) {
                return;
            }
            RecyclerView.LayoutManager lm = parent.getLayoutManager();
            float left = Float.MAX_VALUE, right = -Float.MAX_VALUE, top = 0, bottom = 0;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                left = Math.min(left, lm.getDecoratedLeft(child) + child.getTranslationX());
                right = Math.max(right, lm.getDecoratedRight(child) + child.getTranslationX());
                top = child.getTop();
                bottom = child.getBottom();
            }
            float radius = (bottom - top) / 2f;
            mRect.set(left + 0.5f, top + 0.5f, right - 0.5f, bottom - 0.5f);
            canvas.drawRoundRect(mRect, radius, radius, mFill);
            canvas.drawRoundRect(mRect, radius, radius, mStroke);
        }
    }

    private static void recolor(TextView pill, boolean segment) {
        boolean dark = segment ? pill.isActivated() : pill.hasFocus();
        pill.setTextColor(dark ? 0xFF141414 : 0xFFFFFFFF);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        TextView pill = new TextView(parent.getContext());
        float density = parent.getResources().getDisplayMetrics().density;
        boolean segment = isSegment(parent);
        pill.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round((segment ? 46 : 38) * density)));
        pill.setBackgroundResource(segment ? R.drawable.home_segment_chip : R.drawable.home_glass_chip); // before the padding: a background resets it
        pill.setPadding(Math.round(18 * density), 0, Math.round(18 * density), 0);
        pill.setGravity(Gravity.CENTER);
        pill.setTextSize(14);
        pill.setSingleLine(true);
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        pill.setTag(segment);
        pill.setTextColor(0xFFFFFFFF);
        pill.setOnFocusChangeListener((v, focused) -> recolor(pill, segment));
        pill.setOnLongClickListener(v -> true); // consume a long press without selecting or opening a menu
        return new ViewHolder(pill);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, Object item) {
        Video chip = (Video) item;
        TextView pill = (TextView) holder.view;
        pill.setText(chip.getTitle());
        pill.setActivated(BrowsePresenter.instance(pill.getContext()).isHomeChipActive(chip));
        recolor(pill, Boolean.TRUE.equals(pill.getTag()));
    }

    @Override
    public void onUnbindViewHolder(ViewHolder holder) {
        ((TextView) holder.view).setText(null);
        holder.view.setActivated(false);
    }
}
