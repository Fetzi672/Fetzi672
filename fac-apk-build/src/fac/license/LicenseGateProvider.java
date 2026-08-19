package fac.license;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import fac.license.overlay.LicenseOverlayService;
import fac.license.ui.LicenseActivity;

/**
 * V8 fail-closed runtime guard.
 *
 * LicenseActivity is the visible MAIN/LAUNCHER in :facgate. The manifest uses
 * the untouched original com.nx.main.App as its Application again.
 *
 * After successful verification, :facgate writes a one-shot cold-start grant
 * and explicitly starts the original SplashActivity. The new main process
 * installs this provider before the original CoreProvider. The grant is
 * consumed here and Android then continues through the original Application,
 * root/core initialization and runtime-permission flow.
 */
public class LicenseGateProvider extends ContentProvider {
    public static final String COLD_GRANT="fac_cold_start_grant_v8";
    private static volatile boolean overlayObserverInstalled;
    private static volatile boolean overlayStarted;

    @Override public boolean onCreate(){
        final Context ctx=getContext();
        if(ctx==null) return true;
        SharedPreferences p=ctx.getSharedPreferences(LicenseActivity.PREF,0);
        boolean allowed=p.getBoolean(COLD_GRANT,false) && p.getBoolean("verified",false);
        if(allowed){
            // Consume before the original runtime continues. A later process
            // restart must pass through the online license gate again.
            p.edit().remove(COLD_GRANT).commit();
            installOverlayObserver(ctx);
            return true;
        }

        // Direct/unauthorized protected-main starts fail closed. Normal app-icon
        // launches go to LicenseActivity in :facgate and never reach this branch.
        p.edit().remove(COLD_GRANT).putBoolean("verified",false).commit();
        try{ Process.killProcess(Process.myPid()); }finally{ System.exit(0); }
        return true;
    }

    private static void installOverlayObserver(final Context ctx){
        if(overlayObserverInstalled) return;
        overlayObserverInstalled=true;
        try{
            Application app=(Application)ctx.getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
                @Override public void onActivityResumed(Activity a){
                    if(overlayStarted) return;
                    if(!a.getSharedPreferences(LicenseActivity.PREF,0).getBoolean("verified",false)) return;
                    if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(a)) return;
                    if("com.core.activity.SplashActivity".equals(a.getClass().getName())) return;
                    try{
                        a.startService(new Intent(a,LicenseOverlayService.class));
                        overlayStarted=true;
                    }catch(Exception ignored){}
                }
                @Override public void onActivityCreated(Activity a,Bundle b){}
                @Override public void onActivityStarted(Activity a){}
                @Override public void onActivityPaused(Activity a){}
                @Override public void onActivityStopped(Activity a){}
                @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
                @Override public void onActivityDestroyed(Activity a){}
            });
        }catch(Exception ignored){}
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
            // LicenseActivity runs in :facgate. This terminates only the gate
            // process after ActivityManager accepted the protected launch.
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
