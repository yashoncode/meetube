package com.newtube.mobile.ui.meetune;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KaraokePackTest {
    @Test
    public void mapsOnlySupportedNativeLibraryDirectories() {
        assertEquals("arm64-v8a", KaraokePack.mapAbi("arm64"));
        assertEquals("x86_64", KaraokePack.mapAbi("x86_64"));
        assertNull(KaraokePack.mapAbi("arm"));
        assertNull(KaraokePack.mapAbi("x86"));
        assertNull(KaraokePack.mapAbi("arm64-v8a"));
    }

    @Test
    public void acceptsOnlyFlatEntryNamesWithoutDotDot() {
        assertTrue(KaraokePack.safeEntryName("classes.dex"));
        assertTrue(KaraokePack.safeEntryName("UVR_MDXNET_KARA.LICENSE"));
        assertFalse(KaraokePack.safeEntryName("../classes.dex"));
        assertFalse(KaraokePack.safeEntryName("folder/classes.dex"));
        assertFalse(KaraokePack.safeEntryName("folder\\classes.dex"));
        assertFalse(KaraokePack.safeEntryName("karaoke..onnx"));
        assertFalse(KaraokePack.safeEntryName("/karaoke.onnx"));
        assertFalse(KaraokePack.safeEntryName("."));
        assertFalse(KaraokePack.safeEntryName(""));
    }
}
