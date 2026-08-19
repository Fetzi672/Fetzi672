package fac.license;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import fac.license.overlay.LicenseOverlayService;
import fac.license.ui.LicenseActivity;

/**
 * V9 process handoff without a kill-provider or secondary-process dependency.
 *
 * The package launcher is LicenseActivity. After a successful online verify we
 * write a very short one-shot cold-start grant, schedule the launcher through
 * AlarmManager, then terminate the current process. The next process executes
 * the untouched original com.nx.main.App first. LicenseActivity consumes the
 * grant and immediately promotes com.core.activity.SplashActivity to the task
 * root, so the original root/core/permission flow is a genuine fresh-process
 * startup.
 *
 * Internal package-launcher relaunches from the original runtime are forwarded
 * only when they occur inside the same process PID that consumed the grant.
 */
public final class SessionHandoff {
    private SessionHandoff() {}

    private static final String PREF=LicenseActivity.PREF;
    private static final String COLD_UNTIL="fac_v9_cold_until_elapsed";
    private static final String RUNTIME_PID="fac_v9_runtime_pid";
    private static volatile boolean observerInstalled;

    /**
     * @return true when LicenseActivity should finish because the request was
     * forwarded to the original SplashActivity.
     */
    public static boolean handleLauncher(Activity a){
        SharedPreferences p=a.getSharedPreferences(PREF,0);
        long now=SystemClock.elapsedRealtime();
        long until=p.getLong(COLD_UNTIL,-1L);
        boolean verified=p.getBoolean("verified",false);

        if(verified && until>=now){
            // The only cross-process grant. Consume it immediately.
            p.edit().remove(COLD_UNTIL).putInt(RUNTIME_PID,Process.myPid()).commit();
            installOverlayObserver(a.getApplication());
            startOriginal(a);
            return true;
        }

        // NxScript/internal launcher relaunch while the protected runtime is
        // already alive in this exact process.
        if(verified && p.getInt(RUNTIME_PID,-1)==Process.myPid()){
            installOverlayObserver(a.getApplication());
            startOriginal(a);
            return true;
        }

        // New process without the one-shot grant -> normal online login.
        p.edit().remove(COLD_UNTIL).putInt(RUNTIME_PID,-1).commit();
        return false;
    }

    public static void restartAfterVerify(final Activity a){
        SharedPreferences p=a.getSharedPreferences(PREF,0);
        long until=SystemClock.elapsedRealtime()+10000L;
        if(!p.edit().putLong(COLD_UNTIL,until).putInt(RUNTIME_PID,-1).commit())
            throw new IllegalStateException("could not persist cold-start grant");

        Intent gate=new Intent(Intent.ACTION_MAIN);
        gate.addCategory(Intent.CATEGORY_LAUNCHER);
        gate.setComponent(new ComponentName(a.getPackageName(),"fac.license.ui.LicenseActivity"));
        gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);

        int flags=PendingIntent.FLAG_CANCEL_CURRENT;
        if(Build.VERSION.SDK_INT>=23) flags|=PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi=PendingIntent.getActivity(a,59009,gate,flags);
        AlarmManager am=(AlarmManager)a.getSystemService(Context.ALARM_SERVICE);
        if(am==null) throw new IllegalStateException("AlarmManager unavailable");
        am.set(AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+900L,pi);

        // Kill only after ActivityManager has the restart PendingIntent.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){
            @Override public void run(){
                try{ Process.killProcess(Process.myPid()); }
                finally{ System.exit(0); }
            }
        },250L);
    }

    public static void clearRuntime(Context c){
        try{
            c.getSharedPreferences(PREF,0).edit()
                .remove(COLD_UNTIL).putInt(RUNTIME_PID,-1).commit();
        }catch(Exception ignored){}
    }

    private static void startOriginal(Activity a){
        Intent i=new Intent();
        i.setComponent(new ComponentName(a.getPackageName(),"com.core.activity.SplashActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
    }

    private static void installOverlayObserver(final Application app){
        if(observerInstalled)return;
        observerInstalled=true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityResumed(Activity a){
                if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(a)) return;
                String n=a.getClass().getName();
                if("com.core.activity.SplashActivity".equals(n)) return;
                try{ a.startService(new Intent(a,LicenseOverlayService.class)); }
                catch(Exception ignored){}
            }
            @Override public void onActivityCreated(Activity a,android.os.Bundle b){}
            @Override public void onActivityStarted(Activity a){}
            @Override public void onActivityPaused(Activity a){}
            @Override public void onActivityStopped(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,android.os.Bundle b){}
            @Override public void onActivityDestroyed(Activity a){}
        });
    }
}
