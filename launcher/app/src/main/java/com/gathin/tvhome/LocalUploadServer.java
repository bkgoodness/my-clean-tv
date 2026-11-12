package com.gathin.tvhome;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** One foreground-only, single-client, bounded HTTP session. No external network calls. */
final class LocalUploadServer implements AutoCloseable {
    interface Importer { WallpaperLibrary.ImportResult save(InputStream in, WallpaperLibrary.CommitGuard guard) throws IOException; }
    interface Listener { void progress(long bytes, long total); void result(String result); void stopped(); }
    private static final String PAGE = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>My Clean TV wallpapers</title>"
            + "<style>body{font:18px system-ui;max-width:38em;margin:2em auto;padding:0 1em}button,input{font:inherit;margin:.5em 0}progress{width:100%}</style>"
            + "<h1>My Clean TV wallpapers</h1><p>Use only on a trusted home network. Transfers are not encrypted.</p>"
            + "<label>TV pairing code <input id=c inputmode=numeric maxlength=6></label><button id=p>Pair</button>"
            + "<div id=up hidden><input id=f type=file accept='image/jpeg,image/png,image/webp' multiple><button id=send>Upload selected images</button><progress id=bar max=100 value=0></progress><ul id=results></ul></div><p id=s></p>"
            + "<script>let token='';const s=document.getElementById('s'),p=document.getElementById('p'),c=document.getElementById('c'),up=document.getElementById('up'),f=document.getElementById('f'),send=document.getElementById('send'),bar=document.getElementById('bar'),results=document.getElementById('results');p.onclick=async()=>{try{let r=await fetch('/pair',{method:'POST',headers:{'X-Pair-Code':c.value}});if(!r.ok)throw 0;token=await r.text();up.hidden=false;s.textContent='Paired.'}catch(e){s.textContent='Pairing failed. Check the code or start a new session.'}};"
            + "send.onclick=async()=>{send.disabled=true;for(const file of f.files){s.textContent='Uploading '+file.name;let result=await new Promise(resolve=>{let x=new XMLHttpRequest();x.open('POST','/upload');x.setRequestHeader('Content-Type','application/octet-stream');x.setRequestHeader('X-Session-Token',token);x.upload.onprogress=e=>{if(e.lengthComputable)bar.value=e.loaded/e.total*100};x.onload=()=>resolve(x.responseText);x.onerror=()=>resolve('Transfer failed');x.send(file)});let item=document.createElement('li');item.textContent=file.name+': '+result;results.appendChild(item);s.textContent='Upload finished.'}send.disabled=false};</script>";
    private final Object lock = new Object();
    private final ServerSocket server = new ServerSocket();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Importer importer; private final Listener listener; private final String host, origin;
    private final byte[] local; private final int prefix; private final long deadline;
    private String code, token; private InetAddress paired; private int failures; private boolean closed; private Socket active;

