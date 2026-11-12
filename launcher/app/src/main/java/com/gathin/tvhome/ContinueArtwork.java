package com.gathin.tvhome;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/** Read only local provider artwork. Web and file URIs are intentionally not fetched. */
final class ContinueArtwork {
    interface Decoder<T> { T decode(String address) throws Exception; }
    private ContinueArtwork() { }

    static List<String> candidates(String poster, String thumbnail) {
        List<String> result = new ArrayList<>(2);
        add(result, poster); add(result, thumbnail);
        return result;
    }
    private static void add(List<String> result, String address) {
        if (address == null || address.trim().isEmpty() || result.contains(address)) return;
        try {
            String scheme = new URI(address).getScheme();
            if ("content".equals(scheme) || "android.resource".equals(scheme)) result.add(address);
        } catch (URISyntaxException ignored) { }
    }
    static <T> T firstAvailable(List<String> addresses, Decoder<T> decoder) {
        for (String address : addresses) try {
            T result = decoder.decode(address);
            if (result != null) return result;
        } catch (Exception ignored) { }
        return null;
    }
}
