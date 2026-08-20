package fac.guard;

import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Owns the lightweight FAC bubble and creates the native ImGui surface only
 * while the panel is open. No view is injected into the protected app.
 */
public final class FloatingMenuController {
    private static Context app;
    private static WindowManager wm;
    private static TextView bubble;
    private static ImGuiOverlayView panel;
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Runnable statusRefresh=new Runnable(){
        @Override public void run(){
            if(panel!=null){panel.updateStatus(buildStatus(app));main.postDelayed(this,1000L);}
        }
    };

    private FloatingMenuController() {}

    public static boolean canOverlay(Context c){return Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(c);}

    public static void requestPermission(Context c){
        if(Build.VERSION.SDK_INT<23)return;
        try{
            Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);
        }catch(Exception ignored){}
    }

    public static void showBubble(Context c){
        if(c==null||!canOverlay(c)||!LicenseStore.isSessionActive(c)||!LicenseStore.isLocallyValid(c))return;
        app=c.getApplicationContext();
        main.post(()->{
            ensureWm();
            if(panel!=null||bubble!=null||wm==null)return;
            final TextView b=new TextView(app);
            b.setText("FAC");b.setTextColor(Color.WHITE);b.setTextSize(12);b.setGravity(Gravity.CENTER);
            GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);bg.setColor(Color.rgb(190,32,43));bg.setStroke(dp(2),Color.argb(220,255,255,255));
            b.setBackground(bg);b.setElevation(dp(8));
            final WindowManager.LayoutParams lp=bubbleParams();
            final float[] down=new float[4];final long[] when=new long[1];
            b.setOnTouchListener((v,e)->{
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        down[0]=e.getRawX();down[1]=e.getRawY();down[2]=lp.x;down[3]=lp.y;when[0]=SystemClock.uptimeMillis();return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x=(int)(down[2]+e.getRawX()-down[0]);lp.y=(int)(down[3]+e.getRawY()-down[1]);
                        try{wm.updateViewLayout(b,lp);}catch(Exception ignored){}return true;
                    case MotionEvent.ACTION_UP:
                        float dx=e.getRawX()-down[0],dy=e.getRawY()-down[1];
                        if(dx*dx+dy*dy<dp(10)*dp(10)&&SystemClock.uptimeMillis()-when[0]<450L)openPanel();return true;
                }
                return true;
            });
            try{wm.addView(b,lp);bubble=b;}catch(Exception ignored){bubble=null;}
        });
    }

    public static void hide(Context c){
        if(c!=null)app=c.getApplicationContext();
        main.post(()->{
            main.removeCallbacks(statusRefresh);
            if(wm==null&&app!=null)ensureWm();
            if(panel!=null){try{wm.removeView(panel);}catch(Exception ignored){}panel=null;}
            if(bubble!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
        });
    }

    private static void openPanel(){
        if(app==null||!canOverlay(app))return;
        ensureWm();if(wm==null||panel!=null)return;
        if(bubble!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
        final String settings=BotSettingsBridge.loadProtocol(app);
        final ImGuiOverlayView view=new ImGuiOverlayView(app,buildStatus(app),settings,new ImGuiOverlayView.Listener(){
            @Override public void onClose(){closePanel(false);}
            @Override public void onSave(String protocol){
                boolean ok=BotSettingsBridge.saveProtocol(app,protocol);
                Toast.makeText(app,ok?"FAC: Settings saved":"FAC: "+BotSettingsBridge.lastError(),Toast.LENGTH_LONG).show();
                if(ok)closePanel(true);
                else if(panel!=null)panel.updateStatus(buildStatus(app));
            }
            @Override public void onRecheck(){
                LicenseStore.setLastEvent(app,"License recheck in progress...");
                LicenseRecheckBridge.recheck(app);
            }
        });
        try{
            wm.addView(view,panelParams());panel=view;main.removeCallbacks(statusRefresh);main.post(statusRefresh);
        }catch(Exception e){panel=null;showBubble(app);}
    }

    private static void closePanel(boolean saved){
        main.removeCallbacks(statusRefresh);
        if(panel!=null){try{wm.removeView(panel);}catch(Exception ignored){}panel=null;}
        if(LicenseStore.isSessionActive(app)&&LicenseStore.isLocallyValid(app)&&RootOps.isTargetRunning())showBubble(app);
    }

    private static void ensureWm(){if(wm==null&&app!=null)wm=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);}

    private static WindowManager.LayoutParams bubbleParams(){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(54),dp(54),type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;p.x=dp(12);p.y=dp(180);return p;
    }

    private static WindowManager.LayoutParams panelParams(){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;return p;
    }

    private static int dp(int n){float d=app==null?1f:app.getResources().getDisplayMetrics().density;return (int)(n*d+.5f);}

    private static String buildStatus(Context c){
        StringBuilder s=new StringBuilder();
        put(s,"license",LicenseStore.isSessionActive(c)&&LicenseStore.isLocallyValid(c)?"ACTIVE":(LicenseStore.isLocallyValid(c)?"VERIFIED / LOCKED":"REQUIRED"));
        put(s,"expiry",LicenseStore.expiry(c));
        put(s,"devices",LicenseStore.bound(c)+" / "+LicenseStore.limit(c));
        put(s,"guard",LicenseStore.isArmed(c)?"ARMED":"DISARMED");
        put(s,"root",RootOps.hasRoot()?"READY":"NOT AVAILABLE");
        put(s,"runtime",PayloadManager.isExpectedRuntimeInstalled(c)?"ORIGINAL SIGNATURE VERIFIED":"NOT VERIFIED");
        put(s,"settings_error",BotSettingsBridge.lastError());
        put(s,"last_event",LicenseStore.lastEvent(c));
        try{put(s,"device_id",DeviceIdentity.get(c));}catch(Exception e){put(s,"device_id","Unavailable");}
        long anchor=LicenseStore.verificationElapsed(c),now=SystemClock.elapsedRealtime();
        long left=anchor>0&&now>=anchor?Math.max(0,300-(now-anchor)/1000):0;
        put(s,"next_recheck",left+"s");
        return s.toString();
    }

    private static void put(StringBuilder s,String k,String v){
        String x=v==null?"":v;
        s.append(k).append('\t').append(Base64.encodeToString(x.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)).append('\n');
    }
}
