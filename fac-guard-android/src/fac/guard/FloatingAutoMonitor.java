package fac.guard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Keeps the FAC bubble synchronized with the protected runtime. */
public final class FloatingAutoMonitor {
    private static final Handler H=new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean running;
    private FloatingAutoMonitor() {}

    public static synchronized void start(Context c){
        app=c.getApplicationContext();
        if(running)return;
        running=true;H.post(tick);
    }

    private static final Runnable tick=new Runnable(){
        @Override public void run(){
            if(app==null){running=false;return;}
            boolean show=FloatingMenuController.canOverlay(app)
                &&LicenseStore.isSessionActive(app)
                &&LicenseStore.isLocallyValid(app)
                &&PayloadManager.isExpectedRuntimeInstalled(app)
                &&RootOps.isTargetRunning();
            if(show)FloatingMenuController.showBubble(app);else FloatingMenuController.hide(app);
            H.postDelayed(this,850L);
        }
    };
}
