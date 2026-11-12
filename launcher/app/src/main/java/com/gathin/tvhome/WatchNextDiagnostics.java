package com.gathin.tvhome;

/** Temporary, explicitly requested aggregate diagnostics. Never retains row data. */
final class WatchNextDiagnostics {
    enum Source { YOUTUBE, YOUTUBE_TV, YOUTUBE_MUSIC, GOOGLE_VIDEO, GOODNESSFLIX, JELLYFIN, OTHER }
    enum Artwork { MISSING, CONTENT, RESOURCE, HTTPS, UNSUPPORTED }
    private final Counts[] counts = new Counts[Source.values().length];
    WatchNextDiagnostics() { for (int i = 0; i < counts.length; i++) counts[i] = new Counts(); }
    static Source source(String pkg) {
        if ("com.google.android.youtube.tv".equals(pkg) || "com.google.android.youtube".equals(pkg)) return Source.YOUTUBE;
        if ("com.google.android.youtube.tvunplugged".equals(pkg) || "com.google.android.apps.youtube.unplugged".equals(pkg)) return Source.YOUTUBE_TV;
        if ("com.google.android.youtube.tvmusic".equals(pkg) || "com.google.android.apps.youtube.music".equals(pkg)) return Source.YOUTUBE_MUSIC;
        if ("com.google.android.videos".equals(pkg)) return Source.GOOGLE_VIDEO;
        if ("org.jellyfin.androidtv.debug".equals(pkg)) return Source.GOODNESSFLIX;
        if ("org.jellyfin.androidtv".equals(pkg)) return Source.JELLYFIN;
        return Source.OTHER;
    }
    static Artwork artwork(String value) {
        ContinueArtwork.Source source = ContinueArtwork.source(value);
        if (source == ContinueArtwork.Source.MISSING) return Artwork.MISSING;
        if (source == ContinueArtwork.Source.LOCAL) return value.trim().regionMatches(true, 0, "content:", 0, 8)
                ? Artwork.CONTENT : Artwork.RESOURCE;
        if (source == ContinueArtwork.Source.WEB) {
            try { ArtworkNetwork.validateUrl(value.trim()); return Artwork.HTTPS; }
            catch (java.io.IOException ignored) { }
        }
        return Artwork.UNSUPPORTED;
    }
    enum Failure { NONE, DENIED, EMPTY, INVALID, IO, OTHER }
    private int probes, opened, boundsOk, reopened, decoded, denied, empty, invalid, io, other;
    void probe(boolean firstOpen, boolean bounds, boolean secondOpen, boolean image, Failure failure) {
        probes++; if (firstOpen) opened++; if (bounds) boundsOk++; if (secondOpen) reopened++; if (image) decoded++;
        switch (failure) {
            case DENIED: denied++; break; case EMPTY: empty++; break; case INVALID: invalid++; break;
            case IO: io++; break; case OTHER: other++; break; default: break;
        }
    }
    void add(Source source, boolean missing, boolean excluded, boolean stale, boolean hidden,
             Artwork poster, Artwork thumbnail, boolean intent) {
        Counts c = counts[source.ordinal()]; c.total++;
        if (missing) c.missing++;
        if (excluded) c.excluded++;
        if (stale) c.stale++;
        if (hidden) c.hidden++;
        if (!missing && !excluded && !stale && !hidden) c.visible++;
        if (source == Source.GOODNESSFLIX || source == Source.YOUTUBE || source == Source.YOUTUBE_TV
                || source == Source.YOUTUBE_MUSIC || source == Source.GOOGLE_VIDEO) {
            c.poster[poster.ordinal()]++; c.thumbnail[thumbnail.ordinal()]++;
            if (intent) c.intent++;
        }
    }
    String summary() {
        StringBuilder b = new StringBuilder("Watch Next diagnostic (filter counts can overlap)");
        int total = 0; for (Counts c : counts) total += c.total;
        b.append(" total=").append(total);
        for (Source source : Source.values()) {
            Counts c = counts[source.ordinal()];
            b.append('\n').append(source.name()).append(" entries=").append(c.total)
                    .append(" missing=").append(c.missing).append(" excluded=").append(c.excluded)
                    .append(" stale=").append(c.stale).append(" hidden=").append(c.hidden).append(" visible=").append(c.visible);
            if (source == Source.GOODNESSFLIX || source == Source.YOUTUBE || source == Source.YOUTUBE_TV
                || source == Source.YOUTUBE_MUSIC || source == Source.GOOGLE_VIDEO) {
                b.append(" intent_present=").append(c.intent);
                for (Artwork a : Artwork.values()) b.append(" poster_").append(a.name()).append('=').append(c.poster[a.ordinal()]);
                for (Artwork a : Artwork.values()) b.append(" thumbnail_").append(a.name()).append('=').append(c.thumbnail[a.ordinal()]);
            }
        }
        b.append("\nGOODNESSFLIX probe attempted=").append(probes).append(" opened=").append(opened)
                .append(" bounds_ok=").append(boundsOk).append(" reopened=").append(reopened).append(" decoded=").append(decoded)
                .append(" denied=").append(denied).append(" empty=").append(empty).append(" invalid=").append(invalid)
                .append(" io_error=").append(io).append(" other_error=").append(other);
        return b.toString();
    }
    private static final class Counts {
        int total, missing, excluded, stale, hidden, visible, intent;
        final int[] poster = new int[Artwork.values().length], thumbnail = new int[Artwork.values().length];
    }
}
