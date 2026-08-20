package fac.guard;

import android.content.Context;

/** Manual online recheck invoked from the Dear ImGui Overview tab. */
public final class LicenseRecheckBridge {
    private LicenseRecheckBridge() {}

    public static void recheck(Context context){
        final Context app=context.getApplicationContext();
        new Thread(()->{
            try{
                String key=LicenseStore.loadKey(app);
                if(key==null||key.trim().length()<8)throw new SecurityException("No saved FAC license key.");
                LicenseApi.Result r=LicenseApi.verify(app,key);
                if(!LicenseStore.saveVerification(app,r))throw new IllegalStateException("Could not persist FAC verification.");
                LicenseStore.setSessionActive(app,true);
                LicenseStore.setLastEvent(app,"License recheck successful.");
            }catch(Exception e){
                LicenseStore.invalidate(app);
                LicenseStore.setLastEvent(app,"License recheck failed: "+(e.getMessage()==null?"server verification failed":e.getMessage()));
                RootOps.forceStopTarget();
                FloatingMenuController.hide(app);
                RootOps.openGuardUi(app,"license_recheck_failed");
            }
        },"FAC-ImGui-Recheck").start();
    }
}
