package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Random;

/** Explicit slideshow choices are separate from replaceable theme configuration. */
final class WallpaperSlideshow {
    static final String ENABLED = "wallpaper_slideshow_enabled";
    static final String INTERVAL = "wallpaper_slideshow_minutes";
    static final String ORDER = "wallpaper_slideshow_order";
    static final String EXCLUDED = "wallpaper_slideshow_excluded";
    static final int[] INTERVALS = {5, 15, 30, 60, 1440};
    private WallpaperSlideshow() { }

    static int interval(int requested) {
        for (int value : INTERVALS) if (value == requested) return value;
        return 30;
    }
    static List<File> included(List<WallpaperLibrary.Item> items, Set<String> excluded) {
        List<File> files = new ArrayList<>();
        for (WallpaperLibrary.Item item : items)
            if (!excluded.contains(item.id) && item.file.isFile() && item.file.length() > 0) files.add(item.file);
        return files;
    }
    static List<File> nextCandidates(boolean rotating, boolean explicit, List<File> themeFiles,
                                     List<WallpaperLibrary.Item> items, Set<String> excluded) {
        return rotating && !explicit ? themeFiles : included(items, excluded);
    }
    static File next(List<File> candidates, String previous, String order, Random random) {
        List<File> available = new ArrayList<>();
        for (File file : candidates) if (file.isFile() && file.length() > 0) available.add(file);
        return WallpaperRotation.pick(available, previous, order, random);
    }
    static File current(List<WallpaperLibrary.Item> items, String previous) {
        return WallpaperRotation.find(included(items, java.util.Collections.emptySet()), previous);
    }
    static String idFor(List<WallpaperLibrary.Item> items, File file) {
        if (file != null) for (WallpaperLibrary.Item item : items) if (item.file.equals(file)) return item.id;
        return null;
    }
    static boolean rotating(Boolean userEnabled, int themeMinutes, int count) {
        return userEnabled != null ? userEnabled : themeMinutes > 0 && count > 1;
    }
}
