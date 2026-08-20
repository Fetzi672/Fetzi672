package fac.guard;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * V15.2.3 overlay controller.
 *
 * There is deliberately NO fullscreen TYPE_APPLICATION_OVERLAY while the panel
 * is closed. Runtime text masks are owned by TextMaskAccessibilityService as a
 * trusted TYPE_ACCESSIBILITY_OVERLAY. Closed-state imgui-notify uses only a
 * small top-right window. The interactive ImGui panel is physically bounded and
 * NOT_TOUCH_MODAL, so touches outside its rectangle reach the original app.
 */
public final class FloatingMenuController {
    public static final int NOTICE_SUCCESS=1,NOTICE_WARNING=2,NOTICE_ERROR=3,NOTICE_INFO=4;
    private static Context app;
    private static WindowManager wm;
    private static View bubble;
    private static ImGuiOverlayView surface;
    private static WindowManager.LayoutParams surfaceLp;
    private static boolean panelOpen;
    private static final ArrayList<Notice> pending=new ArrayList<>();
    private static final Handler main=new Handler(Looper.getMainLooper());

    private static final Runnable statusRefresh=new Runnable(){
        @Override public void run(){
            if(surface!=null&&panelOpen){surface.updateStatus(buildStatus(app));main.postDelayed(this,1000L);}
        }
    };

    private static final Runnable releaseToastSurface=new Runnable(){
        @Override public void run(){
            if(panelOpen||surface==null)return;
            if(wm==null&&app!=null)ensureWm();
            if(surface!=null&&wm!=null){try{wm.removeView(surface);}catch(Exception ignored){}}
            surface=null;surfaceLp=null;
        }
    };

    private static final class Notice{
        final int type;final String title,body;
        Notice(int t,String a,String b){type=t;title=a;body=b;}
    }

    private FloatingMenuController() {}

    public static boolean canOverlay(Context c){return Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(c);}

