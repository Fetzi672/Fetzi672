package fac.loader;

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
    private TextView status;
    private TextView rootStatus;
    private Button verifyStart;
    private Button startOriginal;
    private Button stopSession;
    private volatile boolean busy;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        refreshRootStatus();
        String saved=LicenseStore.loadKey(this);
        if(saved.length()>0){
            keyInput.setText(saved);
            verifyAndStart(saved,true);
        }
    }

    @Override protected void onResume(){
        super.onResume();
        refreshRootStatus();
        stopSession.setEnabled(LicenseStore.isSessionActive(this));
        startOriginal.setEnabled(LicenseStore.isLocallyValid(this));
    }

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(sp);return v;}

    private void buildUi(){
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(Color.rgb(18,18,22));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24),dp(30),dp(24),dp(30));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        TextView title=text("FAC Loader",28); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub=text("Original Aiwan/CoC runtime stays untouched",13); sub.setTextColor(Color.LTGRAY); sub.setGravity(Gravity.CENTER); sub.setPadding(0,dp(4),0,dp(24)); root.addView(sub);

        rootStatus=text("Root: checking...",14); rootStatus.setPadding(0,0,0,dp(14)); root.addView(rootStatus);
        TextView label=text("FAC license key",14); label.setTextColor(Color.LTGRAY); root.addView(label);
        keyInput=new EditText(this); keyInput.setSingleLine(true); keyInput.setTextColor(Color.WHITE); keyInput.setHintTextColor(Color.GRAY); keyInput.setHint("FACWEEK-XXXX-XXXX-XXXX"); keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); keyInput.setTypeface(android.graphics.Typeface.MONOSPACE); root.addView(keyInput,new LinearLayout.LayoutParams(-1,dp(56)));

        verifyStart=new Button(this); verifyStart.setText("VERIFY & START ORIGINAL"); verifyStart.setOnClickListener(v->verifyAndStart(keyInput.getText().toString().trim(),false)); root.addView(verifyStart,new LinearLayout.LayoutParams(-1,dp(56)));
        startOriginal=new Button(this); startOriginal.setText("START ORIGINAL"); startOriginal.setEnabled(false); startOriginal.setOnClickListener(v->startApprovedSession()); root.addView(startOriginal,new LinearLayout.LayoutParams(-1,dp(52)));
        stopSession=new Button(this); stopSession.setText("STOP FAC SESSION"); stopSession.setEnabled(false); stopSession.setOnClickListener(v->{GuardService.stopSession(this);stopSession.setEnabled(false);status("Session stopped. Original app was closed.",Color.LTGRAY);}); root.addView(stopSession,new LinearLayout.LayoutParams(-1,dp(52)));

        status=text("Not verified",15); status.setPadding(0,dp(18),0,dp(12)); status.setTextColor(Color.LTGRAY); root.addView(status);
        TextView note=text("V12: the original com.cocfz.com.freescript APK is never modified. The loader verifies FAC, starts a foreground watchdog, then launches the original package. Local expiry is checked every 30s and the server is rechecked every 5 minutes. Any failed guard closes the original app through root.",12); note.setTextColor(Color.GRAY); root.addView(note);
        setContentView(scroll);
    }

    private void refreshRootStatus(){
        new Thread(()->{
            final boolean ok=RootOps.hasRoot();
            runOnUiThread(()->{rootStatus.setText(ok?"Root: READY":"Root: REQUIRED / NOT AVAILABLE");rootStatus.setTextColor(ok?Color.rgb(80,220,120):Color.rgb(255,90,90));});
        },"FAC-Root-Check").start();
    }

    private void setBusy(final boolean b){
        busy=b;
        runOnUiThread(()->{verifyStart.setEnabled(!b);keyInput.setEnabled(!b);});
    }

    private void verifyAndStart(final String key,final boolean automatic){
        if(busy || key==null || key.length()<8)return;
        setBusy(true); status("Verifying license...",Color.LTGRAY);
        new Thread(()->{
            try{
                if(!RootOps.hasRoot())throw new SecurityException("root required");
                LicenseApi.Result r=LicenseApi.verify(this,key);
                LicenseStore.saveKey(this,key);
                if(!LicenseStore.saveVerification(this,r))throw new IllegalStateException("could not persist license state");
                runOnUiThread(()->{
                    status.setTextColor(Color.rgb(80,220,120));
                    status.setText("License active until "+formatDate(r.expiryEpochMs)+" • "+r.devicesBound+"/"+r.devicesLimit+" devices");
                    startOriginal.setEnabled(true);
                });
                // Opening the loader with a saved valid key should be one tap:
                // online verify first, then immediately start the untouched original.
                startApprovedSession();
            }catch(SecurityException e){
                LicenseStore.invalidate(this);
                status("Verification refused: "+safe(e.getMessage()),Color.rgb(255,90,90));
            }catch(Exception e){
                LicenseStore.invalidate(this);
                status("Verification failed or server unreachable.",Color.rgb(255,90,90));
            }finally{setBusy(false);}
        },"FAC-Initial-Verify").start();
    }

    private void startApprovedSession(){
        runOnUiThread(()->{
            if(!LicenseStore.isLocallyValid(this)){status("A fresh verification is required.",Color.rgb(255,90,90));return;}
            new Thread(()->{
                try{
                    if(!RootOps.hasRoot())throw new SecurityException("root required");
                    Intent launch=getPackageManager().getLaunchIntentForPackage(RootOps.TARGET_PACKAGE);
                    if(launch==null)throw new IllegalStateException("Original app is not installed.");

                    // Always start from a clean original process. The APK itself is untouched.
                    RootOps.forceStopTarget();
                    LicenseStore.setSessionActive(this,true);
                    Intent guard=new Intent(this,GuardService.class);
                    if(Build.VERSION.SDK_INT>=26)startForegroundService(guard); else startService(guard);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    runOnUiThread(()->{
                        try{
                            startActivity(launch);
                            stopSession.setEnabled(true);
                            status("FAC session active. Original app launched.",Color.rgb(80,220,120));
                        }catch(Exception x){
                            GuardService.stopSession(this);
                            status("Could not launch the original app.",Color.rgb(255,90,90));
                        }
                    });
                }catch(Exception e){
                    LicenseStore.setSessionActive(this,false);
                    status(safe(e.getMessage()),Color.rgb(255,90,90));
                }
            },"FAC-Launch").start();
        });
    }

    private void status(final String s,final int color){runOnUiThread(()->{status.setText(s);status.setTextColor(color);});}
    private static String safe(String s){return s==null?"Operation failed.":s;}
    private String formatDate(long epoch){try{return new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(new Date(epoch));}catch(Exception e){return "—";}}
}
