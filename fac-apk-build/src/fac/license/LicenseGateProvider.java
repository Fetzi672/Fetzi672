package fac.license;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import fac.license.ui.LicenseActivity;

/**
 * Process-local gate that keeps the original SplashActivity as the real
 * MAIN/LAUNCHER. This preserves the original NxScript/Aiwan launcher flow.
 * A fresh process is always fail-closed until LicenseActivity has verified
 * the key against the FAC server.
 */
public class LicenseGateProvider extends ContentProvider {
    private static volatile boolean installed;

    @Override public boolean onCreate() {
        final Context ctx=getContext();
        if(ctx==null) return true;

        // A process restart must require a fresh online verification.
        ctx.getSharedPreferences(LicenseActivity.PREF,0)
           .edit().putBoolean("verified",false).apply();

        if(!installed) {
            installed=true;
            Application app=(Application)ctx.getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
                @Override public void onActivityCreated(Activity activity, Bundle state) {
                    if(!"com.core.activity.SplashActivity".equals(activity.getClass().getName())) return;
                    boolean verified=activity.getSharedPreferences(LicenseActivity.PREF,0)
                                             .getBoolean("verified",false);
                    if(verified) return;
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
        }
        return true;
    }

    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String so){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
