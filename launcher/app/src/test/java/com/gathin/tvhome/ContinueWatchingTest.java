package com.gathin.tvhome;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class ContinueWatchingTest {
    @Test public void freshnessUsesProviderEngagementAndIncludesBoundary() {
        long now = 200L * 86400000L;
        assertTrue(ContinueWatching.fresh(now - 30L * 86400000L, now, 30));
        assertFalse(ContinueWatching.fresh(now - 30L * 86400000L - 1, now, 30));
        assertFalse(ContinueWatching.fresh(0, now, 30));
        assertFalse(ContinueWatching.fresh(now + 1, now, 7));
        assertTrue(ContinueWatching.fresh(0, now, 0));
    }
    @Test public void recentAndProgressSortingPlaceUnknownProgressLast() {
        assertTrue(ContinueWatching.compare(-1, 99, 0.5f, 10, true) > 0);
        assertTrue(ContinueWatching.compare(0.8f, 1, 0.2f, 100, true) < 0);
        assertTrue(ContinueWatching.compare(0.8f, 1, 0.2f, 100, false) > 0);
    }
    @Test public void providerIdentitySurvivesRepublishedIntentAndIsScopedToApp() {
        String key = ContinueWatching.identity("app.one", "content-123", "first-intent");
        assertEquals(key, ContinueWatching.identity("app.one", "content-123", "updated-intent"));
        assertNotEquals(key, ContinueWatching.identity("app.two", "content-123", "first-intent"));
        assertNull(ContinueWatching.identity("app.one", null, null));
        assertNotEquals(ContinueWatching.identity("app.one", null, "first"), ContinueWatching.identity("app.one", null, "second"));
    }
    @Test public void excludedAppsAndHiddenTitlesStayFilteredWhenRepublished() {
        ContinueWatching.Item before = new ContinueWatching.Item();
        before.pkg = "app.one"; before.title = "Show"; before.episode = "Episode";
        before.stableId = "one"; before.engaged = 100;
        Set<String> hidden = new HashSet<>(); hidden.add(before.key()); hidden.add(before.alias());
        ContinueWatching.Item publishedAgain = new ContinueWatching.Item();
        publishedAgain.pkg = before.pkg; publishedAgain.title = before.title; publishedAgain.episode = before.episode;
        publishedAgain.stableId = "different"; publishedAgain.engaged = 200;
        assertFalse(ContinueWatching.eligible(publishedAgain, Collections.emptySet(), hidden, 200, 30));
        assertTrue(ContinueWatching.eligible(publishedAgain, Collections.emptySet(), Collections.emptySet(), 200, 30));
        assertFalse(ContinueWatching.eligible(publishedAgain, Collections.singleton("app.one"), Collections.emptySet(), 200, 30));
    }
}
