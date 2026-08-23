package fac.license;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import fac.license.overlay.LicenseOverlayService;
import fac.license.ui.LicenseActivity;

/**
 * Thin wrapper around the untouched original com.nx.main.App.
 *
 * In the isolated :facgate process we intentionally do NOT execute the
 * original App.onCreate(), so native/root/core initialization cannot happen
 * before license verification. In the real main process, the one-shot cold
 * grant is required; only then is super.onCreate() called and the original
 * startup chain runs exactly once from a fresh process.
 */
public class FacApplication extends com.nx.main.App {
    private static volatile boolean overlayStarted;

    @Override public void onCreate() {
        String process=currentProcessName(this);
        if(process!=null && process.endsWith(":facgate")) {
            // License UI process only. Never initialize the protected runtime.
            return;
        }

        SharedPreferences p=getSharedPreferences(LicenseActivity.PREF,0);
        boolean allowed=p.getBoolean(LicenseGateProvider.COLD_GRANT,false)
                && p.getBoolean("verified",false);
        if(!allowed) {
            // GateProvider normally terminates this process before Application
            // startup. This is only a fail-closed safety net.
            return;
        }

        p.edit().remove(LicenseGateProvider.COLD_GRANT).commit();

        // This is the untouched original runtime initialization: root listener,
        // native core/plugins, and all original Application startup code.
        super.onCreate();
        installOverlayObserver();
    }

    private void installOverlayObserver(){
        registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityResumed(Activity a){
                if(overlayStarted) return;
                if(!a.getSharedPreferences(LicenseActivity.PREF,0).getBoolean("verified",false)) return;
                if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(a)) return;
                String n=a.getClass().getName();
                // Do not inject FAC UI into the original root/permission splash.
                if("com.core.activity.SplashActivity".equals(n)) return;
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
    }

    private static String currentProcessName(Context ctx){
        if(Build.VERSION.SDK_INT>=28){
            try{return Application.getProcessName();}catch(Throwable ignored){}
        }
        try{
            int pid=android.os.Process.myPid();
            android.app.ActivityManager am=(android.app.ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if(am!=null){
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list=am.getRunningAppProcesses();
                if(list!=null) for(android.app.ActivityManager.RunningAppProcessInfo i:list) if(i.pid==pid) return i.processName;
            }
        }catch(Exception ignored){}
        return ctx.getPackageName();
    }
}
