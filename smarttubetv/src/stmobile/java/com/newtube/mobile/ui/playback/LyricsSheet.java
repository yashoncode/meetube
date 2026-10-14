package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.function.LongSupplier;

/**
 * METUBE(lyrics): the playing song's lyrics (found by {@link Lyrics}) in a panel under the video, like
 * YouTube Music's Lyrics tab. Timed lyrics light up line by line and keep the sung line in view;
 * plain ones are one selectable text, so any of it can be copied. Same frosted sheet and sizing as
 * the Description panel (DescriptionSheet), shown by the player (showPlayerSheet).
 */
final class LyricsSheet {
    /** Light a line up this early, so it is there as it is sung rather than just after. */
    private static final long LEAD_MS = 300;
    /** After the listener scrolls, leave the lyrics where they put them this long. */
    private static final long HANDS_OFF_MS = 4_000;
    private static final long TICK_MS = 200;

    private LyricsSheet() {
    }

    /** {@code position} is the player's position in ms (negative when unknown). */
    static BottomSheetDialog create(@NonNull Activity activity, @NonNull String videoId, String title, String channel,
                                    @Nullable View video, @NonNull LongSupplier position) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_lyrics, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f); // the playing video above stays as it is, never dimmed
        }
        if (video != null) {
            DescriptionSheet.fillUnder(dialog, content, video);
            if (window != null) {
                leaveVideoFree(window, video);
            }
        } else {
            dialog.setCanceledOnTouchOutside(false); // fullscreen: a tap on the video keeps them too
        }
        content.findViewById(R.id.lyrics_sheet_close).setOnClickListener(v -> dialog.dismiss());
        Lyrics.fetch(videoId, title != null ? title : "", channel != null ? channel : "",
                lyrics -> show(dialog, content, lyrics, position));
        return dialog;
    }

    /**
     * The sheet's window ends at the video's bottom edge, and a touch outside it goes to the page
     * (FLAG_NOT_TOUCH_MODAL): the video takes taps as usual - controls, seek, fullscreen - and the
     * lyrics stay open. They close by the X, a drag down or Back.
     */
    private static void leaveVideoFree(Window window, View video) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        View decor = window.getDecorView();
        int[] at = new int[2];
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (decor.getHeight() == 0) {
                return true;
            }
            decor.getLocationOnScreen(at);
            int bottom = at[1] + decor.getHeight();
            video.getLocationOnScreen(at);
            int height = bottom - (at[1] + video.getHeight());
            if (height <= 0 || height == decor.getHeight()) {
                return true; // in place (or the video fills the screen: nothing above to free)
            }
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.BOTTOM;
            params.height = height;
            window.setAttributes(params);
            return false;
        });
    }

    private static void show(BottomSheetDialog dialog, View content, @Nullable Lyrics lyrics, LongSupplier position) {
        content.findViewById(R.id.lyrics_sheet_loading).setVisibility(View.GONE);
        if (lyrics == null) {
            content.findViewById(R.id.lyrics_sheet_empty).setVisibility(View.VISIBLE);
            return;
        }
        NestedScrollView scroll = content.findViewById(R.id.lyrics_sheet_scroll);
        scroll.setVisibility(View.VISIBLE);
        ((TextView) content.findViewById(R.id.lyrics_sheet_song)).setText(lyrics.title);
        ((TextView) content.findViewById(R.id.lyrics_sheet_artist)).setText(lyrics.artist);
        ((TextView) content.findViewById(R.id.lyrics_sheet_source))
                .setText(content.getContext().getString(R.string.mobile_lyrics_source, lyrics.source));
        LinearLayout lines = content.findViewById(R.id.lyrics_sheet_lines);
        if (lyrics.times == null) {
            TextView text = line(lines, String.join("\n", lyrics.lines), 16, R.color.mobile_color_on_surface);
            text.setLineSpacing(0, 1.3f);
            text.setTextIsSelectable(true);
            lines.addView(text);
            return;
        }
        for (String words : lyrics.lines) {
            TextView line = line(lines, words, 22, R.color.mobile_color_on_surface_secondary);
            line.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            line.setPadding(0, 0, 0, Math.round(14 * content.getResources().getDisplayMetrics().density));
            lines.addView(line);
        }
        follow(dialog, scroll, lines, lyrics.times, position);
    }

    /** Lights the line being sung and keeps it a third of the way down, until the sheet closes. */
    private static void follow(BottomSheetDialog dialog, NestedScrollView scroll, LinearLayout lines, long[] times,
                               LongSupplier position) {
        int dim = ContextCompat.getColor(scroll.getContext(), R.color.mobile_color_on_surface_secondary);
        int lit = ContextCompat.getColor(scroll.getContext(), R.color.mobile_color_on_surface);
        long[] touchedAt = {-HANDS_OFF_MS};
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                touchedAt[0] = SystemClock.uptimeMillis();
            }
            return false;
        });
        lines.post(new Runnable() {
            private int mCurrent = -1;

            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return;
                }
                long at = position.getAsLong() + LEAD_MS;
                int sung = -1;
                while (sung + 1 < times.length && times[sung + 1] <= at) {
                    sung++;
                }
                if (sung != mCurrent) {
                    if (mCurrent >= 0) {
                        ((TextView) lines.getChildAt(mCurrent)).setTextColor(dim);
                    }
                    mCurrent = sung;
                    if (sung >= 0) {
                        View line = lines.getChildAt(sung);
                        ((TextView) line).setTextColor(lit);
                        if (SystemClock.uptimeMillis() - touchedAt[0] > HANDS_OFF_MS) {
                            int y = lines.getTop() + line.getTop() - scroll.getHeight() / 3;
                            scroll.smoothScrollTo(0, Math.max(0, y));
                        }
                    }
                }
                lines.postDelayed(this, TICK_MS);
            }
        });
    }

    private static TextView line(View parent, CharSequence text, int sp, int color) {
        TextView view = new TextView(parent.getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ContextCompat.getColor(parent.getContext(), color));
        return view;
    }
}
