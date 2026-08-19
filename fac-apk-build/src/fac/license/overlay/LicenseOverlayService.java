package fac.license.overlay;

import android.app.Service;
import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;
import org.json.*;
import fac.license.ui.LicenseActivity;
import fac.license.ui.BotSettingsActivity;

public class LicenseOverlayService extends Service {
    private static final long LOCAL_CHECK_MS=30000L;
    private static final long SERVER_RECHECK_MS=5L*60L*1000L;
    private static final String KEY_ALIAS="fac_license_key_v1";
    private WindowManager wm;
    private View badge;
    private View menu;
    private WindowManager.LayoutParams badgeParams;
    private Handler handler;
    private volatile boolean networkBusy;
    private long lastServerCheckElapsed;
    private boolean locking;

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(){ super.onCreate(); handler=new Handler(Looper.getMainLooper()); showBadge(); lastServerCheckElapsed=SystemClock.elapsedRealtime(); handler.postDelayed(guardTick,LOCAL_CHECK_MS); }
    @Override public int onStartCommand(Intent i,int flags,int id){ if(badge==null)showBadge(); return START_STICKY; }
    private int overlayType(){return Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;}
    private TextView item(String t){TextView v=new TextView(this);v.setText(t);v.setTextColor(Color.WHITE);v.setTextSize(14);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(dp(16),0,dp(16),0);return v;}

