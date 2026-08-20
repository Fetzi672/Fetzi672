package fac.guard;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent user-controlled sizing for the bounded V15.2.2 ImGui panel. */
public final class UiPreferences {
    private static final String PREF="fac_imgui_v1522";
    private static final float DEFAULT_WIDTH=0.78f;
    private static final float DEFAULT_HEIGHT=0.78f;
    private static final float DEFAULT_SCALE=1.00f;
    private UiPreferences() {}

    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREF,0);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    public static float width(Context c){return clamp(p(c).getFloat("panel_width",DEFAULT_WIDTH),0.55f,0.96f);}
    public static float height(Context c){return clamp(p(c).getFloat("panel_height",DEFAULT_HEIGHT),0.48f,0.94f);}
    public static float scale(Context c){return clamp(p(c).getFloat("ui_scale",DEFAULT_SCALE),0.75f,1.35f);}

    public static void save(Context c,float width,float height,float scale){
        p(c).edit()
            .putFloat("panel_width",clamp(width,0.55f,0.96f))
            .putFloat("panel_height",clamp(height,0.48f,0.94f))
            .putFloat("ui_scale",clamp(scale,0.75f,1.35f))
            .apply();
    }

    public static void reset(Context c){
        p(c).edit()
            .putFloat("panel_width",DEFAULT_WIDTH)
            .putFloat("panel_height",DEFAULT_HEIGHT)
            .putFloat("ui_scale",DEFAULT_SCALE)
            .apply();
    }
}
