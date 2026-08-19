package fac.license;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import fac.license.overlay.LicenseOverlayService;
import fac.license.ui.LicenseActivity;

/**
 * V10 in-process session handoff.
 *
 * The V9 AlarmManager + SIGKILL cold restart has deliberately been removed.
 * MuMu already initializes the original Application/CoreProvider before the
 * launcher Activity, so killing and recreating the process only duplicates the
 * most memory-expensive NX/Core/WebView startup path.
 *
 * V10 performs the online FAC verification in LicenseActivity and then opens
 * the untouched original SplashActivity in the SAME process. A process-local
 * PID marker is used only to recognize package-launcher relaunches originating
 * from that already verified runtime. A genuinely new process never trusts an
 * old PID marker: it returns to LicenseActivity and verifies the saved key
 * online again.
 */
public final class SessionHandoff {
    private SessionHandoff() {}

    private static final String PREF=LicenseActivity.PREF;
    private static final String RUNTIME_PID="fac_v10_runtime_pid";
    private static final long OVERLAY_DELAY_MS=30000L;

    private static volatile boolean observerInstalled;
    private static volatile boolean overlayScheduled;
    private static volatile boolean overlayStarted;
    private static Handler overlayHandler;

    /**
     * @return true when LicenseActivity was only an internal launcher relaunch
     * and has been forwarded to the untouched original SplashActivity.
     */
    public static boolean handleLauncher(Activity a){
        SharedPreferences p=a.getSharedPreferences(PREF,0);
        boolean verified=p.getBoolean("verified",false);
        int runtimePid=p.getInt(RUNTIME_PID,-1);

        if(verified && runtimePid==Process.myPid()){
            installOverlayObserver(a.getApplication());
            startOriginal(a);
            return true;
        }

        // A fresh/recreated process must never inherit runtime authorization
        // from a dead PID. LicenseActivity will online-verify the saved key.
        if(runtimePid!=-1){
            p.edit().putInt(RUNTIME_PID,-1).commit();
        }
        return false;
    }

    /** Called only after a strict HTTP-200 online FAC verification succeeded. */
    public static void activateAfterVerify(Activity a){
        SharedPreferences p=a.getSharedPreferences(PREF,0);
        if(!p.getBoolean("verified",false))
            throw new IllegalStateException("verified state missing");
        if(!p.edit().putInt(RUNTIME_PID,Process.myPid()).commit())
            throw new IllegalStateException("could not persist runtime pid");

        installOverlayObserver(a.getApplication());
        startOriginal(a);
    }

    public static void clearRuntime(Context c){
        try{
            c.getSharedPreferences(PREF,0).edit().putInt(RUNTIME_PID,-1).commit();
        }catch(Exception ignored){}
    }

    private static void startOriginal(Activity a){
        Intent i=new Intent();
        i.setComponent(new ComponentName(a.getPackageName(),"com.core.activity.SplashActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
    }

    /**
     * Delay the FAC overlay until the original MainActivity has had time to
     * settle. This keeps overlay allocation / guard startup out of the critical
     * NX first-UI -> script-start window seen in the MuMu low-memory log.
     */
    private static void installOverlayObserver(final Application app){
        if(observerInstalled)return;
        observerInstalled=true;
        overlayHandler=new Handler(Looper.getMainLooper());

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityResumed(final Activity a){
                if(overlayStarted || overlayScheduled) return;
                if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(a)) return;

                String n=a.getClass().getName();
                if(!"com.nx.main.activity.MainActivity".equals(n)) return;

                overlayScheduled=true;
                overlayHandler.postDelayed(new Runnable(){
                    @Override public void run(){
                        try{
                            if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(app)){
                                overlayScheduled=false;
                                return;
                            }
                            app.startService(new Intent(app,LicenseOverlayService.class));
                            overlayStarted=true;
                        }catch(Exception ignored){
                            // If Android rejects a background service start,
                            // allow a future MainActivity resume to retry.
                            overlayScheduled=false;
                        }
                    }
                },OVERLAY_DELAY_MS);
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
