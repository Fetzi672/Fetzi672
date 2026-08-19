package fac.license;

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
import android.os.Process;
import android.os.SystemClock;
import fac.license.ui.LicenseActivity;

/**
 * Earliest main-process license gate.
 *
 * The manifest places this provider before the original providers. On an
 * unverified launch it opens LicenseActivity in the isolated :facgate process
 * and terminates the main process before the original providers/Application
 * can initialize. After verification, a one-shot cold grant allows the next
 * main process to continue untouched into the original startup chain.
 */
public class LicenseGateProvider extends ContentProvider {
    public static final String COLD_GRANT="fac_cold_start_grant_v6";

    @Override public boolean onCreate(){
        final Context ctx=getContext();
        if(ctx==null) return true;
        SharedPreferences p=ctx.getSharedPreferences(LicenseActivity.PREF,0);
        boolean allowed=p.getBoolean(COLD_GRANT,false) && p.getBoolean("verified",false);
        if(allowed) return true;

        // Fail closed: every normal fresh main-process launch verifies online.
        p.edit().putBoolean("verified",false).remove(COLD_GRANT).commit();
        try{
            Intent gate=new Intent(ctx,LicenseActivity.class);
            gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ctx.startActivity(gate);
            // Give ActivityManager a tiny window to accept the cross-process
            // launch, but never return to provider installation in this process.
            SystemClock.sleep(80L);
        }catch(Exception ignored){}
        try{ Process.killProcess(Process.myPid()); }finally{ System.exit(0); }
        return true;
    }

    /** Called only by the isolated license process after a successful verify. */
    public static void startVerifiedColdProcess(Context ctx){
        SharedPreferences p=ctx.getSharedPreferences(LicenseActivity.PREF,0);
        p.edit().putBoolean(COLD_GRANT,true).commit();
        try{
            Intent original=new Intent(Intent.ACTION_MAIN);
            original.addCategory(Intent.CATEGORY_LAUNCHER);
            original.setComponent(new ComponentName(ctx.getPackageName(),"com.core.activity.SplashActivity"));
            original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(original);
            // LicenseActivity lives in :facgate, so this kills only the gate
            // process; the newly launched protected runtime is a clean process.
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

    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String so){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
