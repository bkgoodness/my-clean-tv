package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class WallpaperLibraryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
    private static final byte[] PNG = {(byte) 0x89, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] WEBP = {'R','I','F','F',0,0,0,0,'W','E','B','P'};
    private static void write(File file, byte[] data) throws IOException { try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); } }
    private static WallpaperLibrary.ImportResult add(File dir, File cache, byte[] data) throws IOException {
        return WallpaperLibrary.importImage(dir, cache, new ByteArrayInputStream(data), (file, format) -> true);
    }
    @Test public void sourceIdsSeparateSameNamesAndLegacyStillSelectsTheme() throws Exception {
        File theme = tmp.newFolder("theme"), user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        File themeImages = new File(theme, "wallpapers"); assertTrue(themeImages.mkdir());
        WallpaperLibrary.ImportResult imported = add(user, cache, JPEG);
        File single = new File(theme, "wallpaper.jpg"); write(single, JPEG);
        File sameName = new File(themeImages, imported.file.getName()); write(sameName, JPEG);
        List<WallpaperLibrary.Item> items = WallpaperLibrary.entries(theme, user);
        assertEquals(3, items.size());
        assertEquals(Collections.singletonList(imported.file), WallpaperLibrary.chosen(items, WallpaperLibrary.USER + imported.file.getName()));
        assertEquals(Collections.singletonList(sameName), WallpaperLibrary.chosen(items, WallpaperLibrary.THEME + sameName.getName()));
        assertEquals(Collections.singletonList(sameName), WallpaperLibrary.chosen(items, sameName.getName()));
        assertEquals(Collections.singletonList(single), WallpaperLibrary.chosen(items, "wallpaper.jpg"));
        assertTrue(WallpaperLibrary.chosen(items, "none").isEmpty());
        assertNull(WallpaperLibrary.chosen(items, null));
        assertNull(WallpaperLibrary.chosen(items, "user:missing.jpg"));
        // Reading a saved choice again after a simulated restart makes the same selection.
        assertEquals(imported.file, WallpaperLibrary.chosen(WallpaperLibrary.entries(theme, user), WallpaperLibrary.USER + imported.file.getName()).get(0));
    }
    @Test public void supportsFormatsDeduplicatesAndDeletesOnlyUserCopy() throws Exception {
        File theme = tmp.newFolder("theme"), user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        File original = new File(theme, "wallpaper.jpg"); write(original, JPEG);
        WallpaperLibrary.ImportResult a = add(user, cache, JPEG);
        assertTrue(add(user, cache, JPEG).duplicate);
        assertFalse(a.duplicate);
        assertTrue(add(user, cache, PNG).file.getName().endsWith(".png"));
        assertTrue(add(user, cache, WEBP).file.getName().endsWith(".webp"));
        List<WallpaperLibrary.Item> items = WallpaperLibrary.entries(theme, user);
        assertFalse(WallpaperLibrary.deleteUser(user, items.get(0)));
        assertFalse(WallpaperLibrary.deleteUser(user, new WallpaperLibrary.Item("user:" + original.getName(), original, true)));
        WallpaperLibrary.Item imported = items.stream().filter(i -> i.file.equals(a.file)).findFirst().get();
        assertTrue(WallpaperLibrary.deleteUser(user, imported));
        assertTrue(original.isFile()); assertFalse(a.file.exists());
    }
    @Test public void damagedDuplicateDoesNotOverwriteTheExistingCopy() throws Exception {
        File user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        WallpaperLibrary.ImportResult existing = add(user, cache, JPEG);
        write(existing.file, new byte[]{9, 8, 7});
        try { add(user, cache, JPEG); fail(); } catch (IOException expected) { }
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(existing.file.toPath()));
        assertEquals(0, cache.list().length);
    }
    @Test public void rejectsBadOrUnreadableImagesWithoutPartialFiles() throws Exception {
        File user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        try { add(user, cache, new byte[]{1,2,3}); fail(); } catch (IOException expected) { }
        try { WallpaperLibrary.importImage(user, cache, new ByteArrayInputStream(JPEG), (f, format) -> false); fail(); } catch (IOException expected) { }
        InputStream broken = new InputStream() { public int read() throws IOException { throw new IOException("broken"); } };
        try { WallpaperLibrary.importImage(user, cache, broken, (f, format) -> true); fail(); } catch (IOException expected) { }
        assertEquals(0, user.list().length); assertEquals(0, cache.list().length);
    }
    @Test public void enforcesCountAndAllowsExistingDuplicateAtCapacity() throws Exception {
        File user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        WallpaperLibrary.ImportResult existing = add(user, cache, JPEG);
        for (int n = 0; n < 99; n++) write(new File(user, "other" + n), new byte[]{1});
        assertTrue(add(user, cache, JPEG).duplicate);
        try { add(user, cache, PNG); fail(); } catch (IOException expected) { }
        assertTrue(existing.file.isFile()); assertEquals(100, user.list().length);
    }
    @Test public void enforcesAggregateLimitAndLeavesExistingFilesAlone() throws Exception {
        File user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        File existing = new File(user, "existing");
        try (RandomAccessFile file = new RandomAccessFile(existing, "rw")) { file.setLength(WallpaperLibrary.MAX_TOTAL - JPEG.length); }
        assertNotNull(add(user, cache, JPEG).file);
        try { add(user, cache, PNG); fail(); } catch (IOException expected) { }
        assertEquals(WallpaperLibrary.MAX_TOTAL - JPEG.length, existing.length());
        assertEquals(2, user.list().length);
    }
    @Test public void acceptsAnImageExactlyAtPerFileLimit() throws Exception {
        File user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        byte[] input = new byte[(int) WallpaperLibrary.MAX_FILE];
        System.arraycopy(JPEG, 0, input, 0, JPEG.length);
        WallpaperLibrary.ImportResult result = add(user, cache, input);
        assertEquals(WallpaperLibrary.MAX_FILE, result.file.length());
        assertEquals(0, cache.list().length);
    }
    @Test public void rejectsOversizedInputWithoutPartialCommit() throws Exception {
        File user = tmp.newFolder("user"), cache = tmp.newFolder("cache");
        InputStream big = new InputStream() { long left = WallpaperLibrary.MAX_FILE + 1; public int read() { return left-- > 0 ? 1 : -1; }
            public int read(byte[] b, int off, int len) { if (left <= 0) return -1; int n = (int) Math.min(left, len); java.util.Arrays.fill(b, off, off+n, (byte) 1); left -= n; return n; } };
        try { WallpaperLibrary.importImage(user, cache, big, (f, format) -> true); fail(); } catch (IOException expected) { }
        assertEquals(0, user.list().length); assertEquals(0, cache.list().length);
    }
    @Test public void symlinksCannotDeleteThemeFiles() throws Exception {
        File user = tmp.newFolder("user"), theme = tmp.newFolder("theme");
        File original = new File(theme, "wallpaper.jpg"); write(original, JPEG);
        File link = new File(user, "a".repeat(64) + ".jpg");
        Files.createSymbolicLink(link.toPath(), original.toPath());
        assertFalse(WallpaperLibrary.deleteUser(user, new WallpaperLibrary.Item("user:" + link.getName(), link, true)));
        assertTrue(original.isFile());
    }
}
