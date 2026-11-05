package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.browse;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.SearchOrbView;
import androidx.leanback.widget.SearchOrbView.Colors;
import androidx.leanback.widget.TitleView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.locale.LocaleUtility;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.LanguageSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.AccountChangeListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tooltips.TooltipCompatHandler;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.search.LongClickSearchOrbView;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.time.DateTimeView;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.Locale;

import static androidx.leanback.widget.TitleViewAdapter.BRANDING_VIEW_VISIBLE;
import static androidx.leanback.widget.TitleViewAdapter.FULL_VIEW_VISIBLE;
import static androidx.leanback.widget.TitleViewAdapter.SEARCH_VIEW_VISIBLE;

/**
 * View that supports dpad navigation between children<br/>
 * NOTE: You should set android:nextFocusLeft and android:nextFocusRight<br/>
 * https://stackoverflow.com/questions/38169378/use-multiple-orb-buttons-or-other-buttons-in-the-leanbacks-title-view<br/>
 * https://stackoverflow.com/questions/40802470/add-button-to-browsefragment
 */
public class NavigateTitleView extends TitleView implements OnDataChange, AccountChangeListener {
    private LongClickSearchOrbView mAccountView;
    private SearchOrbView mLanguageView;
    private SearchOrbView mExitPip;
    private TextView mPipTitle;
    private int mSearchVisibility = View.INVISIBLE;
    private int mBrandingVisibility = View.INVISIBLE;
    private DateTimeView mGlobalClock;
    private DateTimeView mGlobalDate;
    private SearchOrbView mSearchOrbView;
    private View.OnClickListener mOnSettingsClickedListener;
    private boolean mInitDone;
    private int mFlags = FULL_VIEW_VISIBLE;
    private int mIconWidth;
    private int mIconHeight;
    private boolean mIsSearchOrbEnabled;
    private boolean mIsAccountViewEnabled;
    private boolean mIsLanguageViewEnabled;
    private boolean mIsGlobalClockEnabled;
    private boolean mBrowseTitleEnabled; // MEETUBE: shared title layouts keep the greeting browse-only.

    public NavigateTitleView(Context context) {
        super(context);
    }

