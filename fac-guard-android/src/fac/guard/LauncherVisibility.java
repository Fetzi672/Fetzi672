package fac.guard;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Controls only the launcher alias; the Guard app and its real Activity stay installed. */
public final class LauncherVisibility {
    private LauncherVisibility() {}

    private static ComponentName alias(Context c){
        return new ComponentName(c.getPackageName(),"fac.guard.LauncherAlias");
    }

    public static void hide(Context c){
        try{
            c.getPackageManager().setComponentEnabledSetting(
                alias(c),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        }catch(Exception ignored){}
    }

    public static void show(Context c){
        try{
            c.getPackageManager().setComponentEnabledSetting(
                alias(c),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        }catch(Exception ignored){}
    }

    public static boolean isHidden(Context c){
        try{
            int state=c.getPackageManager().getComponentEnabledSetting(alias(c));
            return state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                   state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                   state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        }catch(Exception e){return false;}
    }
}
