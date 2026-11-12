package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchNextDiagnosticsTest {
 @Test public void aggregateCountsCanOverlap() {
  WatchNextDiagnostics d=new WatchNextDiagnostics();
  assertEquals(WatchNextDiagnostics.Source.YOUTUBE,WatchNextDiagnostics.source("com.google.android.youtube.tv"));
  assertEquals(WatchNextDiagnostics.Source.OTHER,WatchNextDiagnostics.source("com.google.android.youtube.tv.spoof"));
  d.add(WatchNextDiagnostics.Source.YOUTUBE,false,true,true,false,WatchNextDiagnostics.Artwork.MISSING,WatchNextDiagnostics.Artwork.MISSING,false);
  d.add(WatchNextDiagnostics.Source.GOODNESSFLIX,true,false,false,true,WatchNextDiagnostics.Artwork.CONTENT,WatchNextDiagnostics.Artwork.HTTPS,true);
  String s=d.summary();
  assertTrue(s.contains("total=2"));
  assertTrue(s.contains("YOUTUBE entries=1 missing=0 excluded=1 stale=1 hidden=0"));
  assertTrue(s.contains("GOODNESSFLIX entries=1 missing=1 excluded=0 stale=0 hidden=1 visible=0 intent_present=1"));
  assertTrue(s.contains("poster_CONTENT=1")); assertTrue(s.contains("thumbnail_HTTPS=1"));
 }
 @Test public void privateStringsNeverAppearInOutput() {
  String secret="private-token-do-not-log";
  assertEquals(WatchNextDiagnostics.Artwork.CONTENT,WatchNextDiagnostics.artwork("content://example/"+secret));
  assertEquals(WatchNextDiagnostics.Artwork.HTTPS,WatchNextDiagnostics.artwork("https://example.com/"+secret));
  assertEquals(WatchNextDiagnostics.Artwork.UNSUPPORTED,WatchNextDiagnostics.artwork("http://127.0.0.1/"+secret));
  assertEquals(WatchNextDiagnostics.Artwork.MISSING,WatchNextDiagnostics.artwork(null));
  assertEquals(WatchNextDiagnostics.Artwork.RESOURCE,WatchNextDiagnostics.artwork("android.resource://example/1"));
  WatchNextDiagnostics d=new WatchNextDiagnostics();
  d.add(WatchNextDiagnostics.source("unknown."+secret),false,false,false,false,WatchNextDiagnostics.artwork("content://example/"+secret),WatchNextDiagnostics.Artwork.MISSING,false);
  assertFalse(d.summary().contains(secret));
 }
}
