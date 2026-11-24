package com.newtube.mobile.ui.common;

import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;

import androidx.annotation.NonNull;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEETUBE(issue #310): "@handle" mentions and youtube.com channel links in a description open
 * that channel in the app, as on YouTube, instead of being plain text or leaving for the browser.
 */
public final class ChannelLinks {
    private static final Pattern LINK = Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/(@[\\w.-]+|channel/(UC[\\w-]{22}))"
                    + "|(?<![\\w@./])(@[\\w-][\\w.-]{1,29})");

    private ChannelLinks() {
    }

    /** Adds the channel links to {@code text} in place; a web link over the same words gives way. */
    public static void apply(@NonNull Spannable text) {
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            String id = m.group(2) != null ? m.group(2) : m.group(1) != null ? m.group(1) : m.group(3);
            int end = m.end();
            while (id.endsWith(".")) { // "@name." at the end of a sentence
                id = id.substring(0, id.length() - 1);
                end--;
            }
            for (URLSpan web : text.getSpans(m.start(), end, URLSpan.class)) {
                text.removeSpan(web);
            }
            String channel = id;
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    ChannelPresenter.instance(widget.getContext()).openChannel(channel);
                }
            }, m.start(), end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
