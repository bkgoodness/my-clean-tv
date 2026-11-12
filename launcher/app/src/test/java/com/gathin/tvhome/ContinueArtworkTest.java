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
        assertTrue(ContinueArtwork.candidates("", "invalid uri").isEmpty());
        assertEquals(Collections.singletonList("content://tv/an image"),
                ContinueArtwork.candidates("content://tv/an image", null));
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
    @Test public void episodicThumbnailsAndOnlineSwitchRespectFallbackOrder() {
        String poster = "https://cdn.example.com/poster", thumb = "content://provider/thumbnail";
        assertEquals(Arrays.asList(thumb, poster), ContinueArtwork.candidates(poster, thumb, true, true));
        assertEquals(Arrays.asList(poster, thumb), ContinueArtwork.candidates(poster, thumb, false, true));
        assertEquals(Collections.singletonList(thumb), ContinueArtwork.candidates(poster, thumb, true, false));
        assertTrue(ContinueArtwork.candidates("http://cdn.example.com/p", null, false, true).isEmpty());
    }
    @Test public void decodedDimensionsStayBounded() {
        for (int[] dimensions : new int[][]{{4000,3000}, {1, 10000}, {512,512}, {Integer.MAX_VALUE,1}}) {
            int sample = ContinueArtwork.sampleSize(dimensions[0], dimensions[1]);
            assertTrue(dimensions[0] / sample <= 512); assertTrue(dimensions[1] / sample <= 512);
        }
        assertEquals(0, ContinueArtwork.sampleSize(0, 100));
    }
    @Test public void episodeMetadataIncludesOnlyProvidedValues() {
        assertEquals("App · S2 E3 · Finale", ContinueArtwork.metadata("App", "2", "3", "Finale"));
        assertEquals("App · E3", ContinueArtwork.metadata("App", null, "3", null));
        assertEquals("App", ContinueArtwork.metadata("App", null, null, null));
        assertEquals("App · S2 E3", ContinueArtwork.metadata("App", "S2", "E3", ""));
    }
    @Test public void preAttachedBindingsRemainValidButStaleAndRecycledBindingsAreRejected() {
        Object token = new Object();
        assertTrue(ContinueArtwork.bindingCurrent(false, 7, 7, token, token));
        assertFalse(ContinueArtwork.bindingCurrent(true, 7, 7, token, token));
        assertFalse(ContinueArtwork.bindingCurrent(false, 8, 7, token, token));
        assertFalse(ContinueArtwork.bindingCurrent(false, 7, 7, new Object(), token));
    }
    @Test public void diagnosticsContainCountsOnly() {
        ArtworkDiagnostics stats = new ArtworkDiagnostics();
        stats.sources("https://private.example/secret", "content://provider/private-title");
        stats.sources(null, ""); stats.decoded(); stats.bound(true); stats.renderReady(); stats.denied();
        String summary = stats.summary();
        assertTrue(summary.contains("missing=1")); assertTrue(summary.contains("web_sources=1"));
        assertTrue(summary.contains("local=1")); assertTrue(summary.contains("bound_detached=1"));
        assertTrue(summary.contains("render_ready=1"));
        assertFalse(summary.contains("private")); assertFalse(summary.contains("provider"));
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
