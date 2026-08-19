package fac.license.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;
import fac.license.DeviceIdentity;
import fac.license.ui.LicenseActivity;

/** Lightweight in-process V11 license guard; no Android Service or manifest entry. */
public final class LicenseRuntimeGuard {
    private static final long LOCAL_CHECK_MS = 30000L;
    private static final long SERVER_RECHECK_MS = 5L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static ScheduledExecutorService executor;
    private static volatile boolean armed;
    private static volatile String key;
    private static volatile long expiryEpochMs;
    private static volatile long serverEpochMs;
    private static volatile long verifyElapsedMs;
    private static volatile long lastServerCheckElapsed;
    private static Context app;

    private LicenseRuntimeGuard() {}

    public static void arm(Context context, String licenseKey, long expiry, long server, long anchor){
        synchronized(LOCK){
            app = context.getApplicationContext();
            key = licenseKey;
            expiryEpochMs = expiry;
            serverEpochMs = server;
            verifyElapsedMs = anchor;
            lastServerCheckElapsed = anchor;
            if(armed) return;
            armed = true;
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FAC-V11-Guard");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(LicenseRuntimeGuard::tick,
                    LOCAL_CHECK_MS, LOCAL_CHECK_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void tick(){
        if(!armed || app == null) return;
        try{
            SharedPreferences p = app.getSharedPreferences(LicenseActivity.PREF,0);
            if(!p.getBoolean("verified",false)) throw new SecurityException("not verified");
            long now = SystemClock.elapsedRealtime();
            if(verifyElapsedMs < 0 || now < verifyElapsedMs) throw new SecurityException("invalid clock anchor");
            long estimatedServerNow = serverEpochMs + (now - verifyElapsedMs);
            if(expiryEpochMs <= 0 || estimatedServerNow >= expiryEpochMs)
                throw new SecurityException("expired");
            if(now - lastServerCheckElapsed >= SERVER_RECHECK_MS) recheck(now);
        }catch(Throwable t){
            revokeAndKill();
        }
    }

    private static void recheck(long now) throws Exception {
        JSONObject req = new JSONObject();
        req.put("licenseKey", key);
        req.put("deviceId", DeviceIdentity.get(app));
        req.put("appVersion", LicenseActivity.APP_VERSION);
        HttpResult r = post(LicenseActivity.VERIFY_URL, req);
        if(r.code != 200) throw new SecurityException("verify status " + r.code);
        JSONObject o = new JSONObject(r.body);
        if(!o.optBoolean("verified",false)) throw new SecurityException("not verified");
        String exp = o.optString("licenseExpiresAtUtc","");
        String srv = o.optString("serverTimeUtc","");
        long expEpoch = LicenseActivity.parseUtc(exp);
        long srvEpoch = LicenseActivity.parseUtc(srv);
        int bound = o.optInt("devicesBound",-1), limit = o.optInt("devicesLimit",-1);
        if(expEpoch<=0 || srvEpoch<=0 || expEpoch<=srvEpoch || bound<0 || limit<=0 || bound>limit)
            throw new IOException("invalid response");
        expiryEpochMs = expEpoch;
        serverEpochMs = srvEpoch;
        verifyElapsedMs = now;
        lastServerCheckElapsed = now;
        app.getSharedPreferences(LicenseActivity.PREF,0).edit()
                .putBoolean("verified",true)
                .putString("expiry",exp)
                .putLong("expiry_epoch_ms",expEpoch)
                .putLong("server_epoch_ms",srvEpoch)
                .putLong("verify_elapsed_ms",now)
                .putInt("bound",bound).putInt("limit",limit).commit();
    }

    private static void revokeAndKill(){
        try{ app.getSharedPreferences(LicenseActivity.PREF,0).edit().putBoolean("verified",false).commit(); }
        catch(Exception ignored){}
        FacAppComponentFactoryX.revokeRuntime();
        try{ Process.killProcess(Process.myPid()); }
        finally{ System.exit(0); }
    }

    private static final class HttpResult {
        final int code; final String body;
        HttpResult(int c,String b){ code=c; body=b; }
    }

    private static HttpResult post(String u, JSONObject j) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("Accept","application/json");
        byte[] d=j.toString().getBytes(StandardCharsets.UTF_8);
        if(d.length>4096) throw new IOException("request too large");
        c.setFixedLengthStreamingMode(d.length);
        OutputStream os=c.getOutputStream(); os.write(d); os.close();
        int code=c.getResponseCode();
        InputStream in=code>=200&&code<400?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(in!=null){ byte[] buf=new byte[1024]; int n,total=0; while((n=in.read(buf))>0&&total<4096){ int take=Math.min(n,4096-total); out.write(buf,0,take); total+=take; if(total>=4096)break; } in.close(); }
        c.disconnect();
        return new HttpResult(code,new String(out.toByteArray(),StandardCharsets.UTF_8));
    }
}
