package fac.loader;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class LicenseApi {
    public static final String VERIFY_URL="https://fac.fetzi-ai.de/api/android/licenses/verify";
    public static final String APP_VERSION="310.0";
    private LicenseApi() {}

    public static final class Result {
        public final boolean verified;
        public final long expiryEpochMs;
        public final long serverEpochMs;
        public final String expiryUtc;
        public final int devicesBound;
        public final int devicesLimit;
        Result(boolean v,long e,long s,String u,int b,int l){verified=v;expiryEpochMs=e;serverEpochMs=s;expiryUtc=u;devicesBound=b;devicesLimit=l;}
    }

    private static final class HttpResult {
        final int code; final String body;
        HttpResult(int c,String b){code=c;body=b;}
    }

    public static Result verify(Context ctx,String key) throws Exception {
        if(key==null || key.trim().length()<8) throw new SecurityException("missing license key");
        JSONObject req=new JSONObject();
        req.put("licenseKey",key.trim());
        req.put("deviceId",DeviceIdentity.get(ctx));
        req.put("appVersion",APP_VERSION);
        HttpResult r=post(VERIFY_URL,req);
        if(r.code!=200) throw new SecurityException("verify status "+r.code);
        JSONObject o=new JSONObject(r.body);
        if(!o.optBoolean("verified",false)) throw new SecurityException("not verified");
        String exp=o.optString("licenseExpiresAtUtc","");
        String srv=o.optString("serverTimeUtc","");
        long expEpoch=parseUtc(exp), srvEpoch=parseUtc(srv);
        int bound=o.optInt("devicesBound",-1), limit=o.optInt("devicesLimit",-1);
        if(expEpoch<=0 || srvEpoch<=0 || expEpoch<=srvEpoch) throw new IOException("invalid server time/expiry");
        if(bound<0 || limit<=0 || bound>limit) throw new IOException("invalid device counters");
        return new Result(true,expEpoch,srvEpoch,exp,bound,limit);
    }

    public static long parseUtc(String s){
        if(s==null)return -1; s=s.trim(); if(s.length()==0)return -1;
        try{
            if(s.endsWith("Z") && s.indexOf('.')>0){ int dot=s.indexOf('.'); s=s.substring(0,dot)+"Z"; }
            SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);
            f.setLenient(false); f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d=f.parse(s); return d==null?-1:d.getTime();
        }catch(Exception e){return -1;}
    }

    private static HttpResult post(String u,JSONObject j)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("Accept","application/json");
        byte[] d=j.toString().getBytes(StandardCharsets.UTF_8);
        if(d.length>4096)throw new IOException("request too large");
        c.setFixedLengthStreamingMode(d.length);
        OutputStream os=c.getOutputStream(); os.write(d); os.close();
        int code=c.getResponseCode();
        InputStream in=code>=200&&code<400?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(in!=null){
            byte[] buf=new byte[1024]; int n,total=0;
            while((n=in.read(buf))>0&&total<4096){int take=Math.min(n,4096-total);out.write(buf,0,take);total+=take;if(total>=4096)break;}
            in.close();
        }
        c.disconnect();
        return new HttpResult(code,new String(out.toByteArray(),StandardCharsets.UTF_8));
    }
}
