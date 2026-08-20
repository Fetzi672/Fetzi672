package fac.guard;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private EditText keyInput;
    private TextView rootStatus,guardStatus,licenseStatus,eventStatus;
    private Button verifyStart,startOriginal,lockSession,toggleGuard;
    private volatile boolean busy;
    private volatile boolean intercepted;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        intercepted=getIntent()!=null&&getIntent().getBooleanExtra("intercepted",false);
        buildUi();
        String saved=LicenseStore.loadKey(this);
        if(saved.length()>0)keyInput.setText(saved);
        refreshState();
        ensureGuardRunning();
        if(intercepted)handleIntercept(saved);
    }

    @Override protected void onNewIntent(Intent i){
        super.onNewIntent(i);setIntent(i);
        intercepted=i!=null&&i.getBooleanExtra("intercepted",false);
        String saved=LicenseStore.loadKey(this);
        if(saved.length()>0)keyInput.setText(saved);
        refreshState();
        if(intercepted)handleIntercept(saved);
    }

    @Override protected void onResume(){super.onResume();refreshState();}

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(sp);return v;}

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(17,17,21));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(28),dp(24),dp(28));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        TextView title=text("FAC Guard V14",28);title.setGravity(Gravity.CENTER);root.addView(title);
        TextView sub=text("Root guard • original APK stays untouched",13);sub.setGravity(Gravity.CENTER);sub.setTextColor(Color.LTGRAY);sub.setPadding(0,dp(4),0,dp(22));root.addView(sub);

        rootStatus=text("Root: checking...",14);root.addView(rootStatus);
        guardStatus=text("Guard: checking...",14);guardStatus.setPadding(0,dp(5),0,0);root.addView(guardStatus);
        licenseStatus=text("License: not verified",14);licenseStatus.setPadding(0,dp(5),0,dp(18));root.addView(licenseStatus);

        TextView label=text("FAC license key",13);label.setTextColor(Color.LTGRAY);root.addView(label);
        keyInput=new EditText(this);keyInput.setSingleLine(true);keyInput.setTextColor(Color.WHITE);keyInput.setHintTextColor(Color.GRAY);keyInput.setHint("FACWEEK-XXXX-XXXX-XXXX");keyInput.setTypeface(android.graphics.Typeface.MONOSPACE);keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);root.addView(keyInput,new LinearLayout.LayoutParams(-1,dp(56)));

        verifyStart=new Button(this);verifyStart.setText("VERIFY & START ORIGINAL");verifyStart.setOnClickListener(v->verifyAndLaunch(keyInput.getText().toString().trim(),false));root.addView(verifyStart,new LinearLayout.LayoutParams(-1,dp(56)));
        startOriginal=new Button(this);startOriginal.setText("START ORIGINAL");startOriginal.setOnClickListener(v->startApprovedOriginal());root.addView(startOriginal,new LinearLayout.LayoutParams(-1,dp(50)));
        lockSession=new Button(this);lockSession.setText("LOCK SESSION NOW");lockSession.setOnClickListener(v->lockNow());root.addView(lockSession,new LinearLayout.LayoutParams(-1,dp(50)));
        toggleGuard=new Button(this);toggleGuard.setOnClickListener(v->toggleGuard());root.addView(toggleGuard,new LinearLayout.LayoutParams(-1,dp(50)));

        eventStatus=text("",12);eventStatus.setTextColor(Color.GRAY);eventStatus.setPadding(0,dp(16),0,dp(8));root.addView(eventStatus);
        TextView note=text("After first setup you normally launch the original Aiwan/CoC icon. FAC Guard watches com.cocfz.com.freescript through root. A direct start without an active verified FAC session is stopped, FAC verification is shown, and after success the untouched original is relaunched. Active sessions are checked locally every 30 seconds and online every 5 minutes.",12);note.setTextColor(Color.GRAY);root.addView(note);
        setContentView(scroll);
    }

    private void handleIntercept(String saved){
        eventStatus.setText("Original launch intercepted • FAC authorization required");
        if(saved!=null&&saved.length()>=8){
            new Handler(Looper.getMainLooper()).postDelayed(()->verifyAndLaunch(saved,true),180L);
        }else{
            statusLicense("License: enter key to continue",Color.rgb(255,190,70));
        }
    }

    private void ensureGuardRunning(){
        if(!LicenseStore.isArmed(this))return;
        try{GuardService.arm(this);}catch(Exception ignored){}
    }

    private void refreshState(){
        new Thread(()->{
            final boolean root=RootOps.hasRoot();
            final boolean armed=LicenseStore.isArmed(this);
            final boolean local=LicenseStore.isLocallyValid(this);
            final boolean session=LicenseStore.isSessionActive(this);
            final String last=LicenseStore.lastEvent(this);
            runOnUiThread(()->{
                rootStatus.setText(root?"Root: READY":"Root: REQUIRED / NOT AVAILABLE");
                rootStatus.setTextColor(root?Color.rgb(80,220,120):Color.rgb(255,90,90));
                guardStatus.setText(armed?"Guard: ARMED":"Guard: DISARMED");
                guardStatus.setTextColor(armed?Color.rgb(80,220,120):Color.rgb(255,190,70));
                if(session&&local){licenseStatus.setText("License: ACTIVE • session authorized");licenseStatus.setTextColor(Color.rgb(80,220,120));}
                else if(local){licenseStatus.setText("License: verified • session locked");licenseStatus.setTextColor(Color.rgb(255,190,70));}
                else{licenseStatus.setText("License: verification required");licenseStatus.setTextColor(Color.LTGRAY);}
                startOriginal.setEnabled(session&&local&&root);
                lockSession.setEnabled(session);
                toggleGuard.setText(armed?"DISARM FAC GUARD":"ARM FAC GUARD");
                if(last!=null&&last.length()>0)eventStatus.setText("Last event: "+last);
            });
        },"FAC-State").start();
    }

    private void setBusy(boolean value){
        busy=value;
        runOnUiThread(()->{verifyStart.setEnabled(!value);keyInput.setEnabled(!value);});
    }

    private void verifyAndLaunch(final String key,final boolean automatic){
        if(busy||key==null||key.length()<8)return;
        setBusy(true);statusLicense("License: verifying...",Color.LTGRAY);
        new Thread(()->{
            try{
                if(!RootOps.hasRoot())throw new SecurityException("Root access is required.");
                LicenseStore.setArmed(this,true);
                GuardService.arm(this);
                LicenseApi.Result r=LicenseApi.verify(this,key);
                LicenseStore.saveKey(this,key);
                if(!LicenseStore.saveVerification(this,r))throw new IllegalStateException("Could not persist verification.");
                LicenseStore.setSessionActive(this,true);
                LicenseStore.setLastEvent(this,"FAC license verified; original runtime authorized.");
                statusLicense("License: ACTIVE until "+formatDate(r.expiryEpochMs)+" • "+r.devicesBound+"/"+r.devicesLimit+" devices",Color.rgb(80,220,120));
                launchOriginalClean();
            }catch(SecurityException e){
                LicenseStore.invalidate(this);
                statusLicense("License refused: "+safe(e.getMessage()),Color.rgb(255,90,90));
            }catch(Exception e){
                LicenseStore.invalidate(this);
                statusLicense("License verification failed or server unreachable.",Color.rgb(255,90,90));
            }finally{setBusy(false);runOnUiThread(this::refreshState);}
        },automatic?"FAC-Auto-Verify":"FAC-Verify").start();
    }

    private void startApprovedOriginal(){
        if(!LicenseStore.isSessionActive(this)||!LicenseStore.isLocallyValid(this)){
            statusLicense("License: fresh verification required",Color.rgb(255,90,90));return;
        }
        new Thread(()->{
            try{
                if(!RootOps.hasRoot())throw new SecurityException("Root access is required.");
                launchOriginalClean();
            }catch(Exception e){statusLicense(safe(e.getMessage()),Color.rgb(255,90,90));}
        },"FAC-Launch-Original").start();
    }

    private void launchOriginalClean()throws Exception{
        Intent launch=getPackageManager().getLaunchIntentForPackage(RootOps.TARGET_PACKAGE);
        if(launch==null)throw new IllegalStateException("Original app com.cocfz.com.freescript is not installed.");
        RootOps.forceStopTarget();
        LicenseStore.setSessionActive(this,true);
        ensureGuardRunning();
        SystemClock.sleep(180L);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        runOnUiThread(()->{
            try{
                startActivity(launch);
                LicenseStore.setLastEvent(this,"Original runtime launched under FAC Guard.");
                finish();
            }catch(Exception e){
                LicenseStore.clearSession(this);
                statusLicense("Could not launch original app.",Color.rgb(255,90,90));
            }
        });
    }

    private void lockNow(){
        LicenseStore.clearSession(this);
        LicenseStore.setLastEvent(this,"Session manually locked.");
        new Thread(()->RootOps.forceStopTarget(),"FAC-Lock").start();
        statusLicense("License: session locked",Color.rgb(255,190,70));
        refreshState();
    }

    private void toggleGuard(){
        boolean armed=LicenseStore.isArmed(this);
        if(armed){
            LicenseStore.setArmed(this,false);LicenseStore.clearSession(this);
            new Thread(()->{RootOps.forceStopTarget();RootOps.stopKeepalive();},"FAC-Disarm").start();
            try{GuardService.disarm(this);}catch(Exception ignored){}
            LicenseStore.setLastEvent(this,"FAC Guard manually disarmed.");
        }else{
            LicenseStore.setArmed(this,true);
            try{GuardService.arm(this);}catch(Exception ignored){}
            LicenseStore.setLastEvent(this,"FAC Guard armed.");
        }
        refreshState();
    }

    private void statusLicense(final String s,final int color){runOnUiThread(()->{licenseStatus.setText(s);licenseStatus.setTextColor(color);});}
    private static String safe(String s){return s==null?"Operation failed.":s;}
    private String formatDate(long epoch){try{return new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(new Date(epoch));}catch(Exception e){return "—";}}
}