    public NavigateTitleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NavigateTitleView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // findViewById is null in constructor. Init mAccountView later.
    }

    @Override
    public View focusSearch(View focused, int direction) {
        View nextFoundFocusableViewInLayout = null;

        // Only concerned about focusing left and right at the moment
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {

            // Try to find the next focusable item in this layout for the supplied direction
            int nextFoundFocusableViewInLayoutId = -1;
            switch(direction) {
                case View.FOCUS_LEFT :
                    nextFoundFocusableViewInLayoutId = focused.getNextFocusLeftId();
                    break;
                case View.FOCUS_RIGHT :
                    nextFoundFocusableViewInLayoutId = focused.getNextFocusRightId();
                    break;
            }

            // View id for next focus direction found....get the View
            if (nextFoundFocusableViewInLayoutId != -1) {
                nextFoundFocusableViewInLayout = findViewById(nextFoundFocusableViewInLayoutId);
            }
        }

        //  Return the found View in the layout if it's focusable
        if (nextFoundFocusableViewInLayout != null && nextFoundFocusableViewInLayout != focused && nextFoundFocusableViewInLayout.isFocusable()) {
            if (nextFoundFocusableViewInLayout.getVisibility() == View.VISIBLE) {
                return nextFoundFocusableViewInLayout;
            } else {
                return focusSearch(nextFoundFocusableViewInLayout, direction);
            }
        } else {
            // No focusable view found in layout...propagate to super (should invoke the BrowseFrameLayout.OnFocusSearchListener
            return super.focusSearch(focused, direction);
        }
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // Gives focus to the SearchOrb first....if not...default to normal descendant focus search
        return getSearchAffordanceView().requestFocus() || super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }

    public void setBrowseTitleEnabled(boolean enabled) {
        mBrowseTitleEnabled = enabled;
        if (enabled) {
            // MEETUBE: the sidebar slides orbs to physical left, including in RTL; branding stays right.
            ((FrameLayout.LayoutParams) findViewById(R.id.title_orbs).getLayoutParams()).gravity =
                    Gravity.CENTER_VERTICAL | Gravity.LEFT;
            ((FrameLayout.LayoutParams) findViewById(R.id.title_branding).getLayoutParams()).gravity =
                    Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        }
        updateGreeting();
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mBrowseTitleEnabled) {
            // MEETUBE: reserve the orb row's full unshifted width, including while the sidebar slides.
            View branding = findViewById(R.id.title_branding);
            View orbs = findViewById(R.id.title_orbs);
            int gap = Math.round(12 * getResources().getDisplayMetrics().density);
            int available = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight() -
                    orbs.getMeasuredWidth() - gap);
            branding.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(getResources().getDimensionPixelSize(R.dimen.lb_search_orb_size),
                            MeasureSpec.EXACTLY));
        }
    }

    // MEETUBE: the selected account supplies a first name independently of account-orb visibility.
    private void updateGreeting() {
        TextView greeting = findViewById(R.id.title_greeting);
        if (greeting == null) {
            return;
        }
        Account account = MediaServiceManager.instance().getSelectedAccount();
        String name = account != null && !account.isEmpty() ? account.getName() : null;
        name = name != null ? name.trim() : null;
        if (!mBrowseTitleEnabled || !MainUIData.instance(getContext()).isTitleGreetingEnabled() ||
                name == null || name.isEmpty()) {
            greeting.setVisibility(View.GONE);
            return;
        }
        String[] words = name.split("\\s+", 2);
        String firstName = words.length > 0 && !words[0].isEmpty() ? words[0] : name;
        greeting.setText(getContext().getString(R.string.greeting_hey, firstName));
        greeting.setVisibility(mBrandingVisibility == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    public void setOnSettingsClickedListener(View.OnClickListener listener) {
        mOnSettingsClickedListener = listener;
        SearchOrbView settings = findViewById(R.id.settings_orb);
        settings.setOnOrbClickedListener(listener);
        settings.setOrbColors(new Colors(0x33FFFFFF, 0x66FFFFFF, Color.WHITE));
        TooltipCompatHandler.setTooltipText(settings, getContext().getString(R.string.header_settings));
        updateSettingsVisibility();
    }

    private void updateSettingsVisibility() {
        SearchOrbView settings = findViewById(R.id.settings_orb);
        boolean enabled = mOnSettingsClickedListener != null && SidebarService.instance(getContext()).isSettingsSectionEnabled();
        settings.setVisibility(enabled ? mSearchVisibility : View.GONE);
        // MEETUBE: hidden Settings leaves the shared title layout and focus chain unchanged.
        View search = getSearchAffordanceView();
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            search.setNextFocusRightId(enabled ? R.id.settings_orb : View.NO_ID);
        } else {
            search.setNextFocusLeftId(enabled ? R.id.settings_orb : View.NO_ID);
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) search.getLayoutParams();
        params.setMarginStart(enabled ? getResources().getDimensionPixelSize(R.dimen.search_bar_right_items_margin) : 0);
        search.setLayoutParams(params);
    }

    @Override
    public void updateComponentsVisibility(int flags) {
        // Fix for: Fatal Exception: java.lang.IllegalStateException
        // Fragment has not been attached yet.
        // Inside: super.updateComponentsVisibility(flags);
        if (getWindowToken() == null) {
            return;
        }

        super.updateComponentsVisibility(flags);

        init();

        mFlags = flags;

        mSearchVisibility = (flags & SEARCH_VIEW_VISIBLE) == SEARCH_VIEW_VISIBLE
                ? View.VISIBLE : View.INVISIBLE;
        updateSettingsVisibility();

        mBrandingVisibility = (flags & BRANDING_VIEW_VISIBLE) == BRANDING_VIEW_VISIBLE
                ? View.VISIBLE : View.INVISIBLE;

        if (mIsSearchOrbEnabled) {
            mSearchOrbView.setVisibility(View.GONE);
        }

        if (mIsAccountViewEnabled) {
            mAccountView.setVisibility(mSearchVisibility);
        }

        if (mIsLanguageViewEnabled) {
            mLanguageView.setVisibility(mSearchVisibility);
        }

        if (mExitPip != null && (PlaybackPresenter.instance(getContext()).isRunningInBackground() || mSearchVisibility != View.VISIBLE)) {
            mExitPip.setVisibility(mSearchVisibility);
            mPipTitle.setVisibility(mSearchVisibility);
        }

        if (mIsGlobalClockEnabled) {
            mGlobalClock.setVisibility(mBrandingVisibility);
        }

        if (mIsGlobalClockEnabled) {
            mGlobalDate.setVisibility(mBrandingVisibility);
        }
        updateGreeting(); // MEETUBE: follow the branding visibility when browsing or scrolling.
    }

    private void init() {
        if (mInitDone) {
            return;
        }

        MediaServiceManager.instance().addAccountListener(this);

        setupButtons();

        MainUIData mainUIData = MainUIData.instance(getContext());
        mainUIData.setOnChange(this);

        mInitDone = true;
    }

    private void setupButtons() {
        MainUIData mainUIData = MainUIData.instance(getContext());

        mSearchOrbView = findViewById(R.id.title_orb);

        mAccountView = findViewById(R.id.account_orb);
        mAccountView.setOnOrbClickedListener(v -> AccountSelectionPresenter.instance(getContext()).nextAccountOrDialog());
        mAccountView.setOnOrbLongClickedListener(v -> {
            AccountSettingsPresenter.instance(getContext()).show();
            return true;
        });
        TooltipCompatHandler.setTooltipText(mAccountView, getContext().getString(R.string.settings_accounts));

        mLanguageView = findViewById(R.id.language_orb);
        mLanguageView.setOnOrbClickedListener(v -> LanguageSettingsPresenter.instance(getContext()).show());
        TooltipCompatHandler.setTooltipText(mLanguageView, getContext().getString(R.string.settings_language_country));

        mExitPip = findViewById(R.id.exit_pip);
        mPipTitle = findViewById(R.id.pip_title);
        mExitPip.setOnOrbClickedListener(v -> ViewManager.instance(getContext()).startView(PlaybackView.class));
        ViewUtil.enableMarquee(mPipTitle);
        ViewUtil.setTextScrollSpeed(mPipTitle, mainUIData.getCardTextScrollSpeed());
        TooltipCompatHandler.setTooltipText(mExitPip, getContext().getString(R.string.return_to_background_video));

        mGlobalClock = findViewById(R.id.global_time);
        mGlobalClock.showDate(false);

        mGlobalDate = findViewById(R.id.global_date);
        mGlobalDate.showTime(false);
        mGlobalDate.showDate(true);

        updateButtonsVisibility();
    }

    private void updateButtonsVisibility() {
        MainUIData mainUIData = MainUIData.instance(getContext());

        mIsSearchOrbEnabled = !mainUIData.isTopButtonEnabled(MainUIData.TOP_BUTTON_SEARCH);
        mIsAccountViewEnabled = mainUIData.isTopButtonEnabled(MainUIData.TOP_BUTTON_BROWSE_ACCOUNTS);
        mIsLanguageViewEnabled = mainUIData.isTopButtonEnabled(MainUIData.TOP_BUTTON_CHANGE_LANGUAGE);
        mIsGlobalClockEnabled = GeneralData.instance(getContext()).isGlobalClockEnabled();

        mSearchOrbView.setVisibility(mIsSearchOrbEnabled ? View.VISIBLE : View.GONE);
        mAccountView.setVisibility(mIsAccountViewEnabled ? View.VISIBLE : View.GONE);
        mLanguageView.setVisibility(mIsLanguageViewEnabled ? View.VISIBLE : View.GONE);
        mGlobalClock.setVisibility(mIsGlobalClockEnabled ? View.VISIBLE : View.GONE);
        mGlobalDate.setVisibility(mIsGlobalClockEnabled ? View.VISIBLE : View.GONE);

        Utils.postDelayed(this::updateAccountIcon, 1_000); // give a time to engine to fetch an updated icon url
        //updateAccountIcon();
        updateLanguageIcon();
        updateGreeting(); // MEETUBE: MainUIData changes refresh the switch immediately.
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (visibility == View.VISIBLE) { // scroll grid up, scroll grid down
            applyPipParameters();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (hasWindowFocus) { // pip window closed, dialog closed
            applyPipParameters();
            updateSettingsVisibility();
        }
    }

    @Override
    public void onAccountChanged(Account account) {
        updateAccountIcon();
        updateGreeting(); // MEETUBE: sign-in, sign-out and account switching refresh the name.
    }

    private void applyPipParameters() {
        if (mExitPip != null) {
            int newVisibility = PlaybackPresenter.instance(getContext()).isRunningInBackground() ? mSearchVisibility : View.INVISIBLE;
            mExitPip.setVisibility(newVisibility);
            mPipTitle.setVisibility(newVisibility);

            if (newVisibility == View.VISIBLE) {
                Video video = PlaybackPresenter.instance(getContext()).getVideo();
                mPipTitle.setText(video != null ? String.format("%s - %s", video.getTitle(), video.getAuthor()) : "");
            }
        }
    }

    private void updateAccountIcon() {
        if (!mIsAccountViewEnabled) {
            return;
        }

        Account current = MediaServiceManager.instance().getSelectedAccount();

        if (current != null && current.getAvatarImageUrl() != null) {
            loadIcon(mAccountView, current.getAvatarImageUrl(), false);
            String accountName = current.getName() != null ? current.getName() : current.getEmail();
            //TooltipCompatHandler.setTooltipText(mAccountView, Utils.updateTooltip(getContext(), accountName));
            TooltipCompatHandler.setTooltipText(mAccountView, accountName);
        } else {
            Colors orbColors = mAccountView.getOrbColors();
            mAccountView.setOrbColors(new Colors(orbColors.color, orbColors.brightColor, ContextCompat.getColor(getContext(), R.color.orb_icon_color)));
            mAccountView.setOrbIcon(ContextCompat.getDrawable(getContext(), R.drawable.browse_title_account));
            TooltipCompatHandler.setTooltipText(mAccountView, getContext().getString(R.string.dialog_account_none));
        }
    }

    private void updateLanguageIcon() {
        if (!mIsLanguageViewEnabled) {
            return;
        }

        // Use delay to fix icon initialization on app boot
        Utils.postDelayed(() -> {
            Locale locale = LocaleUtility.getCurrentLocale(getContext());
            loadIcon(mLanguageView, Utils.getCountryFlagUrl(locale.getCountry()), true); // flag server could be down
            TooltipCompatHandler.setTooltipText(mLanguageView, String.format("%s (%s)", locale.getDisplayCountry(), locale.getDisplayLanguage()));
        }, 100);
    }

    private void loadIcon(SearchOrbView view, String url, boolean useCache) {
        if (view == null) {
            return;
        }

        // The view with GONE visibility has zero width and height
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            Utils.postDelayed(() -> loadIcon(view, url, useCache), 500);
            return;
        }

        Context context = view.getContext();

        if (context instanceof Activity && !Utils.checkActivity((Activity) context)) {
            return;
        }

        // Size of the view might increase after icon change (bug on some firmwares). So, it's better to cache initial values.
        if (mIconWidth == 0 || mIconHeight == 0) {
            mIconWidth = view.getWidth();
            mIconHeight = view.getHeight();
        }

        try {
            loadIcon(context, view, url, mIconWidth, mIconHeight, useCache);
        } catch (ExceptionInInitializerError e) {
            // Glide Kivi error
            e.printStackTrace();
        }
    }

    private static void loadIcon(Context context, SearchOrbView view, String url, int iconWidth, int iconHeight, boolean useCache) {
        Glide.with(context)
                .load(url)
                .apply(ViewUtil.glideOptions())
                .diskCacheStrategy(useCache ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE)
                .circleCrop() // resize image
                .into(new SimpleTarget<Drawable>(iconWidth, iconHeight) {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        Colors orbColors = view.getOrbColors();
                        view.setOrbColors(new Colors(orbColors.color, orbColors.brightColor, Color.TRANSPARENT));
                        view.setOrbIcon(resource);
                    }
                });
    }

    @Override
    public void onDataChange() {
        try {
            updateButtonsVisibility();
            updateComponentsVisibility(mFlags);
        } catch (IllegalStateException e) {
            // Fragment BrowseFragment has not been attached yet.
            e.printStackTrace();
        }
    }
}
