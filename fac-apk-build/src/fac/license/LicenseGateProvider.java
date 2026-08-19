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
 * V8 fail-closed runtime guard.
 *
 * LicenseActivity is the visible MAIN/LAUNCHER in :facgate. The original
 * application class remains com.nx.main.App. This provider lives only in the
 * protected main process and never opens UI.
 *
 * After successful verification, :facgate writes a one-shot cold-start grant
 * and explicitly starts the original SplashActivity. The new main process
 * installs this provider before the original CoreProvider. The grant is
 * consumed here, then Android continues into the untouched original
 * Application.onCreate()/providers/root/permission chain.
 */
public class LicenseGateProvider extends ContentProvider {
    public static final String COLD_GRANT="fac_cold_start_grant_v8";

    @Override public boolean onCreate(){
        final Context ctx=getContext();
        if(ctx==null) return true;
        SharedPreferences p=ctx.getSharedPreferences(LicenseActivity.PREF,0);
        boolean allowed=p.getBoolean(COLD_GRANT,false) && p.getBoolean("verified",false);
        if(allowed){
            // Consume before the original runtime continues. A later process
            // restart must go through the online license gate again.
            p.edit().remove(COLD_GRANT).commit();
            return true;
        }

        // Direct/unauthorized protected-main starts fail closed. The license
        // activity itself is in :facgate, so normal app-icon launches never
        // reach this provider until VERIFY succeeds.
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
            // LicenseActivity is running in :facgate. Terminate only that gate
            // process after ActivityManager has accepted the protected launch.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable(){
                @Override public void run(){
                    try{ Process.killProcess(Process.myPid()); }finally{ System.exit(0); }
                }
            },700L);
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
