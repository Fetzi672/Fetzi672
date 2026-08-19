package fac.license;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
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
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import fac.license.ui.LicenseActivity;
import fac.license.overlay.LicenseOverlayService;

/**
 * FAC license gate while preserving the original application cold-start flow.
 *
 * The first process is only the license-gate process. After successful online
 * verification it schedules the original SplashActivity through AlarmManager,
 * terminates the current process, and consumes a one-shot cold-start grant in
 * the new process. That new process is therefore a genuine Android cold start:
 * original Application.onCreate(), native/root initialization, SplashActivity
 * onCreate/onResume and the original permission flow all run in their normal
 * order without being short-circuited by the gate.
 */
public class LicenseGateProvider extends ContentProvider {
    private static final String PREF = LicenseActivity.PREF;
    private static final String COLD_GRANT = "fac_cold_start_grant";
    private static volatile boolean installed;
    private static volatile boolean handoffStarted;
    private static volatile boolean overlayStarted;
    private static SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override public boolean onCreate() {
        final Context ctx=getContext();
        if(ctx==null) return true;

        final SharedPreferences prefs=ctx.getSharedPreferences(PREF,0);
        final boolean coldGrant=prefs.getBoolean(COLD_GRANT,false)
                && prefs.getBoolean("verified",false);

        if(coldGrant) {
            // One-shot token: this process is the protected/original cold start.
            prefs.edit().remove(COLD_GRANT).apply();
            installAllowedProcessObserver(ctx);
            return true;
        }

        // Normal launcher/process start must verify online first.
        prefs.edit().putBoolean("verified",false).remove(COLD_GRANT).apply();
        installGateObserver(ctx,prefs);
        return true;
    }

    private void installGateObserver(final Context ctx, final SharedPreferences prefs) {
        if(installed) return;
        installed=true;
        final Application app=(Application)ctx.getApplicationContext();

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if(!"com.core.activity.SplashActivity".equals(activity.getClass().getName())) return;
                if(activity.getSharedPreferences(PREF,0).getBoolean("verified",false)) return;
                try {
                    Intent gate=new Intent(activity, LicenseActivity.class);
                    gate.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    activity.startActivity(gate);
                } catch(Exception ignored) {}
                try { activity.finish(); } catch(Exception ignored) {}
            }
            @Override public void onActivityStarted(Activity a){}
            @Override public void onActivityResumed(Activity a){}
            @Override public void onActivityPaused(Activity a){}
            @Override public void onActivityStopped(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
            @Override public void onActivityDestroyed(Activity a){}
        });

        // LicenseActivity already writes verified=true after a strict successful
        // server response. Observe that single transition and perform a genuine
        // process-level handoff instead of starting SplashActivity in-process.
        prefListener=new SharedPreferences.OnSharedPreferenceChangeListener(){
            @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){
                if(!"verified".equals(key) || !p.getBoolean("verified",false)) return;
                if(handoffStarted) return;
                handoffStarted=true;
                p.edit().putBoolean(COLD_GRANT,true).commit();
                scheduleOriginalColdStart(ctx);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    private void scheduleOriginalColdStart(final Context ctx) {
        try {
            Intent original=new Intent(Intent.ACTION_MAIN);
            original.addCategory(Intent.CATEGORY_LAUNCHER);
            original.setComponent(new ComponentName(ctx.getPackageName(),"com.core.activity.SplashActivity"));
            original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);

            int flags=PendingIntent.FLAG_CANCEL_CURRENT;
            if(Build.VERSION.SDK_INT>=23) flags|=PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi=PendingIntent.getActivity(ctx,51031,original,flags);
            AlarmManager am=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
            if(am!=null) am.set(AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+700L,pi);

            // Give SharedPreferences.commit()/AlarmManager a moment to settle,
            // then end the gate process. The AlarmManager launch creates a new
            // Linux/Android process and therefore reruns the untouched original
            // Application.onCreate + Splash permission/root initialization.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){
                @Override public void run(){
                    try { Process.killProcess(Process.myPid()); }
                    finally { System.exit(0); }
                }
            },250L);
        } catch(Exception e) {
            handoffStarted=false;
            ctx.getSharedPreferences(PREF,0).edit().remove(COLD_GRANT).putBoolean("verified",false).apply();
        }
    }

    private void installAllowedProcessObserver(final Context ctx) {
        final Application app=(Application)ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityResumed(Activity activity) {
                // Do not request overlay permission here. The untouched original
                // Splash flow owns all permission dialogs. We merely wait until
                // Android reports that the permission is available, then start
                // the FAC session guard/overlay.
                if(overlayStarted) return;
                if(!activity.getSharedPreferences(PREF,0).getBoolean("verified",false)) return;
                if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(activity)) return;
                try {
                    activity.startService(new Intent(activity,LicenseOverlayService.class));
                    overlayStarted=true;
                } catch(Exception ignored) {}
            }
            @Override public void onActivityCreated(Activity a,Bundle b){}
            @Override public void onActivityStarted(Activity a){}
            @Override public void onActivityPaused(Activity a){}
            @Override public void onActivityStopped(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
            @Override public void onActivityDestroyed(Activity a){}
        });
    }

    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String so){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
