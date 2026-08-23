package fac.guard;

import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(context==null)return;
        LicenseStore.clearSession(context);
        LicenseStore.setLastEvent(context,"FAC Guard boot initialization");
        if(!LicenseStore.isArmed(context))return;
        try{
            Intent s=new Intent(context,GuardService.class);
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(s);else context.startService(s);
        }catch(Exception ignored){}
    }
}
