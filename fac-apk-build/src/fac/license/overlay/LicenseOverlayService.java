package fac.license.overlay;

import android.app.Service;
import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import fac.license.ui.LicenseActivity;
import fac.license.ui.BotSettingsActivity;

public class LicenseOverlayService extends Service {
    private WindowManager wm;
    private View badge;
    private View menu;
    private WindowManager.LayoutParams badgeParams;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);} 
    @Override public void onCreate(){super.onCreate();showBadge();}
    @Override public int onStartCommand(Intent i,int flags,int id){if(badge==null)showBadge();return START_NOT_STICKY;}
    private int overlayType(){return Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;}
    private TextView item(String t){TextView v=new TextView(this);v.setText(t);v.setTextColor(Color.WHITE);v.setTextSize(14);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(dp(16),0,dp(16),0);return v;}
    private void showBadge(){try{if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this))return;wm=(WindowManager)getSystemService(WINDOW_SERVICE);TextView t=item("FAC • LICENSE ACTIVE");t.setBackgroundColor(Color.rgb(198,40,40));t.setPadding(dp(12),dp(8),dp(12),dp(8));badge=t;badgeParams=new WindowManager.LayoutParams(-2,-2,overlayType(),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);badgeParams.gravity=Gravity.BOTTOM|Gravity.START;badgeParams.x=dp(10);badgeParams.y=dp(32);wm.addView(badge,badgeParams);t.setOnClickListener(v->toggleMenu());}catch(Exception ignored){}}
    private void toggleMenu(){if(menu!=null){try{wm.removeView(menu);}catch(Exception ignored){}menu=null;return;}try{LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(Color.rgb(45,45,50));TextView lic=item("License");TextView bot=item("Bot Settings");box.addView(lic,new LinearLayout.LayoutParams(dp(190),dp(52)));box.addView(bot,new LinearLayout.LayoutParams(dp(190),dp(52)));lic.setOnClickListener(v->{Intent i=new Intent(this,LicenseActivity.class);i.putExtra("status_only",true);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);toggleMenu();});bot.setOnClickListener(v->{Intent i=new Intent(this,BotSettingsActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);toggleMenu();});WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,overlayType(),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);p.gravity=Gravity.BOTTOM|Gravity.START;p.x=dp(10);p.y=dp(82);menu=box;wm.addView(menu,p);}catch(Exception ignored){}}
    @Override public void onDestroy(){if(wm!=null){try{if(menu!=null)wm.removeView(menu);}catch(Exception ignored){}try{if(badge!=null)wm.removeView(badge);}catch(Exception ignored){}}menu=null;badge=null;super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent i){return null;}
}
