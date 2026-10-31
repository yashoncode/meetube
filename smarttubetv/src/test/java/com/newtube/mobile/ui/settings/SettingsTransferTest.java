package com.newtube.mobile.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.liskovsoft.sharedutils.helpers.Helpers;

import org.junit.Test;

/** MEETUBE(settings-transfer): a settings file never carries passwords, and an import keeps this phone's. */
public class SettingsTransferTest {
    /** general_data with every field set, its settings / master passwords (33, 41) included. */
    private static String generalData(String settingsPassword, String masterPassword) {
        Object[] fields = new Object[60];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = "f" + i;
        }
        fields[33] = settingsPassword;
        fields[41] = masterPassword;
        return Helpers.mergeData(fields);
    }

    @Test
    public void exportDropsPasswords() {
        String[] out = Helpers.splitData(SettingsTransferActivity.withPasswords(generalData("1234", "9999"), null));
        assertNull(Helpers.parseStr(out, 33));
        assertNull(Helpers.parseStr(out, 41));
        assertEquals("f10", Helpers.parseStr(out, 10)); // the settings themselves travel
    }

    @Test
    public void importKeepsThisPhonesPasswords() {
        String imported = SettingsTransferActivity.withPasswords(generalData("friend", "friend"), generalData("mine", null));
        String[] out = Helpers.splitData(imported);
        assertEquals("mine", Helpers.parseStr(out, 33));
        assertNull(Helpers.parseStr(out, 41));
        assertEquals("f59", Helpers.parseStr(out, 59));
    }
}