    public static void requestPermission(Context c){
        if(Build.VERSION.SDK_INT<23)return;
        try{Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+c.getPackageName()));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);}catch(Exception ignored){}
    }

    public static void showBubble(Context c){
        if(c==null||!canOverlay(c)||!LicenseStore.isSessionActive(c)||!LicenseStore.isLocallyValid(c))return;
        app=c.getApplicationContext();
        main.post(()->{
            ensureWm();
            if(panelOpen||bubble!=null||wm==null)return;
            final View b=createBubbleView();
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

    private static View createBubbleView(){
        ImageView b=new ImageView(app);
        b.setContentDescription("FAC Guard");
        b.setScaleType(ImageView.ScaleType.CENTER_CROP);
        b.setPadding(dp(3),dp(3),dp(3),dp(3));
        GradientDrawable bg=new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);bg.setColor(Color.rgb(190,32,43));bg.setStroke(dp(2),Color.argb(235,255,255,255));
        b.setBackground(bg);b.setElevation(dp(8));
        if(Build.VERSION.SDK_INT>=21){b.setOutlineProvider(ViewOutlineProvider.BACKGROUND);b.setClipToOutline(true);}
        try(InputStream in=app.getAssets().open("fac/fac_bubble.jpg")){
            Bitmap bm=BitmapFactory.decodeStream(in);if(bm!=null)b.setImageBitmap(bm);
        }catch(Exception ignored){b.setImageResource(android.R.drawable.ic_lock_lock);}
        return b;
    }

    /** Remove FAC app-overlays and clear the trusted accessibility text layer. */
    public static void hide(Context c){
        if(c!=null)app=c.getApplicationContext();
        TextMaskAccessibilityService.clearMask();
        main.post(()->{
            panelOpen=false;main.removeCallbacks(statusRefresh);main.removeCallbacks(releaseToastSurface);
            if(wm==null&&app!=null)ensureWm();
            if(bubble!=null&&wm!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
            if(surface!=null&&wm!=null){try{wm.removeView(surface);}catch(Exception ignored){}surface=null;surfaceLp=null;}
        });
    }

    /** Backward-compatible bridge; masks are no longer rendered in the SAW surface. */
    public static void updateTextMask(Context c,String protocol){
        if(c!=null)app=c.getApplicationContext();
        TextMaskAccessibilityService.publishMask(protocol==null?"":protocol);
    }

    public static void notifySuccess(Context c,String title,String body){notify(c,NOTICE_SUCCESS,title,body);}
    public static void notifyWarning(Context c,String title,String body){notify(c,NOTICE_WARNING,title,body);}
    public static void notifyError(Context c,String title,String body){notify(c,NOTICE_ERROR,title,body);}
    public static void notifyInfo(Context c,String title,String body){notify(c,NOTICE_INFO,title,body);}

    public static void notify(Context c,int type,String title,String body){
        if(c!=null)app=c.getApplicationContext();
        final Notice n=new Notice(type,title==null?"FAC":title,body==null?"":body);
        main.post(()->{
            if(app!=null&&canOverlay(app)&&LicenseStore.isSessionActive(app)&&RootOps.isTargetRunning()){
                ensureSurface();
                if(surface!=null){
                    if(!panelOpen)applyToastLayout();
                    surface.notify(n.type,n.title,n.body);
                    if(!panelOpen)scheduleToastRelease();
                    return;
                }
            }
            if(pending.size()>=8)pending.remove(0);pending.add(n);
        });
    }

    private static void ensureSurface(){
        if(surface!=null||app==null||!canOverlay(app))return;
        ensureWm();if(wm==null)return;
        final ImGuiOverlayView v=new ImGuiOverlayView(app,buildStatus(app),BotSettingsBridge.loadProtocol(app),new ImGuiOverlayView.Listener(){
            @Override public void onClose(){closePanel();}
            @Override public void onSave(String protocol){
                boolean ok=BotSettingsBridge.saveProtocol(app,protocol);
                if(ok){notifySuccess(app,"Bot Settings","Settings saved and applied");closePanel();}
                else{notifyError(app,"Bot Settings",BotSettingsBridge.lastError());if(surface!=null)surface.updateStatus(buildStatus(app));}
            }
            @Override public void onRecheck(){
                LicenseStore.setLastEvent(app,"License recheck in progress...");notifyInfo(app,"License","Checking FAC license...");LicenseRecheckBridge.recheck(app);
            }
            @Override public void onLayout(float widthFraction,float heightFraction,float uiScale){
                UiPreferences.save(app,widthFraction,heightFraction,uiScale);
                if(surface!=null){surface.setUiLayout(UiPreferences.width(app),UiPreferences.height(app),UiPreferences.scale(app));if(panelOpen)applyPanelLayout();}
            }
        });
        v.setUiLayout(UiPreferences.width(app),UiPreferences.height(app),UiPreferences.scale(app));
        surfaceLp=toastParams();
        try{
            wm.addView(v,surfaceLp);surface=v;
            if(!pending.isEmpty()){
                for(Notice n:new ArrayList<>(pending))surface.notify(n.type,n.title,n.body);
                pending.clear();
                if(!panelOpen)scheduleToastRelease();
            }
        }catch(Exception e){surface=null;surfaceLp=null;}
    }

    private static void openPanel(){
        if(app==null||!canOverlay(app))return;
        main.removeCallbacks(releaseToastSurface);
        panelOpen=true;
        ensureSurface();if(surface==null){panelOpen=false;return;}
        if(bubble!=null&&wm!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
        surface.updateSettings(BotSettingsBridge.loadProtocol(app));surface.updateStatus(buildStatus(app));
        surface.setUiLayout(UiPreferences.width(app),UiPreferences.height(app),UiPreferences.scale(app));
        applyPanelLayout();
        surface.setPanelOpen(true);
        surface.requestFocus();main.removeCallbacks(statusRefresh);main.post(statusRefresh);
    }

    private static void applyPanelLayout(){
        if(!panelOpen||surface==null||surfaceLp==null||wm==null||app==null)return;
        DisplayMetrics dm=screenMetrics();int sw=Math.max(1,dm.widthPixels),sh=Math.max(1,dm.heightPixels);
        int pw=Math.max(dp(320),(int)(sw*UiPreferences.width(app)));
        int ph=Math.max(dp(360),(int)(sh*UiPreferences.height(app)));
        pw=Math.min(sw,pw);ph=Math.min(sh,ph);
        surfaceLp.width=pw;surfaceLp.height=ph;
        surfaceLp.x=(sw-pw)/2;surfaceLp.y=(sh-ph)/2;
        surfaceLp.gravity=Gravity.TOP|Gravity.START;
        surfaceLp.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        surfaceLp.alpha=1.0f;
        surfaceLp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try{wm.updateViewLayout(surface,surfaceLp);}catch(Exception ignored){}
    }

    private static void closePanel(){
        panelOpen=false;main.removeCallbacks(statusRefresh);
        if(surface!=null&&surfaceLp!=null){
            surface.setPanelOpen(false);
            applyToastLayout();
            scheduleToastRelease();
        }
        if(app!=null&&LicenseStore.isSessionActive(app)&&LicenseStore.isLocallyValid(app)&&RootOps.isTargetRunning())showBubble(app);
    }

    /** Small closed-state imgui-notify window; never spans the original app. */
    private static void applyToastLayout(){
        if(surface==null||surfaceLp==null||wm==null||app==null||panelOpen)return;
        DisplayMetrics dm=screenMetrics();int sw=Math.max(1,dm.widthPixels),sh=Math.max(1,dm.heightPixels);
        int pw=Math.min(sw,dp(390)),ph=Math.min(sh,dp(230));
        surfaceLp.width=pw;surfaceLp.height=ph;
        surfaceLp.x=Math.max(0,sw-pw-dp(8));surfaceLp.y=dp(8);
        surfaceLp.gravity=Gravity.TOP|Gravity.START;
        surfaceLp.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            |WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        surfaceLp.alpha=1.0f;
        surfaceLp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        try{wm.updateViewLayout(surface,surfaceLp);}catch(Exception ignored){}
    }

    private static void scheduleToastRelease(){
        main.removeCallbacks(releaseToastSurface);
        main.postDelayed(releaseToastSurface,8000L);
    }

    private static void ensureWm(){if(wm==null&&app!=null)wm=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);}

    private static WindowManager.LayoutParams bubbleParams(){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(58),dp(58),type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;p.x=dp(12);p.y=dp(180);return p;
    }

    private static WindowManager.LayoutParams toastParams(){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        DisplayMetrics dm=screenMetrics();int sw=Math.max(1,dm.widthPixels),sh=Math.max(1,dm.heightPixels);
        int pw=Math.min(sw,dp(390)),ph=Math.min(sh,dp(230));
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(pw,ph,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                |WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;p.x=Math.max(0,sw-pw-dp(8));p.y=dp(8);p.alpha=1.0f;return p;
    }

    private static DisplayMetrics screenMetrics(){
        DisplayMetrics dm=new DisplayMetrics();
        if(wm!=null)try{wm.getDefaultDisplay().getRealMetrics(dm);return dm;}catch(Exception ignored){}
        if(app!=null)return app.getResources().getDisplayMetrics();
        dm.widthPixels=1080;dm.heightPixels=1920;dm.density=1f;return dm;
    }

    private static int dp(int n){float d=app==null?1f:app.getResources().getDisplayMetrics().density;return (int)(n*d+.5f);}

    private static String buildStatus(Context c){
        StringBuilder s=new StringBuilder();
        put(s,"license",LicenseStore.isSessionActive(c)&&LicenseStore.isLocallyValid(c)?"ACTIVE":(LicenseStore.isLocallyValid(c)?"VERIFIED / LOCKED":"REQUIRED"));
        put(s,"expiry",LicenseStore.expiry(c));put(s,"devices",LicenseStore.bound(c)+" / "+LicenseStore.limit(c));
        put(s,"guard",LicenseStore.isArmed(c)?"ARMED":"DISARMED");put(s,"root",RootOps.hasRoot()?"READY":"NOT AVAILABLE");
        put(s,"runtime",PayloadManager.isExpectedRuntimeInstalled(c)?"ORIGINAL SIGNATURE VERIFIED":"NOT VERIFIED");
        put(s,"text_mask",RootOps.isTextMaskAccessibilityEnabled(c)?"ACTIVE":"NOT ENABLED");
        put(s,"settings_error",BotSettingsBridge.lastError());put(s,"last_event",LicenseStore.lastEvent(c));
        try{put(s,"device_id",DeviceIdentity.get(c));}catch(Exception e){put(s,"device_id","Unavailable");}
        long anchor=LicenseStore.verificationElapsed(c),now=SystemClock.elapsedRealtime();long left=anchor>0&&now>=anchor?Math.max(0,300-(now-anchor)/1000):0;put(s,"next_recheck",left+"s");
        return s.toString();
    }

    private static void put(StringBuilder s,String k,String v){String x=v==null?"":v;s.append(k).append('\t').append(Base64.encodeToString(x.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)).append('\n');}
}
