package com.gathin.tvhome;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ArtworkNetworkTest {
    private static void rejected(String uri) throws Exception { try { ArtworkNetwork.validateUrl(uri); fail(uri); } catch (IOException expected) { } }
    private static InputStream input(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII)); }
    @Test public void acceptsOnlyCredentialFreeHttpsOnStandardPort() throws Exception {
        assertEquals("images.example.com", ArtworkNetwork.validateUrl("https://images.example.com/a?sig=1").getHost());
        for(String s:new String[]{"http://images.example.com/x", "https://user:pass@images.example.com/x",
                "https://127.0.0.1/x", "https://[::1]/x", "https://router.local/x", "https://example.com:8443/x",
                "https://example.com/x#fragment"}) rejected(s);
    }
    @Test public void everyRedirectIsRevalidated() throws Exception {
        URI start=ArtworkNetwork.validateUrl("https://images.example.com/a");
        assertEquals("https://cdn.example.org/b", ArtworkNetwork.redirect(start,"https://cdn.example.org/b").toString());
        assertEquals("https://images.example.com/b", ArtworkNetwork.redirect(start,"/b").toString());
        for(String target:new String[]{"http://cdn.example.org/a", "https://127.0.0.1/a", "https://router.local/a", "https://u:p@cdn.example.org/a"})
            try { ArtworkNetwork.redirect(start,target); fail(target); } catch(IOException expected) { }
    }
    @Test public void rejectsPrivateSpecialMixedAndMappedDestinations() throws Exception {
        for(String ip:new String[]{"127.0.0.1","10.0.0.1","172.16.0.1","192.168.0.1","169.254.1.2","100.64.0.1",
                "192.0.2.1","198.51.100.1","203.0.113.1","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","::ffff:127.0.0.1"})
            assertFalse(ip,ArtworkNetwork.publicAddress(InetAddress.getByName(ip)));
        assertTrue(ArtworkNetwork.publicAddress(InetAddress.getByName("8.8.8.8")));
        try { ArtworkNetwork.validateAddresses(new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("10.0.0.1")}); fail(); } catch(IOException expected) { }
    }
    @Test public void fallbackPrefersTheOtherAddressFamily() throws Exception {
        InetAddress v4 = InetAddress.getByName("8.8.8.8"), v4b = InetAddress.getByName("1.1.1.1");
        InetAddress v6 = InetAddress.getByName("2001:4860:4860::8888");
        assertEquals(v6, ArtworkNetwork.alternateAddress(new InetAddress[]{v4, v4b, v6}));
        assertEquals(v4b, ArtworkNetwork.alternateAddress(new InetAddress[]{v4, v4b}));
        assertNull(ArtworkNetwork.alternateAddress(new InetAddress[]{v4}));
    }
    @Test public void boundsAndParsesFixedAndChunkedResponses() throws Exception {
        ArtworkNetwork.Response fixed=ArtworkNetwork.readResponse(input("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc"));
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII),fixed.body);
        ArtworkNetwork.Response chunks=ArtworkNetwork.readResponse(input("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"));
        assertArrayEquals(fixed.body,chunks.body);
        for(String response:new String[]{"HTTP/1.1 200 OK\r\nContent-Length: 4194305\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n"})
            try { ArtworkNetwork.readResponse(input(response)); fail(); } catch(IOException expected) { }
    }
    @Test public void unboundedResponseCannotExceedFourMiB() throws Exception {
        try { ArtworkNetwork.readToEnd(new ByteArrayInputStream(new byte[ArtworkNetwork.MAX_BYTES + 1])); fail(); }
        catch (IOException expected) { }
        try { ArtworkNetwork.readResponse(input("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n400001\r\n")); fail(); }
        catch (IOException expected) { }
    }
    @Test public void turningOffClosesInFlightConnections() throws Exception {
        class TrackedSocket extends Socket { boolean closed; @Override public synchronized void close() { closed = true; } }
        ArtworkNetwork net = new ArtworkNetwork();
        java.lang.reflect.Method begin = ArtworkNetwork.class.getDeclaredMethod("begin"); begin.setAccessible(true);
        java.lang.reflect.Method track = ArtworkNetwork.class.getDeclaredMethod("track", Socket.class, long.class); track.setAccessible(true);
        long generation = (Long) begin.invoke(net); TrackedSocket socket = new TrackedSocket(); track.invoke(net, socket, generation);
        net.setEnabled(false); assertTrue(socket.closed); assertFalse(net.enabled());
        try { track.invoke(net, new TrackedSocket(), generation); fail(); }
        catch (java.lang.reflect.InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IOException); }
        net.close();
    }
    @Test public void readTimeoutAndDisabledSettingFailClosed() throws Exception {
        InputStream timeout=new InputStream(){public int read() throws IOException {throw new SocketTimeoutException();}};
        try { ArtworkNetwork.readResponse(timeout); fail(); } catch(SocketTimeoutException expected) { }
        ArtworkNetwork net=new ArtworkNetwork(); net.setEnabled(false);
        try { net.fetch("https://images.example.com/x"); fail(); } catch(IOException expected) { }
        assertFalse(net.enabled()); net.close();
    }
}
