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
                FloatingMenuController.notifySuccess(app,"License verified",r.devicesBound+" / "+r.devicesLimit+" devices • session remains active");
            }catch(Exception e){
                String reason=e.getMessage()==null?"Server verification failed":e.getMessage();
                FloatingMenuController.notifyError(app,"License check failed",reason);
                try{Thread.sleep(450L);}catch(Exception ignored){}
                LicenseStore.invalidate(app);
                LicenseStore.setLastEvent(app,"License recheck failed: "+reason);
                RootOps.forceStopTarget();
                FloatingMenuController.hide(app);
                RootOps.openGuardUi(app,"license_recheck_failed");
            }
        },"FAC-ImGui-Recheck").start();
    }
}
