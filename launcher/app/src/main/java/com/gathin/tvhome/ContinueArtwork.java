package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Orders local and permitted HTTPS artwork; no HTTP or file URI is fetched. */
final class ContinueArtwork {
    interface Decoder<T> { T decode(String address) throws Exception; }
    enum Source { MISSING, LOCAL, WEB, FILE, OTHER }
    private ContinueArtwork() { }

    static Source source(String address) {
        if (address == null || address.trim().isEmpty()) return Source.MISSING;
        String value = address.trim(); int colon = value.indexOf(':');
        if (colon <= 0) return Source.OTHER;
        String scheme = value.substring(0, colon).toLowerCase(Locale.ROOT);
        if ("content".equals(scheme) || "android.resource".equals(scheme)) return Source.LOCAL;
        if ("http".equals(scheme) || "https".equals(scheme)) return Source.WEB;
        if ("file".equals(scheme)) return Source.FILE;
        return Source.OTHER;
    }
    static List<String> candidates(String poster, String thumbnail) {
        return candidates(poster, thumbnail, false, false);
    }
    static List<String> candidates(String poster, String thumbnail, boolean episodic, boolean online) {
        List<String> result = new ArrayList<>(2);
        if (episodic) { add(result, thumbnail, online); add(result, poster, online); }
        else { add(result, poster, online); add(result, thumbnail, online); }
        return result;
    }
    private static void add(List<String> result, String address, boolean online) {
        Source source = source(address);
        if (source != Source.LOCAL && !(online && source == Source.WEB && safeHttps(address))) return;
        String value = address.trim();
        if (!result.contains(value)) result.add(value);
    }
    private static boolean safeHttps(String address) {
        try { ArtworkNetwork.validateUrl(address.trim()); return true; } catch (java.io.IOException e) { return false; }
    }
    static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        int sample = 1;
        while (width / sample > 512 || height / sample > 512) sample *= 2;
        return sample;
    }
    static String metadata(String app, String season, String episodeNumber, String episodeTitle) {
        StringBuilder text = new StringBuilder(app == null ? "" : app);
        StringBuilder number = new StringBuilder();
        if (season != null && !season.trim().isEmpty()) number.append(mark(season, "S", "season"));
        if (episodeNumber != null && !episodeNumber.trim().isEmpty()) {
            if (number.length() > 0) number.append(' ');
            number.append(mark(episodeNumber, "E", "episode"));
        }
        if (number.length() > 0) { if (text.length() > 0) text.append(" · "); text.append(number); }
        if (episodeTitle != null && !episodeTitle.trim().isEmpty()) { if (text.length() > 0) text.append(" · "); text.append(episodeTitle.trim()); }
        return text.toString();
    }
    private static String mark(String value, String prefix, String longPrefix) {
        String trimmed = value.trim(); String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(longPrefix) || (lower.startsWith(prefix.toLowerCase(Locale.ROOT))
                && trimmed.length() > 1 && Character.isDigit(trimmed.charAt(1)))) return trimmed;
        return prefix + trimmed;
    }
    static <T> T firstAvailable(List<String> addresses, Decoder<T> decoder) {
        for (String address : addresses) try {
            T result = decoder.decode(address);
            if (result != null) return result;
        } catch (Exception ignored) { }
        return null;
    }
    static boolean bindingCurrent(boolean closed, int currentGeneration, int requestedGeneration,
                                  Object currentToken, Object requestedToken) {
        return !closed && currentGeneration == requestedGeneration && currentToken == requestedToken;
    }
}
