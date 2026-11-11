package com.newtube.mobile.ui.signin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.youtubeapi.web.WebSession;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.newtube.mobile.ui.common.MobileSnackbar;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

/** Shared explanation for every optional web-only feature. The theme supplies the sheet surface. */
public final class WebSignInSheet {
    private WebSignInSheet() {}

    /** Signed out: explain, then open Google's page. Signed in: confirm, then sign out. */
    public static void manage(Activity activity, Runnable onSignedOut) {
        if (!WebSignInState.isSignedIn(activity)) {
            show(activity, () -> activity.startActivity(new Intent(activity, MobileWebSignInActivity.class)));
            return;
        }
        Haptics.watch(new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_web_signin_sign_out)
                .setMessage(R.string.mobile_web_signin_sign_out_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobile_accounts_sign_out, (dialog, which) ->
                        Completable.fromAction(() -> {
                            WebSignInState.clearCookies();
                            WebSignInState.clear(activity);
                        }).subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(onSignedOut::run, error -> MobileSnackbar.show(
                                        activity, R.string.mobile_web_signin_sign_out_failed)))
                .show());
    }

    /**
     * The web account picker, the one place web accounts are chosen (live chat, Accounts,
     * Settings): a frosted sheet of avatar rows (tap = the account that speaks), then Add and Sign
     * out. {@code onChanged} runs after a pick or a sign-out; {@code add} opens the add page.
     */
    public static void pickAccount(Activity activity, Runnable onChanged, Runnable add) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        BottomSheetDialog sheet = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(sheet.getContext()).inflate(R.layout.sheet_mobile_web_accounts, null);
        ((TextView) content.findViewById(R.id.web_accounts_title)).setText(R.string.mobile_web_signin_choose);
        LinearLayout list = content.findViewById(R.id.web_accounts_list);
        LinearLayout actions = content.findViewById(R.id.web_accounts_actions);
        int selected = WebSignInState.selectedSlot(activity);
        for (WebSignInState.Account account : WebSignInState.accounts(activity)) {
            View row = LayoutInflater.from(sheet.getContext()).inflate(R.layout.item_mobile_account_row, list, false);
            ((TextView) row.findViewById(R.id.mobile_account_row_name)).setText(account.name);
            row.findViewById(R.id.mobile_account_row_email).setVisibility(View.GONE);
            row.findViewById(R.id.mobile_account_row_check)
                    .setVisibility(account.slot == selected ? View.VISIBLE : View.INVISIBLE);
            ImageView avatar = row.findViewById(R.id.mobile_account_row_avatar);
            loadAvatar(activity, avatar, account.photo);
            if (account.photo == null) fetchAvatar(activity, avatar, account);
            row.setOnClickListener(v -> {
                sheet.dismiss();
                WebSignInState.select(activity, account.slot);
                onChanged.run();
            });
            list.addView(row);
        }
        addAction(sheet, actions, R.drawable.ic_mobile_add, R.string.mobile_web_signin_add, () -> {
            sheet.dismiss();
            add.run();
        });
        addAction(sheet, actions, R.drawable.ic_mobile_logout, R.string.mobile_accounts_sign_out, () -> {
            sheet.dismiss();
            manage(activity, onChanged);
        });
        sheet.setContentView(content);
        sheet.show();
    }

    /** The avatar, round; the person glyph (tinted like the sheet's icons) until there is one. */
    public static void loadAvatar(Activity activity, ImageView view, String photo) {
        if (photo != null) {
            view.clearColorFilter();
            Glide.with(activity).load(photo).circleCrop().placeholder(R.drawable.ic_mobile_account).into(view);
        } else {
            view.setImageResource(R.drawable.ic_mobile_account);
            view.setColorFilter(ContextCompat.getColor(activity, R.color.mobile_color_on_surface));
        }
    }

    /** Accounts saved before avatars were: look the photo up once, then it is kept. */
    private static void fetchAvatar(Activity activity, ImageView view, WebSignInState.Account account) {
        Context app = activity.getApplicationContext();
        Single.fromCallable(() -> {
                    WebSession.WebAccount web = WebSession.account(account.slot);
                    String photo = web != null && account.name.equals(web.name) && web.photo != null ? web.photo : "";
                    if (!photo.isEmpty()) WebSignInState.save(app, account.slot, account.name, photo);
                    return photo;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(photo -> {
                    if (!photo.isEmpty() && !activity.isDestroyed()) loadAvatar(activity, view, photo);
                }, error -> { });
    }

    private static void addAction(BottomSheetDialog sheet, LinearLayout parent, int icon, int label, Runnable onClick) {
        View row = LayoutInflater.from(sheet.getContext()).inflate(R.layout.item_mobile_you_row, parent, false);
        ((ImageView) row.findViewById(R.id.mobile_you_row_icon)).setImageResource(icon);
        row.findViewById(R.id.mobile_you_row_glass).setWillNotDraw(true); // the You page's bubble, not the sheet's
        ((TextView) row.findViewById(R.id.mobile_you_row_label)).setText(label);
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
    }

    /** Intent for the add-account page (keeps the accounts already signed in). */
    public static Intent addAccountIntent(Activity activity) {
        return new Intent(activity, MobileWebSignInActivity.class).putExtra(MobileWebSignInActivity.EXTRA_ADD, true);
    }

    public static void show(Activity activity, Runnable signIn) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        BottomSheetDialog sheet = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(sheet.getContext()).inflate(R.layout.sheet_mobile_web_signin, null);
        content.findViewById(R.id.web_signin_continue).setOnClickListener(v -> {
            sheet.dismiss();
            signIn.run();
        });
        content.findViewById(R.id.web_signin_not_now).setOnClickListener(v -> sheet.dismiss());
        sheet.setContentView(content);
        sheet.show();
    }
}
