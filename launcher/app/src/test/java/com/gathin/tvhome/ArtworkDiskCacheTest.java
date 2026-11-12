package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ArtworkDiskCacheTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Test public void cacheExpiresFromCreationAndInvalidationRemovesEntry() throws Exception {
        ArtworkDiskCache cache=new ArtworkDiskCache(tmp.newFolder("cache")); byte[] data={1,2,3}; long now=1000000000L;
        cache.put("https://images.example.com/a?private=1",data,now);
        assertArrayEquals(data,cache.get("https://images.example.com/a?private=1",now+100));
        assertNull(cache.get("https://images.example.com/a?private=1",now+ArtworkDiskCache.MAX_AGE_MS+1));
        cache.put("https://images.example.com/a",data,now);cache.remove("https://images.example.com/a");
        assertNull(cache.get("https://images.example.com/a",now));
    }
    @Test public void cacheUsesHashedNamesAndEnforcesByteBudget() throws Exception {
        File dir=tmp.newFolder("bounded");ArtworkDiskCache cache=new ArtworkDiskCache(dir);byte[] data=new byte[4*1024*1024];Arrays.fill(data,(byte)1);
        for(int i=0;i<5;i++)cache.put("https://example.com/secret-"+i,data,1000000L+i);
        long size=0; for(File f:dir.listFiles()){assertTrue(f.getName().matches("[0-9a-f]{64}"));size+=f.length();}
        assertTrue(size<=ArtworkDiskCache.MAX_BYTES);
        try {cache.put("https://example.com/oversize",new byte[ArtworkNetwork.MAX_BYTES+1],1000000L);fail();}catch(java.io.IOException expected){}
    }
}
