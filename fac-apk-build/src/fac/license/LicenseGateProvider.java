package fac.license;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import fac.license.ui.LicenseActivity;

/**
 * V7 fail-closed runtime guard.
 *
 * Important: this provider NEVER opens UI. The visible MAIN/LAUNCHER is
 * LicenseActivity in the isolated :facgate process. Therefore the original
 * main process and its providers do not exist until verification succeeds.
 *
 * When the original main process is started explicitly after VERIFY, this
 * provider is installed before CoreProvider and only permits the process when
 * a one-shot cold-start grant is present. This removes the Android background
 * activity-start race that could leave v6 stuck on the launch splash.
 */
public class LicenseGateProvider extends ContentProvider {
    public static final String COLD_GRANT="fac_cold_start_grant_v7";

    @Override public boolean onCreate(){
        final Context ctx=getContext();
        if(ctx==null) return true;
        SharedPreferences p=ctx.getSharedPreferences(LicenseActivity.PREF,0);
        boolean allowed=p.getBoolean(COLD_GRANT,false) && p.getBoolean("verified",false);
        if(allowed) return true;

        // Direct/unauthorized starts of the protected main process fail closed.
        p.edit().remove(COLD_GRANT).putBoolean("verified",false).commit();
        try{ Process.killProcess(Process.myPid()); }finally{ System.exit(0); }
        return true;
    }

    /** Start the untouched original runtime in a fresh protected main process. */
    public static void startVerifiedColdProcess(Context ctx){
        SharedPreferences p=ctx.getSharedPreferences(LicenseActivity.PREF,0);
        p.edit().putBoolean(COLD_GRANT,true).commit();
        try{
            Intent original=new Intent();
            original.setComponent(new ComponentName(ctx.getPackageName(),"com.core.activity.SplashActivity"));
            original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(original);
        }catch(Exception e){
            p.edit().remove(COLD_GRANT).putBoolean("verified",false).commit();
            throw new RuntimeException(e);
        }
    }

    /** Bring an already-running protected process back through its real Splash. */
    public static void resumeVerifiedRuntime(Context ctx){
        try{
            Intent original=new Intent();
            original.setComponent(new ComponentName(ctx.getPackageName(),"com.core.activity.SplashActivity"));
            original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(original);
        }catch(Exception e){ throw new RuntimeException(e); }
    }

    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String so){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
