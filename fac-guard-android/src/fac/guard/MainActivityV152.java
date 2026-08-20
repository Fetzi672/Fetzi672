package fac.guard;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;

/** V15.2 launcher wrapper: keeps V15 licensing/payload flow and adds ImGui/text-mask setup. */
public class MainActivityV152 extends MainActivity {
    private boolean overlayPrompted;

    @Override public void onCreate(android.os.Bundle b){
        super.onCreate(b);

        // If an update temporarily re-enables the alias, immediately restore the
        // post-setup hidden state. The Activity itself remains callable directly.
        if(LicenseStore.isVerified(this) && PayloadManager.isExpectedRuntimeInstalled(this))
            LauncherVisibility.hide(this);

        FloatingAutoMonitor.start(this);
        ensureStorageAccess();
        new Thread(()->{
            boolean root=RootOps.hasRoot();
            boolean granted=root&&RootOps.enableUiPrivileges(this);
            runOnUiThread(()->{
                if(!FloatingMenuController.canOverlay(this))maybeAskOverlay();
                if(granted)FloatingMenuController.notifyInfo(this,"FAC UI","Floating menu and English text mask enabled");
            });
        },"FAC-V15.2-UI-Setup").start();
    }

    @Override protected void onResume(){
        super.onResume();
        FloatingAutoMonitor.start(this);
        if(FloatingMenuController.canOverlay(this)&&LicenseStore.isSessionActive(this)&&RootOps.isTargetRunning())
            FloatingMenuController.showBubble(this);
    }

    /**
     * MainActivity finishes itself only after it has successfully launched the
     * authorized original runtime. At that point remove the Guard's launcher
     * entry, but do not disable the real Activity or any background services.
     */
    @Override public void finish(){
        if(LicenseStore.isSessionActive(this)
                && LicenseStore.isLocallyValid(this)
                && PayloadManager.isExpectedRuntimeInstalled(this)){
            LauncherVisibility.hide(this);
        }
        super.finish();
    }

    private void maybeAskOverlay(){
        if(Build.VERSION.SDK_INT<23||FloatingMenuController.canOverlay(this)||overlayPrompted)return;
        overlayPrompted=true;
        new AlertDialog.Builder(this)
            .setTitle("FAC Floating Menu")
            .setMessage("Allow 'Display over other apps' so FAC can show its floating Dear ImGui controls and English text replacement above the original runtime.")
            .setPositiveButton("ALLOW",(d,w)->FloatingMenuController.requestPermission(this))
            .setNegativeButton("LATER",null)
            .show();
    }

    private void ensureStorageAccess(){
        if(Build.VERSION.SDK_INT>=23&&Build.VERSION.SDK_INT<=32){
            if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
                try{requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},1520);}catch(Exception ignored){}
            }
        }
    }
}