    private void showBadge(){
        try{
            if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this))return;
            wm=(WindowManager)getSystemService(WINDOW_SERVICE); TextView t=item("FAC • LICENSE ACTIVE"); t.setBackgroundColor(Color.rgb(198,40,40)); t.setPadding(dp(12),dp(8),dp(12),dp(8)); badge=t;
            badgeParams=new WindowManager.LayoutParams(-2,-2,overlayType(),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT); badgeParams.gravity=Gravity.BOTTOM|Gravity.START;badgeParams.x=dp(10);badgeParams.y=dp(32); wm.addView(badge,badgeParams); t.setOnClickListener(v->toggleMenu());
        }catch(Exception ignored){}
    }
    private void updateBadge(final String text){ handler.post(()->{ try{if(badge instanceof TextView)((TextView)badge).setText(text);}catch(Exception ignored){} }); }
    private void toggleMenu(){
        if(menu!=null){try{wm.removeView(menu);}catch(Exception ignored){}menu=null;return;}
        try{
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(Color.rgb(45,45,50));TextView lic=item("License");TextView bot=item("Bot Settings");box.addView(lic,new LinearLayout.LayoutParams(dp(190),dp(52)));box.addView(bot,new LinearLayout.LayoutParams(dp(190),dp(52)));
            lic.setOnClickListener(v->{Intent i=new Intent(this,LicenseActivity.class);i.putExtra("status_only",true);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);toggleMenu();});
            bot.setOnClickListener(v->{Intent i=new Intent(this,BotSettingsActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);toggleMenu();});
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,overlayType(),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);p.gravity=Gravity.BOTTOM|Gravity.START;p.x=dp(10);p.y=dp(82);menu=box;wm.addView(menu,p);
        }catch(Exception ignored){}
    }

    private final Runnable guardTick=new Runnable(){ @Override public void run(){
        if(locking)return;
        SharedPreferences p=getSharedPreferences(LicenseActivity.PREF,0);
        if(!p.getBoolean("verified",false)){ enforceLock("FAC • LICENSE INVALID"); return; }
        long expiry=p.getLong("expiry_epoch_ms",-1), server=p.getLong("server_epoch_ms",-1), anchor=p.getLong("verify_elapsed_ms",-1), nowElapsed=SystemClock.elapsedRealtime();
        if(expiry<=0||server<=0||anchor<0||nowElapsed<anchor){ requestServerRecheck(); }
        else {
            long estimatedServerNow=server+(nowElapsed-anchor);
            if(estimatedServerNow>=expiry){ enforceLock("FAC • LICENSE EXPIRED"); return; }
            if(nowElapsed-lastServerCheckElapsed>=SERVER_RECHECK_MS) requestServerRecheck();
        }
        if(!locking)handler.postDelayed(this,LOCAL_CHECK_MS);
    }};

    private void requestServerRecheck(){
        if(networkBusy||locking)return; networkBusy=true; lastServerCheckElapsed=SystemClock.elapsedRealtime();
        new Thread(()->{
            try{
                String key=loadKey(); if(key.length()<8)throw new IOException("missing key");
                JSONObject req=new JSONObject(); req.put("licenseKey",key); req.put("deviceId",deviceId()); req.put("appVersion",LicenseActivity.APP_VERSION);
                HttpResult r=post(LicenseActivity.VERIFY_URL,req);
                if(r.code!=200)throw new SecurityException("verify status "+r.code);
                JSONObject o=new JSONObject(r.body); if(!o.optBoolean("verified",false))throw new SecurityException("not verified");
                String exp=o.optString("licenseExpiresAtUtc",""); String srv=o.optString("serverTimeUtc",""); long expEpoch=LicenseActivity.parseUtc(exp), srvEpoch=LicenseActivity.parseUtc(srv);
                int bound=o.optInt("devicesBound",-1), limit=o.optInt("devicesLimit",-1);
                if(expEpoch<=0||srvEpoch<=0||expEpoch<=srvEpoch||bound<0||limit<=0||bound>limit)throw new IOException("invalid response");
                long anchor=SystemClock.elapsedRealtime(); getSharedPreferences(LicenseActivity.PREF,0).edit().putBoolean("verified",true).putString("expiry",exp).putLong("expiry_epoch_ms",expEpoch).putLong("server_epoch_ms",srvEpoch).putLong("verify_elapsed_ms",anchor).putInt("bound",bound).putInt("limit",limit).apply();
                updateBadge("FAC • LICENSE ACTIVE");
            }catch(Exception e){ enforceLock("FAC • LICENSE CHECK FAILED"); }
            finally{networkBusy=false;}
        }).start();
    }

    private void enforceLock(final String label){
        if(locking)return; locking=true; getSharedPreferences(LicenseActivity.PREF,0).edit().putBoolean("verified",false).apply(); updateBadge(label);
        handler.postDelayed(()->{ try{android.os.Process.killProcess(android.os.Process.myPid());}catch(Exception ignored){System.exit(0);} },1200L);
    }

    private static class HttpResult{int code;String body;HttpResult(int c,String b){code=c;body=b;}}
    private HttpResult post(String u,JSONObject j)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Accept","application/json");byte[] d=j.toString().getBytes(StandardCharsets.UTF_8);if(d.length>4096)throw new IOException();c.setFixedLengthStreamingMode(d.length);OutputStream os=c.getOutputStream();os.write(d);os.close();int code=c.getResponseCode();InputStream in=code>=200&&code<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(in!=null){byte[] buf=new byte[1024];int n,total=0;while((n=in.read(buf))>0&&total<4096){int take=Math.min(n,4096-total);out.write(buf,0,take);total+=take;if(total>=4096)break;}in.close();}c.disconnect();return new HttpResult(code,new String(out.toByteArray(),StandardCharsets.UTF_8));
    }
    private String deviceId()throws Exception{
        SharedPreferences p=getSharedPreferences(LicenseActivity.PREF,0);String uuid=p.getString("install_uuid",null);if(uuid==null){uuid=UUID.randomUUID().toString();p.edit().putString("install_uuid",uuid).apply();}String aid=Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID);if(aid==null)aid="unknown";MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] h=md.digest((aid+":"+uuid).getBytes(StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder(64);for(byte x:h)sb.append(String.format(Locale.US,"%02x",x&255));return sb.toString();
    }
    private String loadKey(){
        if(Build.VERSION.SDK_INT<23)return "";
        try{String blob=getSharedPreferences(LicenseActivity.PREF,0).getString("key_blob","");if(blob.length()==0)return "";String[] p=blob.split(":",2);if(p.length!=2)return "";KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);javax.crypto.SecretKey sk=(javax.crypto.SecretKey)ks.getKey(KEY_ALIAS,null);if(sk==null)return "";Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.DECRYPT_MODE,sk,new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));return new String(ci.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){return "";}
    }

    @Override public void onDestroy(){ if(handler!=null)handler.removeCallbacksAndMessages(null); if(wm!=null){try{if(menu!=null)wm.removeView(menu);}catch(Exception ignored){}try{if(badge!=null)wm.removeView(badge);}catch(Exception ignored){}}menu=null;badge=null;super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent i){return null;}
}
