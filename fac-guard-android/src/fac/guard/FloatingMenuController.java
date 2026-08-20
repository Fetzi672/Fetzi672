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
 * V15.2.2 overlay controller.
 *
 * Closed state: one fullscreen NOT_TOUCHABLE surface renders masks/toasts.
 * Open state: the same surface is physically resized to the visible ImGui panel
 * and marked NOT_TOUCH_MODAL, so every touch outside the panel reaches the
 * original runtime instead of being swallowed by a transparent fullscreen view.
 */
public final class FloatingMenuController {
    public static final int NOTICE_SUCCESS=1,NOTICE_WARNING=2,NOTICE_ERROR=3,NOTICE_INFO=4;
    private static Context app;
    private static WindowManager wm;
    private static View bubble;
    private static ImGuiOverlayView surface;
    private static WindowManager.LayoutParams surfaceLp;
    private static boolean panelOpen;
    private static String latestMask="";
    private static final ArrayList<Notice> pending=new ArrayList<>();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Runnable statusRefresh=new Runnable(){
        @Override public void run(){
            if(surface!=null&&panelOpen){surface.updateStatus(buildStatus(app));main.postDelayed(this,1000L);}
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
            ensureSurface();
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

    /** Remove every FAC overlay when the protected runtime/session is gone. */
    public static void hide(Context c){
        if(c!=null)app=c.getApplicationContext();
        main.post(()->{
            panelOpen=false;main.removeCallbacks(statusRefresh);
            if(wm==null&&app!=null)ensureWm();
            if(bubble!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
            if(surface!=null){try{wm.removeView(surface);}catch(Exception ignored){}surface=null;surfaceLp=null;}
        });
    }

    public static void updateTextMask(Context c,String protocol){
        if(c!=null)app=c.getApplicationContext();latestMask=protocol==null?"":protocol;
        main.post(()->{
            if(app==null||!canOverlay(app)||!LicenseStore.isSessionActive(app)||!RootOps.isTargetRunning())return;
            ensureSurface();if(surface!=null)surface.updateTextMask(latestMask);
        });
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
                ensureSurface();if(surface!=null){surface.notify(n.type,n.title,n.body);return;}
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
                if(surface!=null){surface.setUiLayout(UiPreferences.width(app),UiPreferences.height(app),UiPreferences.scale(app));applyPanelLayout();}
            }
        });
        v.setUiLayout(UiPreferences.width(app),UiPreferences.height(app),UiPreferences.scale(app));
        surfaceLp=surfaceParams(false);
        try{
            wm.addView(v,surfaceLp);surface=v;surface.updateTextMask(latestMask);
            if(!pending.isEmpty()){for(Notice n:new ArrayList<>(pending))surface.notify(n.type,n.title,n.body);pending.clear();}
        }catch(Exception e){surface=null;surfaceLp=null;}
    }

    private static void openPanel(){
        if(app==null||!canOverlay(app))return;ensureSurface();if(surface==null)return;
        if(bubble!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
        panelOpen=true;
        surface.updateSettings(BotSettingsBridge.loadProtocol(app));surface.updateStatus(buildStatus(app));
        surface.setUiLayout(UiPreferences.width(app),UiPreferences.height(app),UiPreferences.scale(app));
        applyPanelLayout();
        surface.setPanelOpen(true);
        surface.requestFocus();main.removeCallbacks(statusRefresh);main.post(statusRefresh);
    }

    private static void applyPanelLayout(){
        if(!panelOpen||surface==null||surfaceLp==null||wm==null||app==null)return;
        DisplayMetrics dm=new DisplayMetrics();
        try{wm.getDefaultDisplay().getRealMetrics(dm);}catch(Exception e){dm=app.getResources().getDisplayMetrics();}
        int sw=Math.max(1,dm.widthPixels),sh=Math.max(1,dm.heightPixels);
        int pw=Math.max(dp(320),(int)(sw*UiPreferences.width(app)));
        int ph=Math.max(dp(360),(int)(sh*UiPreferences.height(app)));
        pw=Math.min(sw,pw);ph=Math.min(sh,ph);
        surfaceLp.width=pw;surfaceLp.height=ph;
        surfaceLp.x=(sw-pw)/2;surfaceLp.y=(sh-ph)/2;
        surfaceLp.gravity=Gravity.TOP|Gravity.START;
        surfaceLp.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        surfaceLp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try{wm.updateViewLayout(surface,surfaceLp);}catch(Exception ignored){}
    }

    private static void closePanel(){
        panelOpen=false;main.removeCallbacks(statusRefresh);
        if(surface!=null&&surfaceLp!=null){
            surface.setPanelOpen(false);
            surfaceLp.width=WindowManager.LayoutParams.MATCH_PARENT;surfaceLp.height=WindowManager.LayoutParams.MATCH_PARENT;
            surfaceLp.x=0;surfaceLp.y=0;surfaceLp.gravity=Gravity.TOP|Gravity.START;
            surfaceLp.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                |WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            surfaceLp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            try{wm.updateViewLayout(surface,surfaceLp);}catch(Exception ignored){}
            surface.updateTextMask(latestMask);
        }
        if(LicenseStore.isSessionActive(app)&&LicenseStore.isLocallyValid(app)&&RootOps.isTargetRunning())showBubble(app);
    }

    private static void ensureWm(){if(wm==null&&app!=null)wm=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);}

    private static WindowManager.LayoutParams bubbleParams(){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(58),dp(58),type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;p.x=dp(12);p.y=dp(180);return p;
    }

    private static WindowManager.LayoutParams surfaceParams(boolean interactive){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        int flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if(interactive)flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        else flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,type,flags,android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;return p;
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