    LocalUploadServer(Inet4Address address, int prefix, long lifetimeMs, Importer importer, Listener listener) throws IOException {
        if (prefix < 8 || prefix > 30 || !(address.isSiteLocalAddress() || address.isLoopbackAddress())) throw new IOException("No private network");
        this.prefix=prefix; this.local=address.getAddress(); this.importer=importer; this.listener=listener;
        SecureRandom random = new SecureRandom(); code=String.format(Locale.US, "%06d", random.nextInt(1000000));
        byte[] secret=new byte[32]; random.nextBytes(secret); StringBuilder hex=new StringBuilder();
        for(byte b:secret) hex.append(String.format(Locale.US,"%02x", b & 255)); token=hex.toString();
        server.bind(new InetSocketAddress(address,0)); host=address.getHostAddress()+":"+server.getLocalPort(); origin="http://"+host;
        deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(lifetimeMs);
        timer.schedule(this::close,lifetimeMs,TimeUnit.MILLISECONDS);
        Thread thread=new Thread(this::accept,"wallpaper-upload"); thread.setDaemon(true); thread.start();
    }
    String url(){return origin+"/";} String code(){return code;}
    private boolean sameSubnet(InetAddress addr){byte[] b=addr.getAddress(); if(b.length!=4)return false; for(int i=0;i<4;i++){int n=Math.min(8,Math.max(0,prefix-i*8)); int mask=(0xff << (8-n)) & 0xff; if((b[i]&mask)!=(local[i]&mask))return false;} return true;}
    private boolean live(){return !closed && System.nanoTime()<deadline;}
    private void accept(){
        try { while(true){ Socket socket=server.accept(); synchronized(lock){if(!live()){socket.close(); break;} active=socket;}
            try(Socket s=socket){s.setSoTimeout(5000); if(sameSubnet(s.getInetAddress())) handle(s);} catch(IOException | RuntimeException ignored) { }
            finally {synchronized(lock){active=null;}}
        }} catch(IOException ignored) { } finally {close();}
    }
    private static String line(InputStream in, int[] budget) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream(); int previous=-1; while(true){int c=in.read(); if(c<0 || --budget[0]<0)throw new IOException("Bad headers"); if(previous=='\r' && c=='\n'){byte[] b=out.toByteArray();return new String(b,0,b.length-1,StandardCharsets.US_ASCII);} if(c<32 && c!='\r' || c>126 || previous=='\r')throw new IOException("Bad headers"); out.write(c);previous=c;} }
    private void handle(Socket socket) throws IOException {
        InputStream in=socket.getInputStream(); OutputStream out=socket.getOutputStream(); int[] budget={8192};
        String[] request=line(in,budget).split(" ",-1); if(request.length!=3 || !request[2].equals("HTTP/1.1")){reply(out,400,"Bad request");return;}
        Map<String,String> h=new HashMap<>(); boolean duplicate=false; String l; while(!(l=line(in,budget)).isEmpty()){int p=l.indexOf(':'); if(p<=0){reply(out,400,"Bad request");return;} String key=l.substring(0,p).toLowerCase(Locale.US); if(!key.matches("[a-z0-9-]+")){reply(out,400,"Bad request");return;} if(h.put(key,l.substring(p+1).trim())!=null)duplicate=true;}
        if(duplicate){reply(out,400,"Bad request");return;}
        if(!host.equals(h.get("host")) || h.containsKey("transfer-encoding") || h.containsKey("expect")){reply(out,403,"Forbidden");return;}
        if(request[0].equals("GET") && request[1].equals("/") && !h.containsKey("content-length")){reply(out,200,PAGE,true);return;}
        if(!request[0].equals("POST") || !origin.equals(h.get("origin"))){reply(out,403,"Forbidden");return;}
        String size=h.get("content-length"); long length; try{if(size==null || !size.matches("[0-9]{1,10}"))throw new NumberFormatException(); length=Long.parseLong(size);}catch(NumberFormatException e){reply(out,400,"Bad request");return;}
        if(request[1].equals("/pair")){
            if(length!=0){reply(out,400,"Bad request");return;}
            boolean ok; synchronized(lock){ok=live() && (paired==null || paired.equals(socket.getInetAddress())) && constant(code,h.get("x-pair-code")); if(ok)paired=socket.getInetAddress(); else failures++;}
            if(ok){listener.result("paired");reply(out,200,token);}else{reply(out,403,"Pairing failed");if(failures>=5)close();} return;
        }
        if(!request[1].equals("/upload")){reply(out,404,"Not found");return;}
        synchronized(lock){if(!live() || paired==null || !paired.equals(socket.getInetAddress()) || !constant(token,h.get("x-session-token"))){reply(out,403,"Forbidden");return;}}
        if(length<=0 || length>WallpaperLibrary.MAX_FILE){reply(out,413,"Image must be 10 MiB or smaller");return;}
        if(!"application/octet-stream".equals(h.get("content-type"))){reply(out,400,"Invalid upload");return;}
        try {
            WallpaperLibrary.ImportResult result=importer.save(new LimitedInput(in,length), action->{synchronized(lock){if(!live())throw new IOException("Session ended");action.run();}});
            synchronized(lock){if(!live())return;}
            String message=result.duplicate?"Already in library":"Added to library"; listener.result(result.duplicate?"duplicate":"added"); reply(out,200,message);
        } catch(IOException | RuntimeException e){listener.result("failed");reply(out,400,"Upload failed: invalid image, full library, or interrupted transfer");}
    }
    private static boolean constant(String a,String b){return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private final class LimitedInput extends InputStream {private final InputStream in; private final long total; private long left,last; LimitedInput(InputStream in,long total){this.in=in;this.total=total;left=total;}
        @Override public int read() throws IOException {byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(left==0)return -1; synchronized(lock){if(!live())throw new IOException("Session ended");} int n=in.read(b,off,(int)Math.min(left,len));if(n<0)throw new EOFException();left-=n;long now=System.nanoTime();if(left==0 || now-last>250000000L){last=now;listener.progress(total-left,total);}return n;}}
    private static void reply(OutputStream out,int status,String body)throws IOException{reply(out,status,body,false);}
    private static void reply(OutputStream out,int status,String body,boolean html)throws IOException {byte[] data=body.getBytes(StandardCharsets.UTF_8);String headers="HTTP/1.1 "+status+" OK\r\nContent-Type: "+(html?"text/html":"text/plain")+"; charset=utf-8\r\nContent-Length: "+data.length+"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n";out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();}
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;code=null;token=null;paired=null;try{server.close();}catch(IOException ignored){} if(active!=null)try{active.close();}catch(IOException ignored){} }timer.shutdownNow();listener.stopped();}
}
