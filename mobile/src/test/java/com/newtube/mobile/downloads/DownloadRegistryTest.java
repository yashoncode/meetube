package com.newtube.mobile.downloads;

import android.app.Application;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DownloadRegistryTest {
    /** A download cut by a process death comes back paused with its progress, a paused one stays paused (issue #191). */
    @Test public void interruptedAndPausedDownloadsLoadPausedWithTheirProgress() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        DownloadItem running = item("running", DownloadItem.STATE_DOWNLOADING);
        DownloadItem paused = item("paused", DownloadItem.STATE_PAUSED);
        DownloadItem failed = item("failed", DownloadItem.STATE_FAILED);
        JSONArray items = new JSONArray().put(running.toJson()).put(paused.toJson()).put(failed.toJson());
        try (FileOutputStream out = new FileOutputStream(new File(DownloadRegistry.dir(context), "registry.json"))) {
            out.write(new JSONObject().put("items", items).toString().getBytes(StandardCharsets.UTF_8));
        }

        DownloadRegistry registry = DownloadRegistry.instance(context);

        assertTrue(registry.find("running").isPaused());
        assertEquals(40, registry.find("running").progressPercent());
        assertTrue(registry.find("paused").isPaused());
        assertTrue(registry.find("failed").isFailed());
        assertNull(registry.nextQueued());
        assertSame(registry.find("running"), registry.findActive("running-video"));
    }

    private static DownloadItem item(String id, int state) {
        DownloadItem item = new DownloadItem(id, id + "-video", id, null, null, DownloadOption.KIND_VIDEO,
                "720p", "22", null, 0);
        item.state = state;
        item.bytesDone = 400;
        item.bytesTotal = 1000;
        return item;
    }
}
