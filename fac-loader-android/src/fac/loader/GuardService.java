package fac.loader;

import android.app.*;
import android.content.*;
import android.os.*;

public class GuardService extends Service {
    public static final String CHANNEL_ID="fac_loader_guard";
    private static final int NOTIFY_ID=12012;
    private static final long LOCAL_CHECK_MS=30000L;
    private static final long SERVER_RECHECK_MS=5L*60L*1000L;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean networkBusy;
    private volatile boolean locking;
    private long lastServerCheckElapsed;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(NOTIFY_ID,notification("FAC session active"));
        lastServerCheckElapsed=SystemClock.elapsedRealtime();
        handler.post(guardTick);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!LicenseStore.isSessionActive(this) || !LicenseStore.isLocallyValid(this) || !RootOps.hasRoot()){
            enforceLock("Session refused");
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private final Runnable guardTick=new Runnable(){
        @Override public void run(){
            if(locking)return;
            if(!LicenseStore.isSessionActive(GuardService.this) || !LicenseStore.isLocallyValid(GuardService.this)){
                enforceLock("License expired or invalid");
                return;
            }
            if(!RootOps.hasRoot()){
                enforceLock("Root access lost");
                return;
            }
            long now=SystemClock.elapsedRealtime();
            if(now-lastServerCheckElapsed>=SERVER_RECHECK_MS) requestServerRecheck();
            if(!locking)handler.postDelayed(this,LOCAL_CHECK_MS);
        }
    };

    private void requestServerRecheck(){
        if(networkBusy||locking)return;
        networkBusy=true;
        lastServerCheckElapsed=SystemClock.elapsedRealtime();
        new Thread(new Runnable(){
            @Override public void run(){
                try{
                    String key=LicenseStore.loadKey(GuardService.this);
                    LicenseApi.Result r=LicenseApi.verify(GuardService.this,key);
                    if(!LicenseStore.saveVerification(GuardService.this,r))throw new IllegalStateException("could not persist verification");
                    updateNotification("FAC session active • "+r.devicesBound+"/"+r.devicesLimit+" devices");
                }catch(Exception e){
                    enforceLock("Server verification failed");
                }finally{
                    networkBusy=false;
                }
            }
        },"FAC-License-Recheck").start();
    }

    private void enforceLock(String reason){
        if(locking)return;
        locking=true;
        LicenseStore.invalidate(this);
        RootOps.forceStopTarget();
        updateNotification("FAC locked • "+reason);
        handler.postDelayed(new Runnable(){@Override public void run(){stopForeground(true);stopSelf();}},1200L);
    }

    public static void stopSession(Context c){
        LicenseStore.setSessionActive(c,false);
        RootOps.forceStopTarget();
        try{c.stopService(new Intent(c,GuardService.class));}catch(Exception ignored){}
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        LicenseStore.setSessionActive(this,false);
        RootOps.forceStopTarget();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        if(LicenseStore.isSessionActive(this)){
            LicenseStore.setSessionActive(this,false);
            RootOps.forceStopTarget();
        }
        super.onDestroy();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null){
                NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"FAC License Guard",NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Protects the active FAC runtime session");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification notification(String text){
        Intent open=new Intent(this,MainActivity.class);
        int pf=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=23)pf|=PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi=PendingIntent.getActivity(this,12012,open,pf);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);
        b.setContentTitle("FAC Loader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pi);
        return b.build();
    }

    private void updateNotification(final String text){
        handler.post(new Runnable(){@Override public void run(){
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.notify(NOTIFY_ID,notification(text));
        }});
    }

    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
