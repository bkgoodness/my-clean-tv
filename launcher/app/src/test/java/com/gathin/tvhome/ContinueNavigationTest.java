package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ContinueNavigationTest {
    @Test public void oneCardStopsBothHorizontalDirections() {
        assertTrue(ContinueNavigation.horizontalEdge(0, 1, false, true));
        assertTrue(ContinueNavigation.horizontalEdge(0, 1, false, false));
        assertTrue(ContinueNavigation.horizontalEdge(0, 1, true, true));
        assertTrue(ContinueNavigation.horizontalEdge(0, 1, true, false));
    }
    @Test public void endsStayInShelfAndMiddleCanScrollInEitherDirection() {
        for (int count : new int[]{4, 20}) {
            assertTrue(ContinueNavigation.horizontalEdge(0, count, false, true));
            assertTrue(ContinueNavigation.horizontalEdge(count - 1, count, false, false));
            assertFalse(ContinueNavigation.horizontalEdge(1, count, false, true));
            assertFalse(ContinueNavigation.horizontalEdge(1, count, false, false));
            assertTrue(ContinueNavigation.horizontalEdge(0, count, true, false));
            assertTrue(ContinueNavigation.horizontalEdge(count - 1, count, true, true));
        }
        assertTrue(ContinueNavigation.horizontalEdge(-1, 4, false, true));
    }
    @Test public void refreshKeepsTheFocusedItemAndFallsBackToNearbySurvivor() {
        assertEquals(2, ContinueNavigation.restoredIndex(Arrays.asList("c", "a", "b"), "b", 1));
        assertEquals(1, ContinueNavigation.restoredIndex(Arrays.asList("a", "c"), "b", 1));
        assertEquals(0, ContinueNavigation.restoredIndex(Collections.singletonList("c"), "b", 6));
        assertEquals(-1, ContinueNavigation.restoredIndex(Collections.emptyList(), "b", 1));
    }
    @Test public void verticalTransitionsRespectEmptyRowsAndReorderMode() {
        assertTrue(ContinueNavigation.topDownToShelf(true, false));
        assertFalse(ContinueNavigation.topDownToShelf(false, false));
        assertFalse(ContinueNavigation.topDownToShelf(true, true));
        assertTrue(ContinueNavigation.appUpToShelf(3, 4, false, false));
        assertFalse(ContinueNavigation.appUpToShelf(4, 4, false, false));
        assertTrue(ContinueNavigation.appUpToShelf(8, 4, true, false));
        assertFalse(ContinueNavigation.appUpToShelf(0, 4, false, true));
    }
    @Test public void hidingShelfKeepsTheOriginalHeightForTileSizing() {
        assertEquals(500, ContinueNavigation.tileSizingHeight(500, false, 150));
        assertEquals(500, ContinueNavigation.tileSizingHeight(650, true, 150));
        assertEquals(1, ContinueNavigation.tileSizingHeight(100, true, 150));
    }
    @Test public void shelfLeavesSpaceForDeeperAppRowsButReturnsForTheFirstRow() {
        assertFalse(ContinueNavigation.collapseShelf(true, false, false, 3, 4));
        assertTrue(ContinueNavigation.collapseShelf(true, false, false, 4, 4));
        assertFalse(ContinueNavigation.collapseShelf(true, true, false, 4, 4));
        assertFalse(ContinueNavigation.collapseShelf(true, false, true, 4, 4));
        assertFalse(ContinueNavigation.collapseShelf(false, false, false, 4, 4));
    }
}
