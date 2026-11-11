package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the saved tile order: file format, theme changes and the sort step. */
public class OrderTest {

    private static final List<String> DOCK = Arrays.asList("com.plexapp.android", "com.netflix.ninja");

    @Test
    public void roundTrips() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A", "c/.C"));
        Order back = Order.decode(Order.encode(o));
        assertEquals(o.dock, back.dock);
        assertEquals(o.keys, back.keys);
    }

    @Test
    public void dockedRoundTrips() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A", "c/.C"), 2);
        String text = Order.encode(o);
        assertEquals("tvhome-order 1\ndock com.plexapp.android,com.netflix.ninja\ndocked 2\nb/.B\na/.A\nc/.C\n", text);
        Order back = Order.decode(text);
        assertEquals(2, back.docked);
        assertEquals(o.keys, back.keys);
    }

    @Test
    public void anOrderWithoutDockedLeavesTheDockToDockApps() {
        Order back = Order.decode("tvhome-order 1\ndock x\na/.A\nb/.B\n");
        assertEquals(-1, back.docked);
        assertEquals(Arrays.asList("a/.A", "b/.B"), back.keys);
        assertEquals(-1, Order.docked(back, Collections.singletonList("x")));
        assertTrue(Order.encode(new Order("x", Arrays.asList("a/.A"))).indexOf("docked") < 0);
    }

    @Test
    public void dockedSurvivesChangedDockDefaults() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A"), 1);
        assertEquals(1, Order.docked(o, DOCK));
        assertEquals(1, Order.docked(o, Collections.singletonList("com.plexapp.android")));
        assertEquals(-1, Order.docked(null, DOCK));
    }

    @Test
    public void rejectsABadDockedLine() {
        assertNull(Order.decode("tvhome-order 1\ndock x\ndocked two\na/.A\n"));
        assertNull(Order.decode("tvhome-order 1\ndock x\ndocked -1\na/.A\n"));
        assertNull(Order.decode("tvhome-order 1\ndock x\ndocked 3\na/.A\n"));   // more docked than keys
    }

    @Test
    public void emptyOrderRoundTrips() {
        Order back = Order.decode(Order.encode(new Order("", Collections.emptyList())));
        assertEquals("", back.dock);
        assertTrue(back.keys.isEmpty());
    }

    @Test
    public void rejectsWhatIsNotAnOrder() {
        assertNull(Order.decode(null));
        assertNull(Order.decode(""));
        assertNull(Order.decode("{\"version\":2}"));
        assertNull(Order.decode("tvhome-order 2\ndock x\na/.A\n"));
        assertNull(Order.decode("tvhome-order 1\na/.A\n"));                 // no dock line
        assertNull(Order.decode("tvhome-order 1\ndock x\na/.A\n\nb/.B\n"));  // blank key in the middle
        assertNull(Order.decode("tvhome-order 1\ndock x\na/.A\na/.A\n"));    // duplicate key
    }

    @Test
    public void ranksFollowTheSavedOrder() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A"));
        Map<String, Integer> r = Order.ranks(o, DOCK);
        assertEquals(Integer.valueOf(0), r.get("b/.B"));
        assertEquals(Integer.valueOf(1), r.get("a/.A"));
    }

    @Test
    public void changedThemeDockKeepsSavedOrder() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A"), 1);
        assertEquals(Order.ranks(o, DOCK), Order.ranks(o, Collections.singletonList("other")));
        assertEquals(1, Order.docked(o, Collections.singletonList("other")));
        assertTrue(Order.ranks(null, DOCK).isEmpty());
    }

    @Test
    public void editsKeepUnavailableDockAndGridAppsForTheirReturn() {
        Order old = new Order("old", Arrays.asList("dock/.A", "gone/.B", "grid/.C", "gone/.D", "grid/.E"), 2);
        Order saved = Order.mergeUnavailable(old, "new", Arrays.asList("grid/.C", "dock/.A", "grid/.E", "new/.F"), 1);
        assertEquals(Arrays.asList("grid/.C", "gone/.B", "dock/.A", "gone/.D", "grid/.E", "new/.F"), saved.keys);
        assertEquals(2, saved.docked);
        Order restarted = Order.decode(Order.encode(saved));
        assertEquals(saved.keys, restarted.keys);
        assertEquals(saved.docked, restarted.docked);
    }

    @Test
    public void savedTilesBeatThemeHiddenButExplicitRemovalStillWins() {
        Order saved = Order.decode("tvhome-order 1\ndock old\ndocked 1\ncom.example/.Main\n");
        boolean inSavedLayout = Order.packages(Order.ranks(saved, Collections.singletonList("new")))
                .contains("com.example");
        assertTrue(LibraryApps.visible(true, true, inSavedLayout, false));
        assertTrue(!LibraryApps.visible(true, false, inSavedLayout, true));
    }

    @Test
    public void firstSavedOrderRecordsDockWithoutCreatingMissingEntries() {
        Order saved = Order.mergeUnavailable(null, "theme", Arrays.asList("a/.A", "b/.B"), 1);
        assertEquals(Arrays.asList("a/.A", "b/.B"), saved.keys);
        assertEquals(1, saved.docked);
    }

    @Test
    public void savedTilesComeFirstAndNewOnesKeepTheirOrderAfter() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("c/.C", "a/.A", "gone/.Gone"));
        Map<String, Integer> r = Order.ranks(o, DOCK);
        // "n" and "m" are new installs: unranked, so the usual rule (here: list order) decides between them.
        List<String> tiles = new ArrayList<>(Arrays.asList("m/.M", "a/.A", "n/.N", "c/.C"));
        List<String> usual = new ArrayList<>(tiles);
        tiles.sort((x, y) -> {
            int c = Order.compare(r, x, y);
            return c != 0 ? c : Integer.compare(usual.indexOf(x), usual.indexOf(y));
        });
        assertEquals(Arrays.asList("c/.C", "a/.A", "m/.M", "n/.N"), tiles);
    }
}
