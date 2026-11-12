package com.gathin.tvhome;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Source-qualified wallpaper selection and private user-image storage. */
final class WallpaperLibrary {
    static final String USER_DIR = "user-wallpapers";
    static final int MAX_COUNT = 100;
    static final long MAX_FILE = 10L * 1024 * 1024;
    static final long MAX_TOTAL = 250L * 1024 * 1024;
    static final String USER = "user:", THEME = "theme:", THEME_SINGLE = "theme-single";
    private static final Object LOCK = new Object();
    interface Validator { boolean valid(File file, String format); }

    static final class Item {
        final String id;
        final File file;
        final boolean user;
        Item(String id, File file, boolean user) { this.id = id; this.file = file; this.user = user; }
    }
    static final class ImportResult {
        final File file;
        final boolean duplicate;
        ImportResult(File file, boolean duplicate) { this.file = file; this.duplicate = duplicate; }
    }
    private WallpaperLibrary() { }

    static List<Item> entries(File themeDir, File userDir) {
        List<Item> result = new ArrayList<>();
        File single = themeDir == null ? null : new File(themeDir, "wallpaper.jpg");
        if (single != null && single.isFile() && single.length() > 0) result.add(new Item(THEME_SINGLE, single, false));
        for (File file : WallpaperRotation.images(themeDir == null ? null : new File(themeDir, "wallpapers")))
            result.add(new Item(THEME + file.getName(), file, false));
        List<File> userFiles = new ArrayList<>();
        File[] files = userDir == null ? null : userDir.listFiles();
        if (files != null) for (File file : files) if (safeUserFile(userDir, file)) userFiles.add(file);
        userFiles.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (File file : userFiles) result.add(new Item(USER + file.getName(), file, true));
        return result;
    }

    /** Null means theme default; an empty list means None. Legacy filenames still refer to theme images. */
    static List<File> chosen(List<Item> entries, String choice) {
        if (choice == null) return null;
        if (WallpaperRotation.CHOICE_NONE.equals(choice)) return Collections.emptyList();
        for (Item item : entries) if (item.id.equals(choice)) return Collections.singletonList(item.file);
        if (!choice.startsWith(USER) && !choice.startsWith(THEME) && !choice.equals(THEME_SINGLE)) {
            // The old picker listed the single wallpaper before a same-named collection image.
            for (Item item : entries) if (!item.user && item.file.getName().equals(choice))
                return Collections.singletonList(item.file);
        }
        return null;
    }

    static boolean safeUserFile(File directory, File file) {
        if (directory == null || file == null || !file.getName().matches("[0-9a-f]{64}\\.(jpg|png|webp)")) return false;
        try {
            return file.isFile() && directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile());
        } catch (IOException e) { return false; }
    }
    static boolean deleteUser(File directory, Item item) {
        synchronized (LOCK) {
            return item != null && item.user && item.id.equals(USER + item.file.getName())
                    && safeUserFile(directory, item.file) && item.file.delete();
        }
    }

    /** A later import source provides a stream; no original file is ever modified. */
    static ImportResult importImage(File userDir, File cacheDir, InputStream source, Validator validator) throws IOException {
        synchronized (LOCK) {
            if (userDir == null || cacheDir == null || source == null || validator == null) throw new IOException("Missing import source");
            if ((!userDir.isDirectory() && !userDir.mkdirs()) || (!cacheDir.isDirectory() && !cacheDir.mkdirs()))
                throw new IOException("Wallpaper storage unavailable");
            File temp = File.createTempFile("wallpaper-", ".tmp", cacheDir);
            try {
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException e) { throw new IOException("Digest unavailable", e); }
                long bytes = 0;
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = source.read(buffer)) != -1) {
                        if (n == 0) throw new IOException("Import stream stalled");
                        bytes += n;
                        if (bytes > MAX_FILE) throw new IOException("Image exceeds 10 MiB");
                        out.write(buffer, 0, n); digest.update(buffer, 0, n);
                    }
                    out.getFD().sync();
                }
                String format = format(temp);
                if (bytes == 0 || format == null || !validator.valid(temp, format)) throw new IOException("Unsupported or unreadable image");
                StringBuilder name = new StringBuilder();
                for (byte b : digest.digest()) name.append(String.format(Locale.ROOT, "%02x", b & 255));
                File target = new File(userDir, name + "." + format);
                if (safeUserFile(userDir, target)) {
                    if (!sameDigest(target, name.toString())) throw new IOException("Existing wallpaper is damaged");
                    return new ImportResult(target, true);
                }
                File[] existing = userDir.listFiles();
                if (existing == null) throw new IOException("Wallpaper storage unavailable");
                int count = 0; long total = 0;
                for (File file : existing) if (file.isFile()) { count++; total += file.length(); }
                if (count >= MAX_COUNT || total > MAX_TOTAL - bytes) throw new IOException("Wallpaper library is full");
                if (target.exists()) throw new IOException("Wallpaper destination already exists");
                // Internal cache and files share a filesystem. If atomic move is unavailable, fail safely.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                return new ImportResult(target, false);
            } finally { if (temp.exists()) temp.delete(); }
        }
    }

    private static boolean sameDigest(File file, String expected) throws IOException {
        if (file.length() <= 0 || file.length() > MAX_FILE) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = in.read(buffer)) != -1) if (n > 0) digest.update(buffer, 0, n);
            }
            StringBuilder actual = new StringBuilder();
            for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
            return expected.contentEquals(actual);
        } catch (NoSuchAlgorithmException e) { throw new IOException("Digest unavailable", e); }
    }

    static String format(File file) throws IOException {
        byte[] h = new byte[12]; int n = 0;
        try (InputStream in = new FileInputStream(file)) { int x; while (n < h.length && (x = in.read(h, n, h.length - n)) > 0) n += x; }
        if (n >= 3 && (h[0] & 255) == 255 && (h[1] & 255) == 216 && (h[2] & 255) == 255) return "jpg";
        if (n >= 8 && (h[0] & 255) == 137 && h[1] == 80 && h[2] == 78 && h[3] == 71 && h[4] == 13 && h[5] == 10 && h[6] == 26 && h[7] == 10) return "png";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "webp";
        return null;
    }
}
