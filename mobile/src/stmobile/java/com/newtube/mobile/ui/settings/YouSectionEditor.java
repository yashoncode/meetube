package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.NavTabs;
import com.newtube.mobile.ui.common.Haptics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MEETUBE(you order): Library and Explore use BarEditor's drag cards, with their existing toggles. */
final class YouSectionEditor extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final Context mContext;
    private final boolean mLibrary;
    private final List<String> mItems = new ArrayList<>();
    private final Map<String, SettingsRow> mRows = new HashMap<>();
    private final ItemTouchHelper mTouchHelper = new ItemTouchHelper(new Drag());

    static SettingsRow row(Context context, boolean library) {
        return SettingsRow.custom(context.getString(library ? R.string.mobile_you_library : R.string.mobile_you_explore),
                (parent, page) -> create(parent, library));
    }

    private static View create(ViewGroup parent, boolean library) {
        Context context = parent.getContext();
        RecyclerView list = new RecyclerView(context);
        list.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setNestedScrollingEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipChildren(false);
        YouSectionEditor editor = new YouSectionEditor(context, library);
        list.setAdapter(editor);
        editor.mTouchHelper.attachToRecyclerView(list);
        return list;
    }

    private YouSectionEditor(Context context, boolean library) {
        mContext = context;
        mLibrary = library;
        SidebarService sidebar = SidebarService.instance(context);
        Map<Integer, Integer> sections = sidebar.getDefaultSections();
        List<String> libraryOrder = NavTabs.libraryOrder(context);
        if (library) {
            for (String key : libraryOrder) {
                if ("WL".equals(key) || "LL".equals(key)) {
                    add(key, SettingsRow.toggle(context.getString("WL".equals(key)
                                    ? R.string.mobile_you_watch_later : R.string.mobile_you_liked_videos), null,
                            () -> NavTabs.showLibraryPlaylist(context, key),
                            on -> NavTabs.setShowLibraryPlaylist(context, key, on)));
                } else {
                    int sectionId = Integer.parseInt(key);
                    for (Map.Entry<Integer, Integer> section : sections.entrySet()) {
                        if (section.getValue() == sectionId) {
                            addSection(sidebar, section.getKey(), sectionId);
                            break;
                        }
                    }
                }
            }
        } else {
            for (Map.Entry<Integer, Integer> section : sections.entrySet()) {
                int sectionId = section.getValue();
                if (libraryOrder.contains(String.valueOf(sectionId)) || sectionId == MediaGroup.TYPE_SETTINGS
                        || sectionId == MediaGroup.TYPE_SHORTS
                        || sectionId == MediaGroup.TYPE_CHANNEL_UPLOADS || sectionId == MediaGroup.TYPE_HOME
                        || sectionId == MediaGroup.TYPE_HISTORY) {
                    continue;
                }
                addSection(sidebar, section.getKey(), sectionId);
            }
            List<Integer> choices = new ArrayList<>();
            for (String key : mItems) {
                choices.add(Integer.parseInt(key));
            }
            List<Integer> order = sidebar.getSectionOrder(choices);
            Collections.sort(mItems, (a, b) -> Integer.compare(order.indexOf(Integer.parseInt(a)),
                    order.indexOf(Integer.parseInt(b))));
        }
    }

    private void addSection(SidebarService sidebar, int titleRes, int sectionId) {
        add(String.valueOf(sectionId), SettingsRow.toggle(mContext.getString(AppPages.sectionTitle(titleRes)), null,
                () -> sidebar.isSectionPinned(sectionId),
                on -> BrowsePresenter.instance(mContext).enableSection(sectionId, on)));
    }

    private void add(String key, SettingsRow row) {
        mItems.add(key);
        mRows.put(key, row);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_you_section_editor, parent, false)) {};
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SettingsRow row = mRows.get(mItems.get(position));
        View view = holder.itemView;
        ((TextView) view.findViewById(R.id.settings_row_title)).setText(row.title);
        SwitchMaterial toggle = view.findViewById(R.id.settings_row_switch);
        toggle.setChecked(row.checked.getAsBoolean());
        view.setContentDescription(row.title);
        view.setAccessibilityDelegate(SettingsAdapter.SwitchRole.INSTANCE);
        view.setOnClickListener(v -> {
            row.toggle.set(!row.checked.getAsBoolean());
            toggle.setChecked(row.checked.getAsBoolean());
            if (!mLibrary) {
                save(); // MEETUBE(you order): a newly enabled section takes its place in this list.
            }
        });
        view.findViewById(R.id.you_section_grip).setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                mTouchHelper.startDrag(holder);
            }
            return true;
        });
    }

    private void save() {
        if (mLibrary) {
            List<String> order = NavTabs.libraryOrder(mContext);
            int next = 0;
            for (int i = 0; i < order.size(); i++) {
                if (mItems.contains(order.get(i))) {
                    order.set(i, mItems.get(next++));
                }
            }
            NavTabs.setLibraryOrder(mContext, order);
            return;
        }

        SidebarService sidebar = SidebarService.instance(mContext);
        List<Integer> order = new ArrayList<>();
        for (String key : mItems) {
            order.add(Integer.parseInt(key));
        }
        sidebar.setSectionOrder(order);
        // MEETUBE(you order): the section menu uses the same sidebar moves followed by this live refresh.
        BrowsePresenter.instance(mContext).updateSections();
    }

    private final class Drag extends ItemTouchHelper.Callback {
        private boolean mMoved;

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            return makeMovementFlags(holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION
                    ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = holder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                return false;
            }
            mItems.add(to, mItems.remove(from));
            notifyItemMoved(from, to);
            mMoved = true;
            Haptics.tick(recyclerView);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
            super.onSelectedChanged(holder, actionState);
            if (holder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                Haptics.longPress(holder.itemView);
                holder.itemView.animate().scaleX(1.05f).scaleY(1.05f)
                        .translationZ(14 * mContext.getResources().getDisplayMetrics().density)
                        .setDuration(160).setInterpolator(new OvershootInterpolator(2f)).start();
                ((ImageView) holder.itemView.findViewById(R.id.you_section_grip)).setColorFilter(
                        ContextCompat.getColor(mContext, R.color.mobile_color_primary));
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            Haptics.click(holder.itemView);
            holder.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0)
                    .setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
            ((ImageView) holder.itemView.findViewById(R.id.you_section_grip)).clearColorFilter();
            if (mMoved) {
                mMoved = false;
                save();
            }
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }
    }
}
