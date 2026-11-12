package com.gathin.tvhome;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Disposable app-private cache; filenames contain only a hash of the provider URL. */
final class ArtworkDiskCache {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private final File dir;
    ArtworkDiskCache(File dir) { this.dir = dir; }
    private File file(String url) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(64);
            for (byte b : digest) name.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return new File(dir, name.toString());
        } catch (Exception e) { throw new IOException("Cache unavailable", e); }
    }
    synchronized byte[] get(String url, long now) throws IOException {
        File f = file(url);
        if (!f.isFile()) return null;
        if (f.length() <= 8 || f.length() > ArtworkNetwork.MAX_BYTES + 8L) { f.delete(); return null; }
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            long created = in.readLong();
            if (now < created || now - created > MAX_AGE_MS) { f.delete(); return null; }
            byte[] bytes = ArtworkNetwork.readToEnd(in);
            f.setLastModified(now);
            return bytes;
        } catch (IOException e) { f.delete(); return null; }
    }
    synchronized void remove(String url) throws IOException { file(url).delete(); }
    synchronized void put(String url, byte[] bytes, long now) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > ArtworkNetwork.MAX_BYTES) throw new IOException("Invalid cache entry");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cache unavailable");
        File target = file(url), temp = File.createTempFile("art-", ".tmp", dir);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                DataOutputStream data = new DataOutputStream(out); data.writeLong(now); data.write(bytes); data.flush(); out.getFD().sync();
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            target.setLastModified(now); trim();
        } finally { if (temp.exists()) temp.delete(); }
    }
    private void trim() {
        File[] temporary = dir.listFiles(f -> f.isFile() && f.getName().startsWith("art-") && f.getName().endsWith(".tmp"));
        if (temporary != null) for (File f : temporary) f.delete();
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().matches("[0-9a-f]{64}"));
        if (files == null) return;
        long total = 0; for (File f : files) total += f.length();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) if (total > MAX_BYTES) { long n = f.length(); if (f.delete()) total -= n; }
    }
}
