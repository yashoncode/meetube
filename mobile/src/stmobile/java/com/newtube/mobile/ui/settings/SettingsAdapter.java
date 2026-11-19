package com.newtube.mobile.ui.settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/** Renders a page's {@link SettingsRow}s. Clicks go back to the page, which applies them. */
final class SettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    /** Re-read the values (summaries, switch states) without rebuilding the views. */
    static final Object PAYLOAD_VALUES = new Object();

    interface Listener {
        void onRowClicked(@NonNull SettingsRow row);
    }

    private final Listener mListener;
    private List<SettingsRow> mRows = new ArrayList<>();

    SettingsAdapter(Listener listener) {
        mListener = listener;
    }

    void submit(List<SettingsRow> rows) {
        if (rows.size() == mRows.size() && sameShape(mRows, rows)) {
            mRows = rows;
            notifyItemRangeChanged(0, rows.size(), PAYLOAD_VALUES);
        } else {
            mRows = rows;
            notifyDataSetChanged();
        }
    }

    /** Same kinds and titles in the same places: a value changed, not the page. */
    private static boolean sameShape(List<SettingsRow> a, List<SettingsRow> b) {
        for (int i = 0; i < a.size(); i++) {
            SettingsRow x = a.get(i);
            SettingsRow y = b.get(i);
            if (x.kind != y.kind || !String.valueOf(x.title).equals(String.valueOf(y.title))) {
                return false;
            }
        }
        return true;
    }

    /** Where the row with this title is (a search result pointing at it), or -1. */
    int indexOf(@NonNull String title) {
        for (int i = 0; i < mRows.size(); i++) {
            SettingsRow row = mRows.get(i);
            if (row.kind != SettingsRow.KIND_HEADER && row.kind != SettingsRow.KIND_NOTE
                    && title.equals(String.valueOf(row.title))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        SettingsRow row = mRows.get(position);
        return row.kind == SettingsRow.KIND_CUSTOM && row.carded ? SettingsRow.KIND_CUSTOM_CARDED : row.kind;
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case SettingsRow.KIND_HEADER:
                return new TextHolder(inflater.inflate(R.layout.item_mobile_settings_header, parent, false));
            case SettingsRow.KIND_NOTE:
                return new NoteHolder(inflater.inflate(R.layout.item_mobile_settings_note, parent, false), parent);
            case SettingsRow.KIND_CUSTOM:
            case SettingsRow.KIND_CUSTOM_CARDED:
                return new RecyclerView.ViewHolder(new android.widget.FrameLayout(parent.getContext())) {};
            case SettingsRow.KIND_DIVIDER:
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_mobile_settings_divider, parent, false)) {};
            default:
                return new RowHolder(inflater.inflate(R.layout.item_mobile_settings_row, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SettingsRow row = mRows.get(position);
        if (holder instanceof NoteHolder) {
            NoteHolder note = (NoteHolder) holder;
            SettingsHint.bind(note.info, note.text, row.title, true, String.valueOf(row.title));
        } else if (holder instanceof TextHolder) {
            ((TextHolder) holder).text.setText(row.title);
        } else if (holder instanceof RowHolder) {
            ((RowHolder) holder).bind(row, mListener);
        } else if (row.kind == SettingsRow.KIND_CUSTOM && row.custom != null) {
            ViewGroup host = (ViewGroup) holder.itemView;
            host.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            // MEETUBE(tv install): an interactive factory can keep its view across onResume.
            View content = row.custom.create(host, mListener);
            if (host.getChildCount() != 1 || host.getChildAt(0) != content) {
                host.removeAllViews();
                host.addView(content);
            }
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        holder.itemView.setForeground(null); // a search result's highlight, if it was still glowing
    }


    private static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView text;

        TextHolder(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }
    }

    /** A note: its paragraph waits behind a small i, like a setting's explanation. */
    private static final class NoteHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final ImageView info;

        NoteHolder(@NonNull View note, @NonNull ViewGroup parent) {
            super(new android.widget.LinearLayout(parent.getContext()));
            android.widget.LinearLayout column = (android.widget.LinearLayout) itemView;
            column.setOrientation(android.widget.LinearLayout.VERTICAL);
            column.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            info = SettingsHint.icon(parent.getContext());
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) info.getLayoutParams();
            lp.setMarginStart(note.getPaddingStart() - info.getPaddingStart());
            lp.topMargin = lp.bottomMargin = Math.round(2 * parent.getResources().getDisplayMetrics().density);
            column.addView(info);
            text = (TextView) note;
            column.addView(text, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private android.content.res.ColorStateList mIconTint;
        private final TextView title;
        private final TextView summary;
        private final SwitchMaterial toggle;
        private final View chevron;
        private final ImageView info;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.settings_row_icon);
            title = itemView.findViewById(R.id.settings_row_title);
            summary = itemView.findViewById(R.id.settings_row_summary);
            toggle = itemView.findViewById(R.id.settings_row_switch);
            chevron = itemView.findViewById(R.id.settings_row_chevron);
            info = SettingsHint.attach(title);
        }

        void bind(SettingsRow row, Listener listener) {
            if (row.icon != 0) {
                icon.setImageResource(row.icon);
                // The coffee badge keeps its own colours; every other icon takes the row tint.
                if (mIconTint == null) {
                    mIconTint = icon.getImageTintList();
                }
                icon.setImageTintList(row.icon == R.drawable.ic_settings_coffee ? null : mIconTint);
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }

            title.setText(row.title);
            title.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    row.destructive ? R.color.mobile_color_primary : R.color.mobile_color_on_surface));

            CharSequence text = row.summaryText();
            SettingsHint.bind(info, summary, text, row.hint, String.valueOf(row.title));

            boolean isSwitch = row.kind == SettingsRow.KIND_SWITCH;
            toggle.setVisibility(isSwitch ? View.VISIBLE : View.GONE);
            chevron.setVisibility(row.kind == SettingsRow.KIND_LINK && row.page != null ? View.VISIBLE : View.GONE);
            if (isSwitch && row.checked != null) {
                boolean on = row.checked.getAsBoolean();
                if (toggle.isChecked() != on) {
                    toggle.setChecked(on);
                }
                itemView.setContentDescription(row.title + (text != null ? ", " + text : ""));
            } else {
                itemView.setContentDescription(null);
            }
            itemView.setAccessibilityDelegate(isSwitch ? SwitchRole.INSTANCE : null);

            boolean enabled = row.isEnabled();
            itemView.setEnabled(enabled);
            itemView.setAlpha(enabled ? 1f : 0.38f);
            toggle.setEnabled(enabled);
            itemView.setOnClickListener(enabled ? v -> listener.onRowClicked(row) : null);
            itemView.setClickable(enabled);
        }
    }

    /** TalkBack reads a switch row as a switch with its state, like a platform preference. */
    static final class SwitchRole extends View.AccessibilityDelegate {
        static final SwitchRole INSTANCE = new SwitchRole();

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            SwitchMaterial toggle = host.findViewById(R.id.settings_row_switch);
            info.setClassName(android.widget.Switch.class.getName());
            info.setCheckable(true);
            info.setChecked(toggle != null && toggle.isChecked());
        }
    }
}
