package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Only local content/resource artwork is read; no web or file URI is fetched. */
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
        List<String> result = new ArrayList<>(2);
        add(result, poster); add(result, thumbnail);
        return result;
    }
    private static void add(List<String> result, String address) {
        if (source(address) != Source.LOCAL) return;
        String value = address.trim();
        if (!result.contains(value)) result.add(value);
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
