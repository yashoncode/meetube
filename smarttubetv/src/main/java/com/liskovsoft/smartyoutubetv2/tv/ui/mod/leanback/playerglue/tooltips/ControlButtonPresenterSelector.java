/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tooltips;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.leanback.R;
import androidx.leanback.widget.Action;
import androidx.leanback.widget.PlaybackControlsRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.PaddingAction;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.ThumbsUpAction;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.ThumbsDownAction;

/**
 * Displays primary and secondary controls for a {@link PlaybackControlsRow}.
 *
 * Binds to items of type {@link Action}.
 */
public class ControlButtonPresenterSelector extends PresenterSelector {
    private final ControlButtonPresenter mPrimaryPresenter =
            new ControlButtonPresenter(R.layout.lb_control_button_primary);
    private final ControlButtonPresenter mSecondaryPresenter =
            new ControlButtonPresenter(R.layout.lb_control_button_secondary);
    private final Presenter[] mPresenters = new Presenter[]{mPrimaryPresenter};

    public ControlButtonPresenterSelector(boolean tooltipsEnabled) {
        mPrimaryPresenter.tooltipsEnabled = mSecondaryPresenter.tooltipsEnabled = tooltipsEnabled;
    }

    /**
     * Returns the presenter for primary controls.
     */
    public Presenter getPrimaryPresenter() {
        return mPrimaryPresenter;
    }

    /**
     * Returns the presenter for secondary controls.
     */
    public Presenter getSecondaryPresenter() {
        return mSecondaryPresenter;
    }

    /**
     * Always returns the presenter for primary controls.
     */
    @Override
    public Presenter getPresenter(Object item) {
        return mPrimaryPresenter;
    }

    @Override
    public Presenter[] getPresenters() {
        return mPresenters;
    }

    static class ActionViewHolder extends Presenter.ViewHolder {
        ImageView mIcon;
        TextView mLabel;
        View mFocusableView;
        // MEETUBE: an overlay inside the existing button footprint; other controls keep their dimensions.
        TextView mCount;
        View.OnFocusChangeListener mTooltipFocusListener;
        final View.OnFocusChangeListener mCountFocusListener = (v, focused) -> {
            updateCountFocus();
            if (mTooltipFocusListener != null) {
                mTooltipFocusListener.onFocusChange(v, focused);
            }
        };

        public ActionViewHolder(View view) {
            super(view);
            mIcon = (ImageView) view.findViewById(R.id.icon);
            mLabel = (TextView) view.findViewById(R.id.label);
            mFocusableView = view.findViewById(R.id.button);
        }

        void bindCount(String count) {
            boolean hasCount = !TextUtils.isEmpty(count);
            if (hasCount && mCount == null) {
                float density = view.getResources().getDisplayMetrics().density;
                mCount = new TextView(view.getContext());
                mCount.setTextSize(10);
                mCount.setGravity(Gravity.CENTER);
                mCount.setSingleLine(true);
                mCount.setEllipsize(TextUtils.TruncateAt.END);
                mCount.setIncludeFontPadding(false);
                mCount.setPadding(Math.round(2 * density), 0, Math.round(2 * density), 0);
                mCount.setBackgroundResource(com.liskovsoft.smartyoutubetv2.tv.R.drawable.player_rating_badge);
                mCount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        mFocusableView.getLayoutParams().width, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                params.bottomMargin = Math.round(1 * density);
                ((FrameLayout) view).addView(mCount, params);
            }
            if (mCount != null) {
                mCount.setText(count);
                mCount.setVisibility(hasCount ? View.VISIBLE : View.GONE);
                mIcon.setTranslationY(hasCount ? -6 * view.getResources().getDisplayMetrics().density : 0);
                // Tooltips replace the focus listener on rebind; chain it after they have installed it.
                View.OnFocusChangeListener listener = mFocusableView.getOnFocusChangeListener();
                if (listener != mCountFocusListener) {
                    mTooltipFocusListener = listener;
                    mFocusableView.setOnFocusChangeListener(mCountFocusListener);
                }
                updateCountFocus();
            }
        }

        private void updateCountFocus() {
            if (mCount != null) {
                boolean focused = mFocusableView.hasFocus();
                mCount.setActivated(focused);
                mCount.setTextColor(focused ? 0xFF141414 : 0xFFFFFFFF);
            }
        }
    }

    public static class ControlButtonPresenter extends Presenter {
        private int mLayoutResourceId;
        private boolean tooltipsEnabled;

        ControlButtonPresenter(int layoutResourceId) {
            mLayoutResourceId = layoutResourceId;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(mLayoutResourceId, parent, false);
            return new ActionViewHolder(v);
        }

        // Used inside: com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tweaks.ControlBarPresenter.ViewHolder.bindControlToAction()
        // Restore focus: com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tweaks.ControlBar.onRequestFocusInDescendants()
        @Override
        public void onBindViewHolder(ViewHolder viewHolder, Object item) {
            Action action = (Action) item;
            ActionViewHolder vh = (ActionViewHolder) viewHolder;

            vh.mIcon.setImageDrawable(action.getIcon());
            if (action instanceof PaddingAction) {
                int padding = ((PaddingAction) action).getPadding();
                if (padding > 0) {
                    vh.mIcon.setPadding(padding, padding, padding, padding);
                }
            }
            if (vh.mLabel != null) {
                if (action.getIcon() == null) {
                    vh.mLabel.setText(action.getLabel1());
                } else {
                    vh.mLabel.setText(null);
                }
            }
            // MEETUBE: only the two rating actions carry an always-visible count.
            String count = action instanceof ThumbsUpAction ? ((ThumbsUpAction) action).getCount() :
                    action instanceof ThumbsDownAction ? ((ThumbsDownAction) action).getCount() : null;
            CharSequence contentDescription = TextUtils.isEmpty(action.getLabel2())
                    ? action.getLabel1() : action.getLabel2();
            if (!TextUtils.isEmpty(count)) {
                contentDescription = TextUtils.concat(contentDescription, " ", count);
            }
            if (!TextUtils.equals(vh.mFocusableView.getContentDescription(), contentDescription)) {
                vh.mFocusableView.setContentDescription(contentDescription);
                vh.mFocusableView.sendAccessibilityEvent(
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);

                // MOD: enable control tooltips
                if (tooltipsEnabled) {
                    TooltipCompatHandler.setTooltipText(vh.mFocusableView, action.getLabel1());
                }
            }
            vh.bindCount(count);
        }

        @Override
        public void onUnbindViewHolder(ViewHolder viewHolder) {
            ActionViewHolder vh = (ActionViewHolder) viewHolder;
            vh.bindCount(null); // MEETUBE: recycled controls must lose the previous video's badge.
            vh.mIcon.setImageDrawable(null);
            if (vh.mLabel != null) {
                vh.mLabel.setText(null);
            }
            vh.mFocusableView.setContentDescription(null);
        }

        @Override
        public void setOnClickListener(ViewHolder viewHolder,
                                       View.OnClickListener listener) {
            ((ActionViewHolder) viewHolder).mFocusableView.setOnClickListener(listener);
        }

        public void setOnLongClickListener(ViewHolder viewHolder,
                                       View.OnLongClickListener listener) {
            ((ActionViewHolder) viewHolder).mFocusableView.setOnLongClickListener(listener);
        }
    }
}
