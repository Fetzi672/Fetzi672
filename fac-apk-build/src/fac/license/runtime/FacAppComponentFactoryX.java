package fac.license.runtime;

import android.app.Activity;
import android.content.Intent;
import android.support.v4.app.CoreComponentFactory;
import fac.license.ui.LicenseActivity;

/**
 * V11 minimal launcher interception.
 *
 * The manifest still declares the untouched original SplashActivity as the
 * package MAIN/LAUNCHER. Android asks this factory to instantiate that
 * component. Until FAC has verified the license in the current process we
 * return LicenseActivity instead. After verification the static runtime flag
 * is armed and every later SplashActivity instantiation is delegated to the
 * original CoreComponentFactory unchanged.
 */
public final class FacAppComponentFactoryX extends CoreComponentFactory {
    private static final String ORIGINAL_SPLASH = "com.core.activity.SplashActivity";
    private static volatile boolean runtimeAuthorized;

    public static void authorizeRuntime(){ runtimeAuthorized = true; }
    public static void revokeRuntime(){ runtimeAuthorized = false; }
    public static boolean isRuntimeAuthorized(){ return runtimeAuthorized; }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (ORIGINAL_SPLASH.equals(className) && !runtimeAuthorized) {
            return new LicenseActivity();
        }
        return super.instantiateActivity(cl, className, intent);
    }
}
