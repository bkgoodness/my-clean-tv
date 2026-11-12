package com.gathin.tvhome;

import java.util.List;

/** Small, deterministic focus rules for the Watch Next shelf. */
final class ContinueNavigation {
    private ContinueNavigation() { }
    static boolean horizontalEdge(int position, int count, boolean rtl, boolean left) {
        if (position < 0 || position >= count) return true;
        boolean towardStart = left != rtl;
        return towardStart ? position == 0 : position == count - 1;
    }
    static int restoredIndex(List<String> keys, String previousKey, int previousIndex) {
        if (keys.isEmpty()) return -1;
        if (previousKey != null) {
            int found = keys.indexOf(previousKey);
            if (found >= 0) return found;
        }
        return Math.max(0, Math.min(previousIndex, keys.size() - 1));
    }
    static boolean topDownToShelf(boolean hasEntries, boolean reordering) {
        return hasEntries && !reordering;
    }
    static boolean appUpToShelf(int position, int columns, boolean singleRow, boolean reordering) {
        return !reordering && position >= 0 && (singleRow || columns > 0 && position < columns);
    }
    static int tileSizingHeight(int gridHeight, boolean collapsed, int shelfHeight) {
        return collapsed ? Math.max(1, gridHeight - Math.max(0, shelfHeight)) : gridHeight;
    }
    static boolean collapseShelf(boolean hasEntries, boolean singleRow, boolean reordering, int position, int columns) {
        return hasEntries && !singleRow && !reordering && columns > 0 && position >= columns;
    }
}
