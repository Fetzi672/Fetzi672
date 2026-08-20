package fac.guard;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuardService extends Service {
    public static final String CHANNEL_ID="fac_guard_v14";
    public static final String ACTION_ARM="fac.guard.ARM";
    public static final String ACTION_DISARM="fac.guard.DISARM";
    private static final int NOTIFY_ID=14014;
    private static final long LOCAL_CHECK_MS=30000L;
    private static final long SERVER_RECHECK_MS=5L*60L*1000L;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicBoolean networkBusy=new AtomicBoolean(false);
    private final AtomicBoolean interceptBusy=new AtomicBoolean(false);
    private volatile boolean locking;
    private volatile boolean lastTargetRunning;
    private volatile Thread monitorThread;
    private volatile Process monitorProcess;
    private long lastServerCheckElapsed;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(NOTIFY_ID,notification("FAC Guard armed • waiting for original app"));
        long anchor=LicenseStore.verificationElapsed(this);
        long now=SystemClock.elapsedRealtime();
        lastServerCheckElapsed=(anchor>0&&anchor<=now)?anchor:now;
        if(LicenseStore.isArmed(this)){
            startMonitorIfNeeded();
            new Thread(()->{if(RootOps.hasRoot())RootOps.installKeepalive(this);},"FAC-Root-Keepalive").start();
        }
        handler.post(guardTick);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_DISARM.equals(action)){
            LicenseStore.setArmed(this,false);
            LicenseStore.clearSession(this);
            RootOps.stopKeepalive();
            RootOps.forceStopTarget();
            stopForeground(true);stopSelf();
            return START_NOT_STICKY;
        }
        if(ACTION_ARM.equals(action))LicenseStore.setArmed(this,true);
        if(!LicenseStore.isArmed(this)){
            stopForeground(true);stopSelf();return START_NOT_STICKY;
        }
        startMonitorIfNeeded();
        return START_STICKY;
    }

    private void startMonitorIfNeeded(){
        Thread t=monitorThread;
        if(t!=null&&t.isAlive())return;
        monitorThread=new Thread(new Runnable(){
            @Override public void run(){
                while(!Thread.currentThread().isInterrupted()&&LicenseStore.isArmed(GuardService.this)){
                    if(!RootOps.hasRoot()){
                        updateNotification("FAC Guard armed • ROOT REQUIRED");
                        SystemClock.sleep(2000L);
                        continue;
                    }
                    try{
                        Process p=RootOps.startTargetMonitor();
                        monitorProcess=p;
                        BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while((line=br.readLine())!=null&&!Thread.currentThread().isInterrupted()&&LicenseStore.isArmed(GuardService.this)){
                            boolean running="RUN".equals(line.trim());
                            if(running&&!lastTargetRunning)onTargetStarted();
                            if(!running)lastTargetRunning=false;
                        }
                        try{br.close();}catch(Exception ignored){}
                        try{p.destroy();}catch(Exception ignored){}
                    }catch(Exception e){
                        updateNotification("FAC Guard monitor restarting");
                        SystemClock.sleep(1500L);
                    }finally{monitorProcess=null;}
                }
            }
        },"FAC-Target-Monitor");
        monitorThread.start();
    }

    private void onTargetStarted(){
        if(interceptBusy.get())return;
        if(LicenseStore.isSessionActive(this)&&LicenseStore.isLocallyValid(this)){
            lastTargetRunning=true;
            updateNotification("FAC license active • original protected");
            return;
        }
        if(!interceptBusy.compareAndSet(false,true))return;
        try{
            LicenseStore.clearSession(this);
            LicenseStore.setLastEvent(this,"Direct original launch blocked until FAC verification.");
            RootOps.forceStopTarget();
            lastTargetRunning=false;
            SystemClock.sleep(180L);
            RootOps.openGuardUi(this,"direct_launch");
            updateNotification("FAC blocked direct launch • verification required");
        }finally{interceptBusy.set(false);}
    }

    private final Runnable guardTick=new Runnable(){
        @Override public void run(){
            if(!LicenseStore.isArmed(GuardService.this))return;
            if(LicenseStore.isSessionActive(GuardService.this)){
                if(!LicenseStore.isLocallyValid(GuardService.this)){
                    enforceLock("License expired or local state invalid",lastTargetRunning);
                }else if(!RootOps.hasRoot()){
                    enforceLock("Root access lost",lastTargetRunning);
                }else{
                    long now=SystemClock.elapsedRealtime();
                    if(now-lastServerCheckElapsed>=SERVER_RECHECK_MS)requestServerRecheck();
                }
            }
            if(LicenseStore.isArmed(GuardService.this))handler.postDelayed(this,LOCAL_CHECK_MS);
        }
    };

    private void requestServerRecheck(){
        if(locking||!networkBusy.compareAndSet(false,true))return;
        lastServerCheckElapsed=SystemClock.elapsedRealtime();
        new Thread(new Runnable(){
            @Override public void run(){
                try{
                    String key=LicenseStore.loadKey(GuardService.this);
                    LicenseApi.Result r=LicenseApi.verify(GuardService.this,key);
                    if(!LicenseStore.saveVerification(GuardService.this,r))throw new IOException("could not persist verification");
                    LicenseStore.setSessionActive(GuardService.this,true);
                    lastServerCheckElapsed=SystemClock.elapsedRealtime();
                    updateNotification("FAC license active • "+r.devicesBound+"/"+r.devicesLimit+" devices");
                }catch(Exception e){
                    enforceLock("Server verification failed",lastTargetRunning);
                }finally{networkBusy.set(false);}
            }
        },"FAC-Server-Recheck").start();
    }

    private void enforceLock(String reason,boolean showUi){
        if(locking)return;
        locking=true;
        LicenseStore.invalidate(this);
        LicenseStore.setLastEvent(this,reason);
        RootOps.forceStopTarget();
        lastTargetRunning=false;
        updateNotification("FAC LOCKED • "+reason);
        if(showUi)RootOps.openGuardUi(this,"guard_lock");
        handler.postDelayed(()->locking=false,1000L);
    }

    public static void arm(Context c){
        LicenseStore.setArmed(c,true);
        Intent i=new Intent(c,GuardService.class);i.setAction(ACTION_ARM);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    }

    public static void disarm(Context c){
        Intent i=new Intent(c,GuardService.class);i.setAction(ACTION_DISARM);
        try{c.startService(i);}catch(Exception ignored){
            LicenseStore.setArmed(c,false);LicenseStore.clearSession(c);RootOps.stopKeepalive();RootOps.forceStopTarget();
        }
    }

    @Override public void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        Process p=monitorProcess;if(p!=null)try{p.destroy();}catch(Exception ignored){}
        Thread t=monitorThread;if(t!=null)try{t.interrupt();}catch(Exception ignored){}
        if(LicenseStore.isArmed(this)&&LicenseStore.isSessionActive(this))RootOps.forceStopTarget();
        super.onDestroy();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null){
                NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"FAC Guard V14",NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Root guard for the original FAC runtime");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification notification(String text){
        Intent open=new Intent(this,MainActivity.class);
        int pf=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=23)pf|=PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi=PendingIntent.getActivity(this,14014,open,pf);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);
        b.setContentTitle("FAC Guard V14")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pi);
        return b.build();
    }

    private void updateNotification(final String text){
        handler.post(()->{
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.notify(NOTIFY_ID,notification(text));
        });
    }

    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
