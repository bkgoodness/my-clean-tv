package com.gathin.tvhome;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Bounded HTTPS client. Resolve once, reject non-public addresses, then connect to that exact address. */
final class ArtworkNetwork implements AutoCloseable {
    static final int MAX_BYTES = 4 * 1024 * 1024, TIMEOUT_MS = 7000, MAX_REDIRECTS = 3;
    private static final ExecutorService DNS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "artwork-dns"); t.setDaemon(true); return t; });
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "artwork-deadlines"); t.setDaemon(true); return t;
    });
    private final Object lock = new Object();
    private final Set<Socket> sockets = new HashSet<>();
    private boolean enabled = true;
    private long generation;
    void setEnabled(boolean value) {
        synchronized (lock) {
            if (enabled == value) return;
            enabled = value; generation++;
            if (!value) for (Socket s : sockets) try { s.close(); } catch (IOException ignored) { }
        }
    }
    boolean enabled() { synchronized (lock) { return enabled; } }
    private void check(long request) throws IOException { synchronized (lock) { if (!enabled || request != generation) throw new IOException("Disabled"); } }
    private long begin() throws IOException { synchronized (lock) { if (!enabled) throw new IOException("Disabled"); return generation; } }
    static URI validateUrl(String address) throws IOException {
        try {
            if (address == null || address.length() > 4096) throw new IOException("Invalid URL");
            URI u = new URI(address);
            String host = u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || host == null || host.length() > 253
                    || u.getRawUserInfo() != null || u.getRawFragment() != null || (u.getPort() != -1 && u.getPort() != 443)
                    || host.indexOf(':') >= 0 || host.matches("[0-9.]+") || !host.contains(".")
                    || host.endsWith(".") || !host.matches("[A-Za-z0-9.-]+")) throw new IOException("Unsafe URL");
            String h = host.toLowerCase(Locale.ROOT);
            if (h.endsWith(".local") || h.endsWith(".localhost") || h.endsWith(".internal") || h.endsWith(".lan")) throw new IOException("Local host");
            String path = u.getRawPath(); if (path != null && (path.contains("\r") || path.contains("\n"))) throw new IOException("Invalid path");
            return new URI(u.toASCIIString());
        } catch (URISyntaxException | IllegalArgumentException e) { throw new IOException("Invalid URL", e); }
    }
    static URI redirect(URI from, String location) throws IOException {
        try { return validateUrl(from.resolve(new URI(location)).toASCIIString()); }
        catch (URISyntaxException | IllegalArgumentException e) { throw new IOException("Invalid redirect", e); }
    }
    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (b.length == 4) {
            int a=b[0]&255, c=b[1]&255, d=b[2]&255;
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a==100 && c>=64 && c<=127) && !(a==169 && c==254)
                    && !(a==172 && c>=16 && c<=31) && !(a==192 && c==168)
                    && !(a==192 && c==0 && (d==0 || d==2)) && !(a==192 && c==88 && d==99)
                    && !(a==198 && (c==18 || c==19 || c==51 && d==100))
                    && !(a==203 && c==0 && d==113);
        }
        if (b.length != 16 || (b[0]&0xe0) != 0x20) return false;
        int a=b[0]&255, c=b[1]&255, d=b[2]&255, e=b[3]&255;
        return !(a==0x20 && c==0x02) && !(a==0x20 && c==0x01 && d==0x0d && e==0xb8)
                && !(a==0x20 && c==0x01 && d<=1);
    }
    static void validateAddresses(InetAddress[] addresses) throws IOException {
        if (addresses == null || addresses.length == 0) throw new IOException("No address");
        for (InetAddress address : addresses) if (!publicAddress(address)) throw new IOException("Non-public destination");
    }
    private InetAddress[] resolve(String host, long request) throws IOException {
        check(request); Future<InetAddress[]> task;
        try { task = DNS.submit(() -> InetAddress.getAllByName(host)); }
        catch (RejectedExecutionException e) { throw new IOException("DNS busy", e); }
        try {
            InetAddress[] addresses = task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            check(request); validateAddresses(addresses); return addresses;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", e); }
        catch (ExecutionException | TimeoutException e) { throw new IOException("DNS failed", e); }
        finally { task.cancel(true); }
    }
    byte[] fetch(String address) throws IOException {
        long request = begin(); URI u = validateUrl(address);
        for (int redirects=0; redirects<=MAX_REDIRECTS; redirects++) {
            check(request); InetAddress[] addresses = resolve(u.getHost(), request);
            Response response;
            try { response = request(u, addresses[0], request); }
            catch (SocketException | SocketTimeoutException failure) {
                check(request);
                InetAddress alternate = alternateAddress(addresses);
                if (alternate == null) throw failure;
                response = request(u, alternate, request);
            }
            if (response.location == null) return response.body;
            if (redirects == MAX_REDIRECTS) throw new IOException("Too many redirects");
            u = redirect(u, response.location);
        }
        throw new IOException("Too many redirects");
    }
    static InetAddress alternateAddress(InetAddress[] addresses) {
        if (addresses == null || addresses.length < 2) return null;
        for (int i = 1; i < addresses.length; i++)
            if (addresses[i].getAddress().length != addresses[0].getAddress().length) return addresses[i];
        return addresses[1];
    }
    private void track(Socket raw, long request) throws IOException {
        synchronized (lock) { if (!enabled || request != generation) { raw.close(); throw new IOException("Disabled"); } sockets.add(raw); }
    }
    private Response request(URI uri, InetAddress address, long request) throws IOException {
        Socket raw = new Socket();
        track(raw, request);
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> { try { raw.close(); } catch (IOException ignored) { } }, 25, TimeUnit.SECONDS);
        try {
            raw.connect(new InetSocketAddress(address, 443), TIMEOUT_MS); raw.setSoTimeout(TIMEOUT_MS); check(request);
            try (SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, uri.getHost(), 443, true)) {
                SSLParameters params = ssl.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); ssl.setSSLParameters(params);
                ssl.setSoTimeout(TIMEOUT_MS); ssl.startHandshake(); check(request);
                String path = uri.getRawPath(); if (path == null || path.isEmpty()) path="/";
                if (uri.getRawQuery()!=null) path += "?"+uri.getRawQuery();
                String line="GET "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nAccept: image/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                ssl.getOutputStream().write(line.getBytes(StandardCharsets.US_ASCII)); ssl.getOutputStream().flush();
                Response result = readResponse(ssl.getInputStream()); check(request); return result;
            }
        } finally { deadline.cancel(false); synchronized(lock) { sockets.remove(raw); } try { raw.close(); } catch (IOException ignored) { } }
    }
    static final class Response { final String location; final byte[] body; Response(String location, byte[] body){this.location=location;this.body=body;} }
    private static String line(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int previous=-1;
        while (true) { int c=in.read(); if(c<0 || --budget[0]<0)throw new IOException("Bad response");
            if(previous=='\r' && c=='\n'){byte[] b=out.toByteArray();return new String(b,0,b.length-1,StandardCharsets.ISO_8859_1);}
            if(c=='\n' || previous=='\r' || (c<32 && c!='\r' && c!='\t') || c==127)throw new IOException("Bad response");out.write(c);previous=c; }
    }
    static Response readResponse(InputStream in) throws IOException {
        int[] budget={16384}; String status=line(in,budget); String[] parts=status.split(" ",3);
        if(parts.length<2 || !parts[0].matches("HTTP/1\\.[01]") || !parts[1].matches("[0-9]{3}"))throw new IOException("Bad status");
        int code=Integer.parseInt(parts[1]); Map<String,String> headers=new HashMap<>(); String h;
        while(!(h=line(in,budget)).isEmpty()){int n=h.indexOf(':');if(n<=0)throw new IOException("Bad header");String key=h.substring(0,n).toLowerCase(Locale.US);
            if(!key.matches("[a-z0-9-]+"))throw new IOException("Bad header");
            String value=h.substring(n+1).trim();
            if (headers.containsKey(key) && (key.equals("content-length") || key.equals("transfer-encoding")
                    || key.equals("location") || key.equals("content-encoding"))) throw new IOException("Duplicate header");
            headers.put(key,value);}
        if(code==301 || code==302 || code==303 || code==307 || code==308){String location=headers.get("location");if(location==null || location.length()>4096)throw new IOException("Invalid redirect");return new Response(location,null);}
        if(code!=200)throw new IOException("Unsuccessful response");
        String enc=headers.get("content-encoding");if(enc!=null && !"identity".equalsIgnoreCase(enc))throw new IOException("Unsupported encoding");
        String transfer=headers.get("transfer-encoding"), length=headers.get("content-length");
        if(transfer!=null && length!=null)throw new IOException("Ambiguous framing");
        if(transfer!=null){if(!"chunked".equalsIgnoreCase(transfer))throw new IOException("Unsupported framing");return new Response(null,readChunked(in));}
        if(length!=null){long count;try{if(!length.matches("[0-9]{1,10}"))throw new NumberFormatException();count=Long.parseLong(length);}catch(NumberFormatException e){throw new IOException("Bad length");}
            if(count<=0 || count>MAX_BYTES)throw new IOException("Too large");return new Response(null,readFixed(in,(int)count));}
        return new Response(null,readToEnd(in));
    }
    private static byte[] readFixed(InputStream in,int count)throws IOException {byte[] b=new byte[count];int n=0;while(n<count){int r=in.read(b,n,count-n);if(r<0)throw new EOFException();if(r==0)continue;n+=r;}return b;}
    static byte[] readToEnd(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(n==0)continue;if(out.size()>MAX_BYTES-n)throw new IOException("Too large");out.write(b,0,n);}if(out.size()==0)throw new IOException("Empty");return out.toByteArray();}
    private static byte[] readChunked(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();int[] budget={16384};while(true){String s=line(in,budget);int semi=s.indexOf(';');if(semi>=0)s=s.substring(0,semi);if(!s.matches("[0-9a-fA-F]{1,8}"))throw new IOException("Bad chunk");long n=Long.parseLong(s,16);if(n>MAX_BYTES-out.size())throw new IOException("Too large");if(n==0){while(!line(in,budget).isEmpty()){}break;}out.write(readFixed(in,(int)n));if(in.read()!='\r' || in.read()!='\n')throw new IOException("Bad chunk");}if(out.size()==0)throw new IOException("Empty");return out.toByteArray();}
    @Override public void close() { setEnabled(false); }
}
