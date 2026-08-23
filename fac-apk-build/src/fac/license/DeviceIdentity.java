package fac.license;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Reinstall-stable device identity for emulator/Android instances.
 *
 * Primary anchors are hardware/system identifiers that are not stored in the
 * app sandbox and therefore survive an APK uninstall/reinstall and a signing
 * certificate change. ANDROID_ID is only used as a last-resort fallback when
 * the platform exposes no serial/MAC anchors.
 */
public final class DeviceIdentity {
    private DeviceIdentity() {}

    public static String get(Context ctx) throws Exception {
        ArrayList<String> anchors=new ArrayList<String>();

        addIfUseful(anchors,"serial",sysprop("ro.serialno"));
        addIfUseful(anchors,"bootserial",sysprop("ro.boot.serialno"));

        try {
            Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
            if(all!=null){
                while(all.hasMoreElements()){
                    NetworkInterface ni=all.nextElement();
                    if(ni==null || ni.isLoopback()) continue;
                    byte[] mac=ni.getHardwareAddress();
                    if(!validMac(mac)) continue;
                    anchors.add("mac:"+safe(ni.getName())+"="+hex(mac));
                }
            }
        } catch(Exception ignored) {}

        Collections.sort(anchors);
        StringBuilder src=new StringBuilder("FAC-DEVICE-V6|");
        if(!anchors.isEmpty()) {
            for(String a:anchors) src.append(a).append('|');
        } else {
            // Last-resort fallback. Build properties make accidental collisions
            // less likely; ANDROID_ID adds per-instance entropy where available.
            src.append("board=").append(safe(Build.BOARD)).append('|');
            src.append("brand=").append(safe(Build.BRAND)).append('|');
            src.append("device=").append(safe(Build.DEVICE)).append('|');
            src.append("hardware=").append(safe(Build.HARDWARE)).append('|');
            src.append("manufacturer=").append(safe(Build.MANUFACTURER)).append('|');
            src.append("model=").append(safe(Build.MODEL)).append('|');
            src.append("product=").append(safe(Build.PRODUCT)).append('|');
            String aid=Settings.Secure.getString(ctx.getContentResolver(),Settings.Secure.ANDROID_ID);
            src.append("android_id=").append(safe(aid)).append('|');
        }

        MessageDigest md=MessageDigest.getInstance("SHA-256");
        return hex(md.digest(src.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String sysprop(String key){
        try {
            Class<?> c=Class.forName("android.os.SystemProperties");
            Object v=c.getMethod("get",String.class).invoke(null,key);
            return v==null?"":String.valueOf(v);
        } catch(Exception e){ return ""; }
    }

    private static void addIfUseful(List<String> out,String label,String value){
        value=safe(value).trim();
        if(value.length()==0 || "unknown".equalsIgnoreCase(value) || "0123456789ABCDEF".equalsIgnoreCase(value)) return;
        out.add(label+":"+value);
    }

    private static boolean validMac(byte[] m){
        if(m==null || m.length<6) return false;
        boolean any=false;
        for(byte b:m) if((b&0xff)!=0) any=true;
        if(!any) return false;
        String h=hex(m);
        return !"020000000000".equalsIgnoreCase(h) && !"000000000000".equalsIgnoreCase(h);
    }

    private static String safe(String s){ return s==null?"":s; }
    private static String hex(byte[] data){
        StringBuilder sb=new StringBuilder(data.length*2);
        for(byte b:data) sb.append(String.format(Locale.US,"%02x",b&255));
        return sb.toString();
    }
}
