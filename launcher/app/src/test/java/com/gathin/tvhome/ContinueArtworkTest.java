package com.gathin.tvhome;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ContinueArtworkTest {
    @Test public void localPosterPrecedesThumbnailAndDuplicatesAreRemoved() {
        assertEquals(Arrays.asList("content://tv/poster", "content://tv/thumb"),
                ContinueArtwork.candidates("content://tv/poster", "content://tv/thumb"));
        assertEquals(Collections.singletonList("content://tv/poster"),
                ContinueArtwork.candidates("content://tv/poster", "content://tv/poster"));
        assertEquals(Collections.singletonList("android.resource://app/12"),
                ContinueArtwork.candidates(null, "android.resource://app/12"));
    }
    @Test public void rejectsWebFileEmptyAndMalformedSources() {
        assertTrue(ContinueArtwork.candidates("https://example.com/poster", "file:///secret").isEmpty());
        assertTrue(ContinueArtwork.candidates("", "content://invalid uri").isEmpty());
        assertEquals(Collections.singletonList("content://tv/thumb"),
                ContinueArtwork.candidates("http://example.com/poster", "content://tv/thumb"));
    }
    @Test public void missingDeniedAndUndecodablePosterFallsBackToThumbnail() {
        List<String> addresses = ContinueArtwork.candidates("content://tv/poster", "content://tv/thumb");
        assertEquals("thumbnail", ContinueArtwork.firstAvailable(addresses, address -> {
            if (address.endsWith("poster")) throw new IOException("access denied");
            return "thumbnail";
        }));
        assertEquals("thumbnail", ContinueArtwork.firstAvailable(addresses,
                address -> address.endsWith("poster") ? null : "thumbnail"));
        assertNull(ContinueArtwork.firstAvailable(addresses, address -> null));
    }
    @Test public void validPosterWinsAndProgressHandlesMissingAndOverflow() {
        List<String> addresses = ContinueArtwork.candidates("content://tv/poster", "content://tv/thumb");
        assertEquals("poster", ContinueArtwork.firstAvailable(addresses,
                address -> address.endsWith("poster") ? "poster" : "thumbnail"));
        ContinueWatching.Item item = new ContinueWatching.Item(); item.position = -1; item.duration = 100;
        assertEquals(-1f, item.progress(), 0f);
        item.position = 50; assertEquals(0.5f, item.progress(), 0f);
        item.position = 500; assertEquals(1f, item.progress(), 0f);
        item.duration = 0; assertEquals(-1f, item.progress(), 0f);
    }
}
