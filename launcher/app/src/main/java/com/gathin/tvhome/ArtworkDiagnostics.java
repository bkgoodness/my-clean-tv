package com.gathin.tvhome;

/** Aggregate operation counts only: never stores or logs titles, packages, paths or URI values. */
final class ArtworkDiagnostics {
    private int missing, local, web, file, other, cache, denied, unavailable, invalid, decoded, bound, detached, ready, stale;
    private int remoteRequests, remoteCache, remoteFailed;
    synchronized void sources(String poster, String thumbnail) {
        ContinueArtwork.Source a = ContinueArtwork.source(poster), b = ContinueArtwork.source(thumbnail);
        if (a == ContinueArtwork.Source.MISSING && b == ContinueArtwork.Source.MISSING) missing++;
        count(a); count(b);
    }
    private void count(ContinueArtwork.Source source) {
        switch (source) {
            case LOCAL: local++; break;
            case WEB: web++; break;
            case FILE: file++; break;
            case OTHER: other++; break;
            default: break;
        }
    }
    synchronized void cacheHit() { cache++; }
    synchronized void remoteRequest() { remoteRequests++; }
    synchronized void remoteCacheHit() { remoteCache++; }
    synchronized void remoteFailed() { remoteFailed++; }
    synchronized void denied() { denied++; }
    synchronized void unavailable() { unavailable++; }
    synchronized void decodeFailed() { invalid++; }
    synchronized void decoded() { decoded++; }
    synchronized void bound(boolean beforeAttach) { bound++; if (beforeAttach) detached++; }
    synchronized void renderReady() { ready++; }
    synchronized void stale() { stale++; }
    synchronized String summary() {
        return "operations missing=" + missing + " local=" + local + " web_sources=" + web + " file_skipped=" + file
                + " other_skipped=" + other + " cache=" + cache + " denied=" + denied + " unavailable=" + unavailable
                + " decode_failed=" + invalid + " decoded=" + decoded + " bound=" + bound
                + " bound_detached=" + detached + " render_ready=" + ready + " stale=" + stale
                + " remote_requests=" + remoteRequests + " remote_cache=" + remoteCache + " remote_failed=" + remoteFailed;
    }
}
