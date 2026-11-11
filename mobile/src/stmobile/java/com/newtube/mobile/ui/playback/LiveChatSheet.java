package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.web.WebSessionException;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileSheets;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.signin.MobileWebSignInActivity;
import com.newtube.mobile.ui.signin.WebSignInSheet;
import com.newtube.mobile.ui.signin.WebSignInState;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Live-chat panel for the touch watch page. The hosting Activity owns the actual chat stream (it
 * receives the SmartTube {@code ChatReceiver} pushed by the reused {@code ChatController}); this
 * sheet just seeds itself from the current snapshot and appends new messages as they arrive via the
 * {@link Host} callback, auto-scrolling to the newest. Best-effort: on a non-live video the Activity
 * never shows the chat entry, so this sheet is only ever opened when a chat stream is active.
 */
public class LiveChatSheet extends BottomSheetDialogFragment {

    private static final String TAG_SHEET = "mobile_live_chat_sheet";

    /** Implemented by the hosting Activity to expose the live-chat stream to the sheet. */
    public interface Host {
        List<ChatItem> getChatSnapshot();
        @Nullable Video getChatVideo();
        void onChatMessageSent(ChatItem item);
        void registerChatObserver(Observer observer);
        void unregisterChatObserver(Observer observer);
        /** The panel was closed by the user: stop the live-chat stream the Activity opened for it. */
        void onChatSheetDismissed();
        /** The upright page's video, which the panel stays under; null sideways (a side column). */
        @Nullable View getChatVideoArea();
        /** Sideways, the video steps aside for the column this wide (px); 0 gives it the room back. */
        void setChatColumn(int widthPx);
    }

    public interface Observer {
        void onChatItem(ChatItem item);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return Haptics.watch(super.onCreateDialog(savedInstanceState)); // MEETUBE(haptics)
    }

    private LiveChatAdapter mAdapter;
    private RecyclerView mList;
    private TextView mEmpty;
    private Host mHost;
    private View mComposer;
    private EditText mInput;
    private ImageButton mSend;
    private android.widget.ImageView mAccount;
    private View mSendProgress;
    private Disposable mSendAction;
    private boolean mSending;
    private String mVideoId;
    private String mDraft = "";
    private Snackbar mFeedback;

