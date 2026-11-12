package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SidebarService implements ProfileChangeListener {
    @SuppressLint("StaticFieldLeak")
    private static SidebarService sInstance;
    private final Context mContext;
    private List<Video> mPinnedItems;
    private List<Integer> mSectionOrder;
    private final Map<Integer, Integer> mDefaultSections = new LinkedHashMap<>();
    private final AppPrefs mPrefs;
    private boolean mIsSettingsSectionEnabled;
    private int mBootSectionId;
    private final static int RESERVED_ID = 100;
    /**
     * NEWTUBE(notifications): phone gate. YouTube refuses the notification inbox to the TV sign-in the
     * app uses (HTTP 400 "Precondition check failed", signed in and out), so the phone has no
     * Notifications section: it is left out of the pinned sections (the You panel), of "Set-up
     * sections" and of "Boot to section", and a stored boot to it opens Home. The stored sidebar prefs
     * are read and written as before - only what is shown changes. Set from MobileMainApplication; TV
     * never sets it.
     */
    private static volatile boolean sNotificationsHidden;

    public static void setNotificationsSectionHidden(boolean hidden) {
        sNotificationsHidden = hidden;
    }

    private SidebarService(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        initSections();
        restoreState();
    }

    public static SidebarService instance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new SidebarService(context.getApplicationContext());
        }

        return sInstance;
    }

    public Collection<Video> getPinnedItems() {
        if (sNotificationsHidden) {
            List<Video> shown = new ArrayList<>(mPinnedItems);
            Helpers.removeIf(shown, item -> item != null && getSectionId(item) == MediaGroup.TYPE_NOTIFICATIONS);
            return Collections.unmodifiableList(shown);
        }

        return Collections.unmodifiableList(mPinnedItems);
    }

    public int addPinnedItem(Video item) {
        if (mPinnedItems.contains(item)) {
            return -1;
        }

        int idx = Helpers.indexOfFirst(mPinnedItems, obj -> obj.getId() > RESERVED_ID);

        if (idx != -1) {
            mPinnedItems.add(idx, item);
        } else {
            mPinnedItems.add(item);
        }
        persistState();

        return idx;
    }

    public void removePinnedItem(Video item) {
        mPinnedItems.remove(item);
        persistState();
    }

    public void enableSection(int sectionId, boolean enabled) {
        if (enabled) {
            if (sectionId == MediaGroup.TYPE_SETTINGS) {
                mIsSettingsSectionEnabled = true; // prevent Settings lock
            }

            //Video item = new Video();
            //item.sectionId = sectionId;
            //
            //if (mPinnedItems.contains(item)) { // don't reorder if item already exists
            //    return;
            //}

            Video section = Helpers.findFirst(mPinnedItems, item -> item != null && item.sectionId == sectionId);

            if (section != null) { // don't reorder if item already exists
                return;
            }

            Video item = new Video();
            item.sectionId = sectionId;

            int index = getDefaultSectionIndex(sectionId);

            if (index == -1 || index > mPinnedItems.size()) {
                mPinnedItems.add(item);
            } else {
                mPinnedItems.add(index, item);
            }
        } else {
            Helpers.removeIf(mPinnedItems, item -> item == null || item.sectionId == sectionId);
        }

        persistState();
    }

    public Map<Integer, Integer> getDefaultSections() {
        if (sNotificationsHidden) {
            Map<Integer, Integer> shown = new LinkedHashMap<>(mDefaultSections);
            shown.values().remove(MediaGroup.TYPE_NOTIFICATIONS);
            return shown;
        }

        return mDefaultSections;
    }

    private int getDefaultSectionIndex(int sectionId) {
        int index = -1;

        Collection<Integer> values = mDefaultSections.values();

        for (int item : values) {
            index++;
            if (item == sectionId) {
                break;
            }
        }

        return index;
    }

    /**
     * Contains sections and pinned items!
     */
    public boolean isSectionPinned(int sectionId) {
        if (sNotificationsHidden && sectionId == MediaGroup.TYPE_NOTIFICATIONS) {
            return false;
        }

        Video section = Helpers.findFirst(mPinnedItems, item -> getSectionId(item) == sectionId);
        return section != null; // by default enable all pinned items
    }

    public int getSectionIndex(int sectionId) {
        // 1) Distinguish section from pinned item
        // 2) Add pinned items after the sections

        int index = findPinnedItemIndex(sectionId);

        return index;
    }

    /** MEETUBE(you order): retain disabled choices, with enabled choices in the live sidebar order. */
    public List<Integer> getSectionOrder(List<Integer> sectionIds) {
        List<Integer> order = new ArrayList<>();
        for (int sectionId : mSectionOrder) {
            if (sectionIds.contains(sectionId) && mDefaultSections.containsValue(sectionId) && !order.contains(sectionId)) {
                order.add(sectionId);
            }
        }
        for (int sectionId : sectionIds) {
            if (mDefaultSections.containsValue(sectionId) && !order.contains(sectionId)) {
                order.add(sectionId);
            }
        }
        List<Integer> pinned = new ArrayList<>();
        for (int sectionId : order) {
            if (getSectionIndex(sectionId) >= 0) {
                pinned.add(sectionId);
            }
        }
        Collections.sort(pinned, (a, b) -> Integer.compare(getSectionIndex(a), getSectionIndex(b)));
        int next = 0;
        for (int i = 0; i < order.size(); i++) {
            if (getSectionIndex(order.get(i)) >= 0) {
                order.set(i, pinned.get(next++));
            }
        }
        return order;
    }

    /** MEETUBE(you order): reorder these choices in their sidebar slots and remember disabled rows too. */
    public void setSectionOrder(List<Integer> sectionIds) {
        List<Integer> current = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int sectionId : sectionIds) {
            if (mDefaultSections.containsValue(sectionId) && !order.contains(sectionId)) {
                order.add(sectionId);
                if (getSectionIndex(sectionId) >= 0) {
                    current.add(sectionId);
                }
            }
        }
        Collections.sort(current, (a, b) -> Integer.compare(getSectionIndex(a), getSectionIndex(b)));
        int next = 0;
        for (int sectionId : order) {
            int index = getSectionIndex(sectionId);
            if (index < 0) {
                continue;
            }
            int displacedId = current.get(next);
            int target = getSectionIndex(displacedId);
            if (index != target) {
                moveSectionTo(sectionId, target);
                moveSectionTo(displacedId, index);
                Collections.swap(current, next, current.indexOf(sectionId));
            }
            next++;
        }
        mSectionOrder = order;
        persistState();
    }

    private void moveSectionTo(int sectionId, int target) {
        int index = getSectionIndex(sectionId);
        while (index > target) {
            moveSectionUp(sectionId);
            index--;
        }
        while (index < target) {
            moveSectionDown(sectionId);
            index++;
        }
    }

    public void renameSection(int sectionId, String newTitle) {
        int index = findPinnedItemIndex(sectionId);
        if (index != -1) {
            Video video = mPinnedItems.get(index);
            video.title = newTitle;
            persistState();
        }
    }

    public void moveSectionUp(int sectionId) {
        shiftSection(sectionId, -1);
    }

    public void moveSectionDown(int sectionId) {
        shiftSection(sectionId, 1);
    }

    public boolean canMoveSectionUp(int sectionId) {
        return canShiftSection(sectionId, -1);
    }

    public boolean canMoveSectionDown(int sectionId) {
        return canShiftSection(sectionId, 1);
    }

    private boolean canShiftSection(int sectionId, int shift) {
        int index = findPinnedItemIndex(sectionId);

        if (index != -1) {
            return  index + shift >= 0 && index + shift < mPinnedItems.size();
        }

        return false;
    }

    private void shiftSection(int sectionId, int shift) {
        if (!canShiftSection(sectionId, shift)) {
            return;
        }

        int index = findPinnedItemIndex(sectionId);

        if (index != -1) {
            Video current = mPinnedItems.get(index);
            mPinnedItems.remove(current);

            mPinnedItems.add(index + shift, current);
            persistState();
        }
    }

    private int findPinnedItemIndex(int sectionId) {
        int index = -1;

        for (Video item : mPinnedItems) {
            // Distinguish pinned items by hashCode or extra field (default section)!
            if (getSectionId(item) == sectionId) {
                index = mPinnedItems.indexOf(item);
                break;
            }
        }

        return index;
    }

    public void setBootSectionId(int sectionId) {
        mBootSectionId = sectionId;

        persistState();
    }

    public int getBootSectionId() {
        if (sNotificationsHidden && mBootSectionId == MediaGroup.TYPE_NOTIFICATIONS) {
            return MediaGroup.TYPE_HOME;
        }

        return mBootSectionId;
    }

    public void enableSettingsSection(boolean enabled) {
        mIsSettingsSectionEnabled = enabled;

        persistState();
    }

    public boolean isSettingsSectionEnabled() {
        return mIsSettingsSectionEnabled;
    }

    private int getSectionId(Video item) {
        if (item == null) {
            return -1;
        }

        return item.sectionId == -1 ? item.getId() : item.sectionId;
    }

    private void initSections() {
        mDefaultSections.put(R.string.header_notifications, MediaGroup.TYPE_NOTIFICATIONS);
        mDefaultSections.put(R.string.header_home, MediaGroup.TYPE_HOME);
        mDefaultSections.put(R.string.header_shorts, MediaGroup.TYPE_SHORTS);
        mDefaultSections.put(R.string.header_trending, MediaGroup.TYPE_TRENDING);
        mDefaultSections.put(R.string.header_kids_home, MediaGroup.TYPE_KIDS_HOME);
        mDefaultSections.put(R.string.header_sports, MediaGroup.TYPE_SPORTS);
        mDefaultSections.put(R.string.badge_live, MediaGroup.TYPE_LIVE);
        mDefaultSections.put(R.string.header_gaming, MediaGroup.TYPE_GAMING);
        mDefaultSections.put(R.string.header_news, MediaGroup.TYPE_NEWS);
        mDefaultSections.put(R.string.header_music, MediaGroup.TYPE_MUSIC);
        mDefaultSections.put(R.string.header_channels, MediaGroup.TYPE_CHANNEL_UPLOADS);
        mDefaultSections.put(R.string.header_subscriptions, MediaGroup.TYPE_SUBSCRIPTIONS);
        mDefaultSections.put(R.string.header_history, MediaGroup.TYPE_HISTORY);
        mDefaultSections.put(R.string.header_downloads, VideoDownloads.SECTION_ID); // NEWTUBE(downloads)
        mDefaultSections.put(R.string.header_blocked_channels, MediaGroup.TYPE_BLOCKED_CHANNELS);
        mDefaultSections.put(R.string.header_playlists, MediaGroup.TYPE_USER_PLAYLISTS);
        mDefaultSections.put(R.string.my_videos, MediaGroup.TYPE_MY_VIDEOS);
        mDefaultSections.put(R.string.playback_queue_category_title, MediaGroup.TYPE_PLAYBACK_QUEUE);
        mDefaultSections.put(R.string.header_settings, MediaGroup.TYPE_SETTINGS);
    }

    private void initPinnedItems() {
        for (int sectionId : mDefaultSections.values()) {
            // Hidden sections
            enableSection(sectionId, !Helpers.equalsAny(sectionId, new int[] {
                    MediaGroup.TYPE_NOTIFICATIONS,
                    MediaGroup.TYPE_PLAYBACK_QUEUE,
                    MediaGroup.TYPE_TRENDING,
                    MediaGroup.TYPE_BLOCKED_CHANNELS
            }));
        }
    }

    private void cleanupPinnedItems() {
        Helpers.removeDuplicates(mPinnedItems);

        Helpers.removeIf(mPinnedItems, item -> {
            if (item == null) {
                return true;
            }

            item.videoId = null;

            // Fix id collision between pinned and default sections
            if (item.getId() < RESERVED_ID && item.getId() >= -1 && item.sectionId == -1) {
                return true;
            }

            return !item.hasPlaylist() && item.channelId == null && item.sectionId == -1 && item.channelGroupId == null && !item.hasReloadPageKey();
        });
    }

    private void restoreState() {
        String data = mPrefs.getSidebarData();

        String[] split = Helpers.splitData(data);

        mPinnedItems = Helpers.parseList(split, 0, Video::fromString);
        mBootSectionId = Helpers.parseInt(split, 1, MediaGroup.TYPE_HOME);
        mIsSettingsSectionEnabled = Helpers.parseBoolean(split, 2, true);
        mSectionOrder = Helpers.parseIntList(split, 3);

        transferOldPinnedItems();

        if (mPinnedItems.isEmpty()) {
            initPinnedItems();
        }

        // Backward compatibility
        enableSection(MediaGroup.TYPE_SETTINGS, true);

        cleanupPinnedItems();
    }

    private void transferOldPinnedItems() {
        if (mPinnedItems != null && !mPinnedItems.isEmpty()) {
            return;
        }

        List<Video> oldPinnedItems = GeneralData.instance(mContext).getOldPinnedItems();

        if (oldPinnedItems != null && !oldPinnedItems.isEmpty()) {
            mPinnedItems = oldPinnedItems;
        }
    }

    public void persistState() {
        // MEETUBE(you order): long-press section moves also update the remembered positions of enabled choices.
        mSectionOrder = getSectionOrder(mSectionOrder);
        mPrefs.setSidebarData(Helpers.mergeData(mPinnedItems, mBootSectionId, mIsSettingsSectionEnabled, mSectionOrder));
    }

    @Override
    public void onProfileChanged() {
        restoreState();
    }
}
