package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;

public class LocalUploadServerTest {
    private static final LocalUploadServer.Listener LISTENER=new LocalUploadServer.Listener(){public void progress(long a,long b){} public void result(String s){} public void stopped(){}};
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    private static String call(LocalUploadServer server,String method,String path,String headers,byte[] body)throws Exception{
        URL u=new URL(server.url());try(Socket socket=new Socket(u.getHost(),u.getPort())){socket.setSoTimeout(3000);OutputStream out=socket.getOutputStream();out.write(bytes(method+" "+path+" HTTP/1.1\r\nHost: "+u.getAuthority()+"\r\n"+headers+"\r\n"));if(body!=null)out.write(body);out.flush();ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=socket.getInputStream().read(b))!=-1)result.write(b,0,n);return result.toString("UTF-8");}}
    private static String origin(LocalUploadServer s)throws Exception{URL u=new URL(s.url());return "Origin: http://"+u.getAuthority()+"\r\n";}
    private static String pair(LocalUploadServer s)throws Exception{String r=call(s,"POST","/pair",origin(s)+"Content-Length: 0\r\nX-Pair-Code: "+s.code()+"\r\n",null);assertTrue(r,r.contains("200 OK"));return r.substring(r.indexOf("\r\n\r\n")+4);}
    private static String upload(LocalUploadServer s,String token,byte[] data)throws Exception{return call(s,"POST","/upload",origin(s)+"Content-Length: "+data.length+"\r\nContent-Type: application/octet-stream\r\nX-Session-Token: "+token+"\r\n",data);}
    private static LocalUploadServer start(LocalUploadServer.Importer importer,long ttl)throws Exception{return new LocalUploadServer((Inet4Address)InetAddress.getByName("127.0.0.1"),8,ttl,importer,LISTENER);}
    @Test public void authorizationAndOriginAreRequired()throws Exception{try(LocalUploadServer s=start((in,g)->{fail();return null;},10000)){
        assertTrue(call(s,"POST","/pair","Origin: http://evil.test\r\nContent-Length: 0\r\nX-Pair-Code: "+s.code()+"\r\n",null).contains("403"));
        assertTrue(upload(s,"bad",new byte[0]).contains("403"));
        assertTrue(call(s,"POST","/pair",origin(s)+"Content-Length: 0\r\nContent-Length: 0\r\n",null).contains("400"));
        assertTrue(call(s,"POST","/pair",origin(s)+"Content-Length: 0\r\nTransfer-Encoding: chunked\r\n",null).contains("403"));
        String token=pair(s);assertEquals(64,token.length());assertTrue(upload(s,"wrong",new byte[0]).contains("403"));
    }}
    @Test public void rebindingHostIsRejected()throws Exception{try(LocalUploadServer s=start((in,g)->{fail();return null;},10000)){URL u=new URL(s.url());try(Socket socket=new Socket(u.getHost(),u.getPort())){socket.setSoTimeout(3000);socket.getOutputStream().write(bytes("GET / HTTP/1.1\r\nHost: attacker.example:"+u.getPort()+"\r\n\r\n"));BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));assertTrue(in.readLine().contains("403"));}}}
    @Test public void storageValidationAndDuplicates()throws Exception{File dir=Files.createTempDirectory("upload-test").toFile(),user=new File(dir,"user"),cache=new File(dir,"cache");
        try(LocalUploadServer s=start((in,g)->WallpaperLibrary.importImage(user,cache,in,(f,format)->true,g),10000)){
            String token=pair(s);byte[] image={(byte)0xff,(byte)0xd8,(byte)0xff,1,2,3};
            assertTrue(upload(s,token,image).contains("Added to library"));assertTrue(upload(s,token,image).contains("Already in library"));
            assertTrue(upload(s,token,bytes("not an image")).contains("Upload failed"));
            assertTrue(call(s,"POST","/upload",origin(s)+"Content-Length: 10485761\r\nContent-Type: application/octet-stream\r\nX-Session-Token: "+token+"\r\n",null).contains("413"));
            assertEquals(1,user.list().length);assertEquals(0,cache.list().length);
        }}
    @Test public void interruptionAndShutdownRemoveTemporaryFiles()throws Exception{File dir=Files.createTempDirectory("upload-interrupt").toFile(),user=new File(dir,"user"),cache=new File(dir,"cache");CountDownLatch begun=new CountDownLatch(1),ended=new CountDownLatch(1);
        LocalUploadServer s=start((in,g)->{begun.countDown();try{return WallpaperLibrary.importImage(user,cache,in,(f,format)->true,g);}finally{ended.countDown();}},10000);String token=pair(s);URL u=new URL(s.url());Socket socket=new Socket(u.getHost(),u.getPort());OutputStream out=socket.getOutputStream();out.write(bytes("POST /upload HTTP/1.1\r\nHost: "+u.getAuthority()+"\r\n"+origin(s)+"Content-Length: 1000\r\nContent-Type: application/octet-stream\r\nX-Session-Token: "+token+"\r\n\r\n"));out.write(new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff});out.flush();assertTrue(begun.await(2,TimeUnit.SECONDS));s.close();socket.close();assertTrue(ended.await(3,TimeUnit.SECONDS));assertEquals(0,cache.list().length);assertEquals(0,user.list().length);
    }
    @Test public void pairingIsRateLimitedAndSessionExpires()throws Exception{LocalUploadServer s=start((in,g)->null,10000);for(int i=0;i<5;i++)assertTrue(call(s,"POST","/pair",origin(s)+"Content-Length: 0\r\nX-Pair-Code: invalid\r\n",null).contains("403"));Thread.sleep(100);try{pair(s);fail();}catch(IOException expected){}finally{s.close();}
        LocalUploadServer expiry=start((in,g)->null,100);Thread.sleep(250);try{pair(expiry);fail();}catch(IOException expected){}finally{expiry.close();}}
}