    private final ActivityResultLauncher<Intent> mWebSignIn = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && mInput != null) {
                    bindComposer();
                    if (!isLiveChat()) return;
                    mInput.requestFocus();
                    mInput.post(() -> {
                        if (mInput == null) return;
                        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
                    });
                }
            });

    private final Observer mObserver = new Observer() {
        @Override
        public void onChatItem(ChatItem item) {
            if (mList == null) {
                return;
            }
            mList.post(() -> appendMessage(item));
        }
    };

    public static void show(@NonNull androidx.fragment.app.FragmentManager fm) {
        if (fm.findFragmentByTag(TAG_SHEET) != null) {
            return; // already open: a second panel stacked a second window on the first
        }
        new LiveChatSheet().show(fm, TAG_SHEET);
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof Host) {
            mHost = (Host) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_mobile_live_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.chat_sheet_close).setOnClickListener(v -> dismissAllowingStateLoss());

        mEmpty = view.findViewById(R.id.chat_sheet_empty);
        mList = view.findViewById(R.id.chat_sheet_list);

        mAdapter = new LiveChatAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true); // newest at the bottom, list starts pinned to the end
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);
        Video video = mHost != null ? mHost.getChatVideo() : null;
        mVideoId = video != null ? video.videoId : null;
        if (savedInstanceState != null) mDraft = savedInstanceState.getString("chat_draft", "");
        installComposer(view);

        if (mHost != null) {
            List<ChatItem> snapshot = mHost.getChatSnapshot();
            mAdapter.setItems(snapshot);
            if (snapshot != null && !snapshot.isEmpty()) {
                mEmpty.setVisibility(View.GONE);
                mList.scrollToPosition(mAdapter.getItemCount() - 1);
            }
            mHost.registerChatObserver(mObserver);
        }
    }

    private void installComposer(View root) {
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        mComposer = root.findViewById(R.id.chat_sheet_composer);
        mInput = mComposer.findViewById(R.id.comment_composer_input);
        mSend = mComposer.findViewById(R.id.comment_composer_send);
        mSendProgress = mComposer.findViewById(R.id.comment_composer_progress);
        // The avatar slot is the web account switch (shown once signed in, in bindComposer).
        mAccount = mComposer.findViewById(R.id.comment_composer_avatar);
        mAccount.setContentDescription(getString(R.string.mobile_web_signin_switch));
        mAccount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        mAccount.setOnClickListener(v -> WebSignInSheet.pickAccount(requireActivity(), this::bindComposer,
                () -> mWebSignIn.launch(WebSignInSheet.addAccountIntent(requireActivity()))));
        mInput.setMaxLines(3);
        mInput.setText(mDraft);
        mInput.setFilters(new InputFilter[] {(source, start, end, dest, dstart, dend) ->
                mSending ? dest.subSequence(dstart, dend) : null, new InputFilter.LengthFilter(200)});
        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateSend(); }
        });
        mInput.setOnClickListener(v -> { if (!WebSignInState.isSignedIn(requireContext())) requestWebSignIn(); });
        mComposer.setOnClickListener(v -> { if (!WebSignInState.isSignedIn(requireContext())) requestWebSignIn(); });
        mSend.setContentDescription(getString(R.string.mobile_chat_send));
        mSend.setOnClickListener(v -> sendMessage());
        // No keyboard handling of our own: the bottom sheet already pads itself by the keyboard,
        // and a margin on top of that squeezed the list and this bar out of the sheet.
        bindComposer();
    }

    private boolean isLiveChat() {
        Video video = mHost != null ? mHost.getChatVideo() : null;
        return video != null && video.isLive && !video.isLiveEnd
                && mVideoId != null && mVideoId.equals(video.videoId);
    }

    @Override public void onResume() {
        super.onResume();
        bindComposer();
    }

    private void bindComposer() {
        if (mComposer == null) return;
        mComposer.setVisibility(isLiveChat() ? View.VISIBLE : View.GONE);
        boolean signedIn = WebSignInState.isSignedIn(requireContext());
        mInput.setHint(signedIn ? getString(R.string.mobile_chat_as, WebSignInState.accountName(requireContext()))
                : getString(R.string.mobile_chat_hint));
        mInput.setFocusable(signedIn);
        mInput.setFocusableInTouchMode(signedIn);
        mInput.setCursorVisible(signedIn);
        mAccount.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) WebSignInSheet.loadAvatar(requireActivity(), mAccount, WebSignInState.accountPhoto(requireContext()));
        View pill = (View) mInput.getParent().getParent();
        ViewGroup.MarginLayoutParams pillParams = (ViewGroup.MarginLayoutParams) pill.getLayoutParams();
        int start = signedIn ? Math.round(12 * getResources().getDisplayMetrics().density) : 0;
        if (pillParams.getMarginStart() != start) {
            pillParams.setMarginStart(start);
            pill.setLayoutParams(pillParams);
        }
        updateSend();
    }

    private void updateSend() {
        if (mSend == null) return;
        boolean ready = !mSending && (!WebSignInState.isSignedIn(requireContext())
                || !mInput.getText().toString().trim().isEmpty());
        mInput.setEnabled(!mSending);
        mSend.setEnabled(ready);
        mSend.setColorFilter(ContextCompat.getColor(requireContext(), ready
                ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
        mSend.setAlpha(ready ? 1f : 0.5f);
        mSend.setVisibility(mSending ? View.INVISIBLE : View.VISIBLE);
        mSendProgress.setVisibility(mSending ? View.VISIBLE : View.GONE);
    }

    private void requestWebSignIn() {
        WebSignInSheet.show(requireActivity(), () -> mWebSignIn.launch(
                new Intent(requireContext(), MobileWebSignInActivity.class)));
    }

    private void sendMessage() {
        if (mSending || !isLiveChat()) return;
        if (!WebSignInState.isSignedIn(requireContext())) {
            requestWebSignIn();
            return;
        }
        String text = mInput.getText().toString().trim();
        if (text.isEmpty()) return;
        Video video = mHost.getChatVideo();
        if (video == null || video.channelId == null || video.channelId.isEmpty()) {
            showChatError(getString(R.string.mobile_chat_not_ready), false);
            return;
        }
        mSending = true;
        updateSend();
        mSendAction = YouTubeServiceManager.instance().getLiveChatService()
                .sendLiveChatMessageObserve(mVideoId, video.channelId, text)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    mSending = false;
                    updateSend();
                    if (!result.isSuccess()) {
                        showChatError(result.getError(), false);
                        return;
                    }
                    mInput.setText("");
                    // YouTube often answers without the message itself, and Top chat may never
                    // show it: echo it from what was typed, as YouTube's own page does.
                    ChatItem sent = result.getItem() != null ? result.getItem() : ownMessage(text);
                    if (isLiveChat()) mHost.onChatMessageSent(sent);
                }, error -> {
                    mSending = false;
                    boolean expired = error instanceof WebSessionException
                            && (((WebSessionException) error).getKind() == WebSessionException.Kind.REJECTED
                            || ((WebSessionException) error).getKind() == WebSessionException.Kind.NOT_SIGNED_IN);
                    if (expired) WebSignInState.clear(requireContext());
                    bindComposer();
                    showChatError(getString(expired ? R.string.mobile_web_signin_expired
                            : R.string.mobile_chat_send_failed), expired);
                });
    }

    private ChatItem ownMessage(String text) {
        String name = WebSignInState.accountName(requireContext());
        String photo = WebSignInState.accountPhoto(requireContext());
        return new ChatItem() {
            @Override public String getId() { return null; }
            @Override public String getMessage() { return text; }
            @Override public String getAuthorName() { return name; }
            @Override public String getAuthorPhoto() { return photo; }
        };
    }

    private void showChatError(String text, boolean expired) {
        if (mFeedback != null) mFeedback.dismiss();
        mFeedback = Snackbar.make(requireView(), text, Snackbar.LENGTH_LONG).setAnchorView(mComposer);
        if (expired) mFeedback.setAction(R.string.mobile_web_signin_google, v -> requestWebSignIn());
        MobileSnackbar.style(requireActivity(), mFeedback);
        mFeedback.show();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("chat_draft", mInput != null ? mInput.getText().toString() : mDraft);
        super.onSaveInstanceState(state);
    }

    private void appendMessage(ChatItem item) {
        if (mAdapter == null) {
            return;
        }
        bindComposer();
        int count = mAdapter.addItem(item);
        if (count > 0) {
            mEmpty.setVisibility(View.GONE);
            mList.scrollToPosition(count - 1);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = requireDialog().getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        placeBesideVideo();
        // The player rotates in place (configChanges) and its bars come and go after that: the
        // panel follows every layout of the page, not just the first one after a rotation.
        requireActivity().getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(mPageLayout);
    }

    @Override
    public void onStop() {
        requireActivity().getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(mPageLayout);
        super.onStop();
    }

    private final android.view.ViewTreeObserver.OnGlobalLayoutListener mPageLayout = this::placeBesideVideo;

    /**
     * MEETUBE(live chat, issue #32): never over the video. Upright the window is only the room under
     * the video; sideways it is a column on the right of it, YouTube's. Taps beside the window reach
     * the player (FLAG_NOT_TOUCH_MODAL), so the video stays playable while the chat runs.
     */
    private void placeBesideVideo() {
        Dialog dialog = getDialog();
        Window window = dialog != null ? dialog.getWindow() : null;
        if (window == null || mHost == null || !dialog.isShowing()) {
            return;
        }
        View page = requireActivity().getWindow().getDecorView();
        View video = mHost.getChatVideoArea();
        if (video == null) {
            // A card floating a margin in from the edges, rounded all round: off the screen's top
            // edge, Material also finds no status bar inset to leave room for.
            int margin = cardMarginPx(page);
            int column = Math.round(page.getWidth() * 0.4f);
            mHost.setChatColumn(column + 2 * margin); // the video beside the chat, never under it
            if (!resize(window, column, page.getHeight() - 2 * margin, Gravity.END | Gravity.CENTER_VERTICAL, margin)) {
                return;
            }
            MobileSheets.keepSystemBarsHidden(window); // over the fullscreen player
        } else {
            mHost.setChatColumn(0);
            int[] at = new int[2];
            video.getLocationInWindow(at);
            int under = page.getHeight() - (at[1] + video.getHeight());
            // A video filling the page (vertical fullscreen) leaves no room: take its lower half.
            int height = under < page.getHeight() / 3 ? page.getHeight() / 2 : under;
            if (!resize(window, ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM, 0)) {
                return;
            }
            MobileSheets.showSystemBars(window); // back from fullscreen: the page has its bars
        }
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        // The side card is frosted square and rounded all round by its outline (roundCard). Posted:
        // a sheet's first frost runs as its window attaches and would put the sheet's corners back.
        boolean rounded = video != null;
        window.getDecorView().post(() -> com.newtube.mobile.ui.common.SheetGlass.frost(dialog, rounded));
        mColumn = video == null;
        // The side card leaves no room for system bars: hidden over the fullscreen player, they
        // still reach Material as insets, which capped the sheet a status bar's height short of its
        // window's top - an empty strip above the handle. Consumed at the root, no layer sees them.
        View decor = window.getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) ->
                mColumn ? new WindowInsetsCompat.Builder(insets)
                        .setInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                        .build() : ViewCompat.onApplyWindowInsets(v, insets));
        decor.requestApplyInsets();
        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (frame != null) {
            roundCard(frame, mColumn);
            frame.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.requestLayout();
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(frame);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    /**
     * MEETUBE(description): {@code dialog} (just shown) as the landscape side card the chat is: a
     * column on the right, the video still playing and touchable beside it. Returns the room in px
     * the video steps aside for (the host's {@link Host#setChatColumn}).
     * ponytail: the chat's own placeBesideVideo keeps its copy, as it also follows rotations.
     */
    static int placeSideCard(@NonNull Dialog dialog, @NonNull View page) {
        Window window = dialog.getWindow();
        if (window == null) {
            return 0;
        }
        int margin = cardMarginPx(page);
        int column = Math.round(page.getWidth() * 0.4f);
        resize(window, column, page.getHeight() - 2 * margin, Gravity.END | Gravity.CENTER_VERTICAL, margin);
        MobileSheets.keepSystemBarsHidden(window);
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        window.getDecorView().post(() -> com.newtube.mobile.ui.common.SheetGlass.frost(dialog, false));
        // No bar insets: hidden over the player, they would still leave an empty strip up top.
        View decor = window.getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> WindowInsetsCompat.CONSUMED);
        decor.requestApplyInsets();
        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (frame != null) {
            roundCard(frame, true);
            frame.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.requestLayout();
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(frame);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
        return column + 2 * margin;
    }

    /** The panel is the landscape side card (not the upright one under the video). */
    private boolean mColumn;

    /** False when the window already has this size: every page layout lands here. */
    private static boolean resize(Window window, int width, int height, int gravity, int x) {
        WindowManager.LayoutParams lp = window.getAttributes();
        if (lp.width == width && lp.height == height && lp.gravity == gravity && lp.x == x) {
            return false;
        }
        lp.width = width;
        lp.height = height;
        lp.gravity = gravity;
        lp.x = x; // from the end edge, with gravity END
        window.setAttributes(lp);
        return true;
    }

    private static final float CARD_MARGIN_DP = 12f;
    private static final float CARD_CORNER_DP = 24f;

    /**
     * MEETUBE(live chat, issue #32): a landscape side panel as a card, every corner rounded (the
     * live chat here, a video's comments in MobilePlaybackActivity); {@code false} undoes it.
     */
    static void roundCard(View view, boolean card) {
        if (!card) {
            view.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            view.setClipToOutline(false);
            view.setForeground(null);
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        float corner = CARD_CORNER_DP * density;
        view.setForeground(com.newtube.mobile.ui.common.GlassView.rim(corner, density)); // the nav pill's glass rim
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), corner);
            }
        });
        view.setClipToOutline(true);
    }

    /** The margin a side card keeps from the screen's edges and the video, in px. */
    static int cardMarginPx(View view) {
        return Math.round(CARD_MARGIN_DP * view.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        // User closed the panel (X, swipe, back, scrim tap): tell the Activity to stop the live-chat
        // poll it opened for us. onDestroyView only tears down the observer - the poll outlives the
        // view during background audio, so its lifetime is tied to this explicit dismiss instead.
        // Stop following the page first: the layout that gives the video its room back must not
        // place the closing panel (and step the video aside) once more.
        requireActivity().getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(mPageLayout);
        if (mHost != null) {
            mHost.setChatColumn(0);
            mHost.onChatSheetDismissed();
        }
        super.onDismiss(dialog);
    }

    @Override
    public void onDestroyView() {
        if (mSendAction != null) mSendAction.dispose();
        if (mFeedback != null) mFeedback.dismiss();
        mSendAction = null;
        mFeedback = null;
        mEmpty = null;
        if (mInput != null) mDraft = mInput.getText().toString();
        mSending = false;
        mComposer = null;
        mInput = null;
        mSend = null;
        mAccount = null;
        mSendProgress = null;
        mList = null;
        mAdapter = null;
        if (mHost != null) {
            mHost.unregisterChatObserver(mObserver);
        }
        super.onDestroyView();
    }
}
