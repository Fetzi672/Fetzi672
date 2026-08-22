package fac.nxlab;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ScanService extends Service {
    public static final String ACTION_START="fac.nxlab.START_SCAN";
    public static final String ACTION_STOP="fac.nxlab.STOP_SCAN";
    private static final String CHANNEL="nxlab_scan";
    private static final String ROOT="/data/local/tmp/fac_nxlab";
    private static final String RUNNER=ROOT+"/nxlab-runner";
    private static final String TPL=ROOT+"/templates";
    private static final String STOP=ROOT+"/STOP";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble,bottom,center;
    private LinearLayout menu;
    private MatchOverlayView match;
    private final ArrayDeque<String> feed=new ArrayDeque<>();
    private volatile java.lang.Process scanProcess;
    private volatile boolean running;

    @Override public void onCreate(){super.onCreate();createChannel();startForeground(2202,notification("Scanner idle"));}
    @Override public IBinder onBind(Intent i){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int id){
        String a=intent==null?ACTION_START:intent.getAction();
        if(ACTION_STOP.equals(a)){requestStop("Stop requested");return START_NOT_STICKY;}
        double threshold=intent==null?0.88:intent.getDoubleExtra("threshold",0.88);
        if(!running){running=true;worker.execute(()->prepareAndRun(threshold));}
        return START_STICKY;
    }

    private void prepareAndRun(double threshold){
        try{
            execRoot("appops set fac.nxlab android:system_alert_window allow 2>/dev/null || appops set fac.nxlab SYSTEM_ALERT_WINDOW allow 2>/dev/null || true",5000);
            main.post(this::showOverlays);
            status("Staging runner...");
            execRoot("rm -rf "+q(TPL)+"; mkdir -p "+q(TPL)+"; rm -f "+q(STOP),5000);
            stageAsset("runner/x86_64/nxlab-runner",RUNNER,true);
            stageAsset("templates/index.tsv",TPL+"/index.tsv",false);
            String index=readAssetText("templates/index.tsv");
            int count=0;
            for(String line:index.split("\\r?\\n")){
                line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;String[] p=line.split("\\t");if(p.length<3)continue;stageAsset("templates/"+p[2],TPL+"/"+p[2],false);count++;
            }
            center("READY • "+count+" TEMPLATES",1200);
            status("Starting scan • threshold "+String.format(java.util.Locale.US,"%.2f",threshold));
            runScanner(threshold);
        }catch(Exception e){error("Scanner failed: "+e.getMessage());}
        finally{running=false;scanProcess=null;main.postDelayed(()->{removeOverlays();stopSelf();},1600);}
    }

    private void runScanner(double threshold)throws Exception{
        String cmd="cd "+q(ROOT)+" && "+q(RUNNER)+" scan "+q(TPL)+" "+String.format(java.util.Locale.US,"%.3f",threshold)+" 1200 2>&1";
        java.lang.Process p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();scanProcess=p;
        BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8));
        String line;while((line=br.readLine())!=null){if(line.startsWith("NXEVT|"))handleEvent(line);else status(line);}
        int code=p.waitFor();status("Runner exit="+code);
    }

    private void handleEvent(String line){
        String[] p=line.split("\\|",-1);if(p.length<3)return;String kind=p[1];
        if("STATUS".equals(kind)||"LOG".equals(kind)){status(p[2]);}
        else if("PROGRESS".equals(kind)){status(p[2]);center(p[2],450);}
        else if("CENTER".equals(kind)){center(p[2],1100);}
        else if("ERROR".equals(kind)){error(p[2]);}
        else if("MATCH".equals(kind)&&p.length>=9){try{String label=p[3];float x=Float.parseFloat(p[4]),y=Float.parseFloat(p[5]),w=Float.parseFloat(p[6]),h=Float.parseFloat(p[7]);String score=p[8];main.post(()->{if(match!=null){match.add(x,y,w,h,label,score);main.postDelayed(()->{if(match!=null)match.clear();},2600);}center("MATCH FOUND • "+label,1200);});status("MATCH "+label+" @ "+(int)x+","+(int)y+" score="+score);}catch(Exception ignored){}}
    }

    private void showOverlays(){
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){error("Overlay permission missing");return;}
        if(wm!=null)return;wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        match=new MatchOverlayView(this);WindowManager.LayoutParams mlp=new WindowManager.LayoutParams(-1,-1,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);mlp.gravity=Gravity.TOP|Gravity.START;wm.addView(match,mlp);
        bottom=new TextView(this);bottom.setTextColor(Color.WHITE);bottom.setTextSize(12);bottom.setPadding(dp(10),dp(8),dp(10),dp(8));bottom.setBackgroundColor(0xCC11151B);bottom.setTypeface(android.graphics.Typeface.MONOSPACE);WindowManager.LayoutParams blp=new WindowManager.LayoutParams(dp(310),-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);blp.gravity=Gravity.BOTTOM|Gravity.RIGHT;blp.x=dp(14);blp.y=dp(30);wm.addView(bottom,blp);
        center=new TextView(this);center.setTextColor(Color.WHITE);center.setTextSize(20);center.setGravity(Gravity.CENTER);center.setPadding(dp(18),dp(12),dp(18),dp(12));center.setBackgroundColor(0xD90F1319);center.setVisibility(View.GONE);WindowManager.LayoutParams clp=new WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);clp.gravity=Gravity.CENTER;wm.addView(center,clp);
        bubble=new TextView(this);bubble.setText("NX");bubble.setTextColor(Color.WHITE);bubble.setTextSize(16);bubble.setGravity(Gravity.CENTER);bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);bg.setColor(0xEEB01824);bg.setStroke(dp(2),Color.WHITE);bubble.setBackground(bg);WindowManager.LayoutParams bp=new WindowManager.LayoutParams(dp(58),dp(58),type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);bp.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL;bp.x=dp(8);wm.addView(bubble,bp);installBubbleTouch(bp);
        menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);menu.setPadding(dp(10),dp(10),dp(10),dp(10));menu.setBackgroundColor(0xEE10141A);TextView mt=new TextView(this);mt.setText("FAC NX Lab v0.2");mt.setTextColor(Color.WHITE);mt.setTextSize(14);mt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);menu.addView(mt,new LinearLayout.LayoutParams(dp(190),dp(34)));Button x=new Button(this);x.setText("✕  STOP SCRIPT");x.setTextColor(Color.WHITE);x.setBackgroundColor(0xFFB01824);x.setOnClickListener(v->requestStop("Stopped from floating control"));menu.addView(x,new LinearLayout.LayoutParams(dp(190),dp(48)));WindowManager.LayoutParams menulp=new WindowManager.LayoutParams(dp(210),dp(104),type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);menulp.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL;menulp.x=dp(76);menu.setVisibility(View.GONE);wm.addView(menu,menulp);
        status("Overlay online");
    }

    private void installBubbleTouch(WindowManager.LayoutParams lp){
        bubble.setOnTouchListener(new View.OnTouchListener(){float downY;int startY;boolean moved;@Override public boolean onTouch(View v,android.view.MotionEvent e){switch(e.getAction()){case android.view.MotionEvent.ACTION_DOWN:downY=e.getRawY();startY=lp.y;moved=false;return true;case android.view.MotionEvent.ACTION_MOVE:float dy=e.getRawY()-downY;if(Math.abs(dy)>8)moved=true;lp.y=startY-(int)dy;try{wm.updateViewLayout(bubble,lp);}catch(Exception ignored){}return true;case android.view.MotionEvent.ACTION_UP:if(!moved&&menu!=null)menu.setVisibility(menu.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);return true;}return false;}});
    }

    private void requestStop(String why){status(why);center("STOPPING SCRIPT...",900);worker.execute(()->{try{execRoot("touch "+q(STOP)+"; sleep 1; pkill -f 'nxlab-runner scan' 2>/dev/null || true",4000);}catch(Exception ignored){}java.lang.Process p=scanProcess;if(p!=null)p.destroy();});}
    private void status(String s){main.post(()->{feed.addLast(s);while(feed.size()>6)feed.removeFirst();StringBuilder b=new StringBuilder();for(String x:feed){if(b.length()>0)b.append('\n');b.append(x);}if(bottom!=null)bottom.setText(b.toString());updateNotification(s);});}
    private void center(String s,long ms){main.post(()->{if(center==null)return;center.setText(s);center.setVisibility(View.VISIBLE);center.removeCallbacks(hideCenter);center.postDelayed(hideCenter,ms);});}
    private final Runnable hideCenter=()->{if(center!=null)center.setVisibility(View.GONE);};
    private void error(String s){status("ERROR: "+s);center("ERROR • "+s,1800);}
    private void stageAsset(String asset,String dest,boolean executable)throws Exception{try(InputStream in=getAssets().open(asset)){String cmd="mkdir -p "+q(new File(dest).getParent())+" && cat > "+q(dest)+" && chmod "+(executable?"0755":"0644")+" "+q(dest);pipeToRoot(cmd,in,25000);}}
    private String readAssetText(String asset)throws Exception{try(InputStream in=getAssets().open(asset);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toString("UTF-8");}}
    private String execRoot(String cmd,long timeout)throws Exception{java.lang.Process p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();ByteArrayOutputStream o=new ByteArrayOutputStream();Thread r=drain(p.getInputStream(),o);waitFor(p,timeout);r.join(500);return o.toString("UTF-8");}
    private void pipeToRoot(String cmd,InputStream src,long timeout)throws Exception{java.lang.Process p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();Thread r=drain(p.getInputStream(),new ByteArrayOutputStream());Thread w=new Thread(()->{try(OutputStream out=p.getOutputStream()){byte[] b=new byte[65536];int n;while((n=src.read(b))>0)out.write(b,0,n);out.flush();}catch(Exception ignored){}});w.start();waitFor(p,timeout);w.join(1000);r.join(500);if(p.exitValue()!=0)throw new IOException("root stage exit="+p.exitValue());}
    private Thread drain(InputStream in,OutputStream out){Thread t=new Thread(()->{try{byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}catch(Exception ignored){}});t.start();return t;}
    private void waitFor(java.lang.Process p,long timeout)throws Exception{long d=SystemClock.uptimeMillis()+timeout;for(;;){try{p.exitValue();return;}catch(IllegalThreadStateException e){if(SystemClock.uptimeMillis()>d){p.destroy();throw new IOException("timeout");}Thread.sleep(40);}}}
    private String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    private Notification notification(String text){Intent i=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,1,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("FAC NX Lab v0.2").setContentText(text).setContentIntent(pi).setOngoing(true).build();}
    private void updateNotification(String s){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.notify(2202,notification(s.length()>80?s.substring(0,80):s));}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel(CHANNEL,"NX Lab Scanner",NotificationManager.IMPORTANCE_LOW));}}
    private void removeOverlays(){main.post(()->{if(wm==null)return;for(View v:new View[]{menu,bubble,bottom,center,match}){if(v!=null)try{wm.removeView(v);}catch(Exception ignored){}}wm=null;});}
    @Override public void onDestroy(){requestStop("Service closing");removeOverlays();worker.shutdownNow();super.onDestroy();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
