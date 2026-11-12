package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.*;

public class WallpaperSlideshowTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File image(File dir, String name) throws Exception { File f = new File(dir, name); Files.write(f.toPath(), new byte[]{1}); return f; }
    @Test public void themeDefaultsRemainUntilExplicitlyChanged() {
        assertFalse(WallpaperSlideshow.rotating(null, 0, 4));
        assertTrue(WallpaperSlideshow.rotating(null, 15, 4));
        assertFalse(WallpaperSlideshow.rotating(null, 15, 1));
        assertTrue(WallpaperSlideshow.rotating(true, 0, 0));
        assertFalse(WallpaperSlideshow.rotating(false, 30, 4));
    }
    @Test public void offeredIntervalsArePreservedAndInvalidFallsBack() {
        for (int n : new int[]{5,15,30,60,1440}) assertEquals(n, WallpaperSlideshow.interval(n));
        assertEquals(30, WallpaperSlideshow.interval(-1));
        assertEquals(30, WallpaperSlideshow.interval(120));
    }
    @Test public void exclusionUsesStableSourceIdsAcrossReloadAndThemeChanges() throws Exception {
        File theme = tmp.newFolder("theme"), user = tmp.newFolder("user");
        File walls = new File(theme, "wallpapers"); assertTrue(walls.mkdir());
        File a = image(walls, "same.jpg"); File b = image(user, "a".repeat(64)+".jpg");
        List<WallpaperLibrary.Item> initial = WallpaperLibrary.entries(theme, user);
        Set<String> excluded = new HashSet<>(); excluded.add("theme:same.jpg");
        assertEquals(Collections.singletonList(b), WallpaperSlideshow.included(initial, excluded));
        assertEquals(Collections.singletonList(b), WallpaperSlideshow.included(WallpaperLibrary.entries(theme, user), excluded));
        File newTheme = tmp.newFolder("another-theme"); File newWalls = new File(newTheme, "wallpapers"); assertTrue(newWalls.mkdir());
        image(newWalls, "different.jpg");
        assertEquals(2, WallpaperSlideshow.included(WallpaperLibrary.entries(newTheme, user), excluded).size());
        assertTrue(a.delete()); assertEquals(Collections.singletonList(b), WallpaperSlideshow.included(initial, Collections.emptySet()));
    }
    @Test public void sourceQualifiedPlaybackAvoidsSameNameCollisionsAndSupportsLegacyNames() throws Exception {
        File one = image(tmp.newFolder("one"), "same.jpg"), two = image(tmp.newFolder("two"), "same.jpg");
        List<File> files = Arrays.asList(one, two);
        assertEquals(one, WallpaperRotation.find(files, "same.jpg"));
        assertEquals(two, WallpaperRotation.find(files, two.getPath()));
        assertEquals(two, WallpaperRotation.pick(files, "same.jpg", WallpaperRotation.ORDER_SHUFFLE, new Random(1)));
        assertEquals(two, WallpaperRotation.pick(files, one.getPath(), WallpaperRotation.ORDER_SEQUENTIAL, new Random(1)));
        assertEquals(one, WallpaperRotation.pick(files, two.getPath(), WallpaperRotation.ORDER_SHUFFLE, new Random(1)));
        assertNull(WallpaperRotation.pick(Collections.emptyList(), "", WallpaperRotation.ORDER_SEQUENTIAL, new Random()));
    }
    @Test public void manualNextAdvancesRepeatedlyAndSkipsUnavailableImages() throws Exception {
        File dir = tmp.newFolder("next");
        File a = image(dir, "a.jpg"), b = image(dir, "b.jpg"), c = image(dir, "c.jpg");
        List<File> files = Arrays.asList(a, b, c);
        Random random = new Random(1);
        File first = WallpaperSlideshow.next(files, a.getPath(), WallpaperRotation.ORDER_SEQUENTIAL, random);
        File second = WallpaperSlideshow.next(files, first.getPath(), WallpaperRotation.ORDER_SEQUENTIAL, random);
        assertEquals(b, first); assertEquals(c, second);
        assertTrue(b.delete());
        assertEquals(c, WallpaperSlideshow.next(files, a.getPath(), WallpaperRotation.ORDER_SEQUENTIAL, random));
        assertTrue(a.delete()); assertTrue(c.delete());
        assertNull(WallpaperSlideshow.next(files, "", WallpaperRotation.ORDER_SHUFFLE, random));
    }
    @Test public void nextHonorsExplicitExclusionsAndUnchangedThemeDefaults() throws Exception {
        File a = image(tmp.newFolder("theme-next"), "a.jpg"), b = image(tmp.newFolder("user-next"), "b.jpg");
        List<WallpaperLibrary.Item> items = Arrays.asList(new WallpaperLibrary.Item("theme:a.jpg", a, false),
                new WallpaperLibrary.Item("user:b.jpg", b, true));
        Set<String> allExcluded = new HashSet<>(Arrays.asList("theme:a.jpg", "user:b.jpg"));
        assertTrue(WallpaperSlideshow.nextCandidates(true, true, Arrays.asList(a), items, allExcluded).isEmpty());
        assertEquals(b, WallpaperSlideshow.current(items, b.getPath()));
        assertNull(WallpaperSlideshow.current(items, "missing.jpg"));
        assertEquals(Collections.singletonList(a), WallpaperSlideshow.nextCandidates(true, false, Collections.singletonList(a), items, allExcluded));
        assertEquals(Arrays.asList(a,b), WallpaperSlideshow.nextCandidates(false, true, Collections.singletonList(a), items, Collections.emptySet()));
    }
    @Test public void itemMappingNeverConfusesThemeAndUserSources() throws Exception {
        File a = image(tmp.newFolder("t"), "same.jpg"), b = image(tmp.newFolder("u"), "same.jpg");
        List<WallpaperLibrary.Item> list = Arrays.asList(new WallpaperLibrary.Item("theme:same.jpg", a, false),
                new WallpaperLibrary.Item("user:same.jpg", b, true));
        assertEquals("user:same.jpg", WallpaperSlideshow.idFor(list, b));
        assertEquals("theme:same.jpg", WallpaperSlideshow.idFor(list, a));
        assertNull(WallpaperSlideshow.idFor(list, null));
    }
}
