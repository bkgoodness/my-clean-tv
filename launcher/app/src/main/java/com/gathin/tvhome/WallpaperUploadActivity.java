package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.Inet4Address;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The listener exists only while this screen is visible. */
public final class WallpaperUploadActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean stopped, networkDown; private volatile LocalUploadServer server;
    private ConnectivityManager connectivity; private ConnectivityManager.NetworkCallback callback;
    private TextView details, status; private Network network; private Inet4Address address; private int prefix;
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(LauncherLocale.wrap(base));}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(48),dp(32),dp(48),dp(24));root.setBackgroundColor(0xff101419);
        TextView title=text(26);title.setText(R.string.wallpaper_upload_title);root.addView(title);
        details=text(19);details.setText(R.string.wallpaper_upload_starting);root.addView(details);
        status=text(17);root.addView(status);
        TextView stop=text(18);stop.setText(R.string.wallpaper_upload_close);stop.setFocusable(true);stop.setClickable(true);stop.setPadding(dp(16),dp(12),dp(16),dp(12));stop.setBackgroundColor(0xff36434d);stop.setOnFocusChangeListener((v,focused)->v.setBackgroundColor(focused?0xff56718a:0xff36434d));stop.setOnClickListener(v->finish());root.addView(stop);setContentView(root);stop.requestFocus();
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        for(Network n:connectivity.getAllNetworks()){
            NetworkCapabilities caps=connectivity.getNetworkCapabilities(n);LinkProperties props=connectivity.getLinkProperties(n);
            if(caps==null || props==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
            for(LinkAddress link:props.getLinkAddresses()) if(link.getAddress() instanceof Inet4Address && link.getAddress().isSiteLocalAddress() && link.getPrefixLength()>=8 && link.getPrefixLength()<=30){network=n;address=(Inet4Address)link.getAddress();prefix=link.getPrefixLength();break;}
            if(address!=null)break;
        }
        if(address==null){details.setText(R.string.wallpaper_upload_no_network);return;}
        callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onLost(Network n){if(n.equals(network))networkLost();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){if(n.equals(network) && (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || !(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))))networkLost();}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){if(!n.equals(network))return;boolean found=false;for(LinkAddress a:p.getLinkAddresses())if(a.getAddress().equals(address) && a.getPrefixLength()==prefix)found=true;if(!found)networkLost();}
        };
        try{connectivity.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback);}catch(RuntimeException e){callback=null;details.setText(R.string.wallpaper_upload_no_network);return;}
        worker.execute(()->{try{
            LocalUploadServer created=new LocalUploadServer(address,prefix,10*60*1000L,(input,guard)->WallpaperImport.save(getApplicationContext(),input,guard),new LocalUploadServer.Listener(){
                public void progress(long done,long total){showStatus(getString(R.string.wallpaper_upload_progress, (int)(100*done/total)));}
                public void result(String result){int id=result.equals("paired")?R.string.wallpaper_upload_paired:result.equals("added")?R.string.wallpaper_upload_added:result.equals("duplicate")?R.string.wallpaper_upload_duplicate:R.string.wallpaper_upload_failed;showStatus(getString(id));}
                public void stopped(){runOnUiThread(()->{if(!stopped){details.setText(R.string.wallpaper_upload_stopped);status.setText("");}});}
            });
            synchronized(this){if(stopped || networkDown){created.close();return;}server=created;}
            runOnUiThread(()->{if(!stopped)details.setText(getString(R.string.wallpaper_upload_details,created.url(),created.code()));});
        }catch(Exception e){runOnUiThread(()->{if(!stopped)details.setText(R.string.wallpaper_upload_no_network);});}});
    }
    private TextView text(int size){TextView t=new TextView(this);t.setTextColor(0xffffffff);t.setTextSize(size);t.setPadding(0,dp(10),0,dp(10));return t;}
    private void showStatus(String text){runOnUiThread(()->{if(!stopped)status.setText(text);});}
    private void networkLost(){synchronized(this){networkDown=true;if(server!=null)server.close();}runOnUiThread(()->{if(!stopped)details.setText(R.string.wallpaper_upload_no_network);});}
    @Override protected void onPause(){details.setText(R.string.wallpaper_upload_stopped);status.setText("");synchronized(this){stopped=true;if(server!=null)server.close();}if(callback!=null){try{connectivity.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}callback=null;}worker.shutdownNow();super.onPause();}
}
