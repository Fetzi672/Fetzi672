package fac.guard;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;

/** V15.2 launcher wrapper: keeps V15 licensing/payload flow and adds overlay setup. */
public class MainActivityV152 extends MainActivity {
    private boolean overlayPrompted;

    @Override public void onCreate(android.os.Bundle b){
        super.onCreate(b);
        FloatingAutoMonitor.start(this);
        ensureStorageAccess();
        maybeAskOverlay();
    }

    @Override protected void onResume(){
        super.onResume();
        FloatingAutoMonitor.start(this);
        if(FloatingMenuController.canOverlay(this)&&LicenseStore.isSessionActive(this)&&RootOps.isTargetRunning())
            FloatingMenuController.showBubble(this);
    }

    private void maybeAskOverlay(){
        if(Build.VERSION.SDK_INT<23||FloatingMenuController.canOverlay(this)||overlayPrompted)return;
        overlayPrompted=true;
        new AlertDialog.Builder(this)
            .setTitle("FAC Floating Menu")
            .setMessage("Allow 'Display over other apps' so FAC can show the small floating bubble and Dear ImGui control panel above the original runtime.")
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
