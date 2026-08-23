package fac.nxlab.noroot.debug;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScanService extends Service {
    public static final String ACTION_START="fac.nxlab.noroot.debug.START_SCAN";
    public static final String ACTION_STOP="fac.nxlab.noroot.debug.STOP_SCAN";
    public static final String EXTRA_RESULT_CODE="result_code", EXTRA_PROJECTION_DATA="projection_data", EXTRA_THRESHOLD="threshold";
    private static final String CHANNEL="nxlab_debug_capture";
    private static final int NOTIFY_ID=2311;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService scanner=Executors.newSingleThreadExecutor();
    private final AtomicBoolean running=new AtomicBoolean(false);
    private final Object frameLock=new Object();
    private final ArrayDeque<String> feed=new ArrayDeque<>();
    private HandlerThread captureThread; private Handler captureHandler;
    private MediaProjection projection; private VirtualDisplay virtualDisplay; private ImageReader reader;
    private Bitmap latestFrame; private int width,height,density; private long lastFrameAt; private double threshold=.88; private volatile boolean stopping;
    private WindowManager wm; private TextView bubble,bottom,center; private LinearLayout menu; private MatchOverlayView matchView;
    private List<Template> templates=Collections.emptyList();

    private static final String[][] SPECS={
        {"settings.png","Settings"},{"white_x.png","White X"},{"chest_1.png","Chest 1"},{"chest_2.png","Chest 2"},{"chest_3.png","Chest 3"},{"chest_4.png","Chest 4"},
        {"giant_gauntlet.png","Giant Gauntlet"},{"frozen_arrow.png","Frozen Arrow"},{"eternal_tome.png","Eternal Tome"},{"rage_vial.png","Rage Vial"},
        {"invisibility_vial.png","Invisibility Vial"},{"archer_puppet.png","Archer Puppet"},{"healing_tome.png","Healing Tome"},{"life_gem.png","Life Gem"}
    };

    @Override public void onCreate(){super.onCreate();createChannel();captureThread=new HandlerThread("nxlab-capture");captureThread.start();captureHandler=new Handler(captureThread.getLooper());}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopScan("Stopped from control",true);return START_NOT_STICKY;}
        if(intent==null||!ACTION_START.equals(intent.getAction())||running.get())return START_NOT_STICKY;
        startForeground(NOTIFY_ID,notification("Waiting for screen capture"));
        threshold=clamp(intent.getDoubleExtra(EXTRA_THRESHOLD,.88),.70,.99);
        int result=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED); Intent data=intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
        if(result!=Activity.RESULT_OK||data==null){stopSelf();return START_NOT_STICKY;}
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}
        try{
            MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE); projection=m.getMediaProjection(result,data); if(projection==null)throw new IllegalStateException("MediaProjection unavailable");
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){if(!stopping)stopScan("Android ended screen capture",false);}},main);
            templates=loadTemplates(); if(templates.isEmpty())throw new IllegalStateException("No embedded templates");
            running.set(true); stopping=false; showOverlays(); setupCapture();
            main.postDelayed(()->{if(matchView!=null)matchView.showSelfTest();},350);
            center("DEBUG OVERLAY SELF-TEST",1700); status("MediaProjection active • "+templates.size()+" templates • required="+fmt(threshold));
            scanner.execute(this::scanLoop);
        }catch(Exception e){error("Start failed: "+e.getMessage());stopScan("Start failed",false);}
        return START_NOT_STICKY;
    }

    private void setupCapture(){
        DisplayMetrics dm=new DisplayMetrics(); realDisplay().getRealMetrics(dm); width=dm.widthPixels;height=dm.heightPixels;density=dm.densityDpi;
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2); reader.setOnImageAvailableListener(this::consumeFrame,captureHandler);
        virtualDisplay=projection.createVirtualDisplay("FAC-NXLab-Debug",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,captureHandler);
        status("Capture surface "+width+"x"+height+" @ "+density+"dpi");
    }
    private Display realDisplay(){WindowManager w=(WindowManager)getSystemService(WINDOW_SERVICE);return w.getDefaultDisplay();}
    private void consumeFrame(ImageReader r){Image im=null;try{im=r.acquireLatestImage();if(im==null)return;long now=SystemClock.uptimeMillis();if(now-lastFrameAt<180)return;lastFrameAt=now;Image.Plane p=im.getPlanes()[0];ByteBuffer b=p.getBuffer();int pix=p.getPixelStride(),row=p.getRowStride(),pad=row-pix*width,paddedW=width+Math.max(0,pad/Math.max(1,pix));Bitmap padded=Bitmap.createBitmap(paddedW,height,Bitmap.Config.ARGB_8888);b.rewind();padded.copyPixelsFromBuffer(b);Bitmap frame=paddedW==width?padded:Bitmap.createBitmap(padded,0,0,width,height);if(frame!=padded)padded.recycle();synchronized(frameLock){Bitmap old=latestFrame;latestFrame=frame;if(old!=null&&old!=frame&&!old.isRecycled())old.recycle();}}catch(Throwable t){status("Frame warning: "+t.getClass().getSimpleName());}finally{if(im!=null)im.close();}}
    private Bitmap copyFrame(){synchronized(frameLock){if(latestFrame==null||latestFrame.isRecycled())return null;return latestFrame.copy(Bitmap.Config.ARGB_8888,false);}}

    private void scanLoop(){int cycle=0;while(running.get()&&!Thread.currentThread().isInterrupted()){Bitmap screen=copyFrame();if(screen==null){status("Waiting for first frame...");sleep(180);continue;}cycle++;int sw=screen.getWidth(),sh=screen.getHeight();int[] sp=new int[sw*sh];screen.getPixels(sp,0,sw,0,0,sw,sh);screen.recycle();status("Cycle "+cycle+" • frame "+sw+"x"+sh);int hits=0;
        for(int i=0;i<templates.size()&&running.get();i++){
            Template t=templates.get(i);String prefix="Scanning "+(i+1)+"/"+templates.size()+" • "+t.label;status(prefix);List<Match> top=findTopMatches(sp,sw,sh,t,3);Match best=top.isEmpty()?null:top.get(0);double bestScore=best==null?0:best.score;
            status(t.label+" • best="+fmt(bestScore)+" / required="+fmt(threshold)+(best==null?"":" @ "+best.x+","+best.y));
            if(best!=null&&best.score>=threshold){hits++;final Match b=best;main.post(()->{if(matchView!=null){matchView.setCandidates(null,t.label);matchView.addMatch(b.x,b.y,t.w,t.h,t.label,fmt(b.score));}});center("MATCH FOUND • "+t.label+" • "+fmt(best.score),1450);}
            else {double minCandidate=Math.max(.50,threshold-.30);ArrayList<float[]> v=new ArrayList<>();for(Match m:top)if(m.score>=minCandidate)v.add(new float[]{m.x,m.y,t.w,t.h,(float)m.score});main.post(()->{if(matchView!=null)matchView.setCandidates(v,t.label);});if(best!=null&&best.score>=minCandidate)center("BEST CANDIDATE • "+t.label+" • "+fmt(best.score),520);}
        }
        if(!running.get())break;if(hits==0)center("NO RED MATCH • cycle "+cycle,800);else status("Cycle "+cycle+" complete • "+hits+" RED match(es)");sleep(500);
    }if(!stopping)stopScan("Scanner loop ended",false);}

    private List<Match> findTopMatches(int[] screen,int sw,int sh,Template t,int wanted){if(t.w>sw||t.h>sh)return Collections.emptyList();final int coarseWanted=14,step=4;PriorityQueue<Coarse> q=new PriorityQueue<>(Comparator.comparingDouble((Coarse a)->a.sim));int maxX=sw-t.w,maxY=sh-t.h;
        for(int y=0;y<=maxY&&running.get();y+=step){for(int x=0;x<=maxX;x+=step){long d=0;for(Anchor a:t.anchors)d+=colorDiff(screen[(y+a.y)*sw+x+a.x],a.color);double sim=1.0-(double)d/(Math.max(1,t.anchors.size())*765.0);if(q.size()<coarseWanted)q.add(new Coarse(x,y,sim));else if(sim>q.peek().sim){q.poll();q.add(new Coarse(x,y,sim));}}}
        ArrayList<Coarse> coarse=new ArrayList<>(q);coarse.sort((a,b)->Double.compare(b.sim,a.sim));ArrayList<Match> all=new ArrayList<>();for(Coarse c:coarse){Match m=refine(screen,sw,sh,t,c.x,c.y);if(m==null)continue;boolean near=false;for(int j=0;j<all.size();j++){Match e=all.get(j);if(Math.abs(e.x-m.x)+Math.abs(e.y-m.y)<Math.max(8,Math.min(t.w,t.h)/3)){near=true;if(m.score>e.score)all.set(j,m);break;}}if(!near)all.add(m);all.sort((a,b)->Double.compare(b.score,a.score));while(all.size()>wanted)all.remove(all.size()-1);}return all;}
    private Match refine(int[] s,int sw,int sh,Template t,int x,int y){Match best=null;for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++){int rx=x+dx,ry=y+dy;if(rx<0||ry<0||rx+t.w>sw||ry+t.h>sh)continue;double sc=score(s,sw,t,rx,ry);if(best==null||sc>best.score)best=new Match(rx,ry,sc);}return best;}
    private double score(int[] s,int sw,Template t,int ox,int oy){long diff=0;int n=0,st=t.w*t.h>3000?2:1;for(int y=0;y<t.h;y+=st){int sr=(oy+y)*sw+ox,tr=y*t.w;for(int x=0;x<t.w;x+=st){int tc=t.pixels[tr+x];if(((tc>>>24)&255)<80)continue;diff+=colorDiff(s[sr+x],tc);n++;}}if(n==0)return 0;return clamp(1.0-(double)diff/(n*765.0),0,1);}

    private List<Template> loadTemplates(){ArrayList<Template> out=new ArrayList<>();for(String[] spec:SPECS){try(InputStream in=getAssets().open("templates/"+spec[0])){Bitmap b=BitmapFactory.decodeStream(in);if(b==null)continue;Bitmap a=b.getConfig()==Bitmap.Config.ARGB_8888?b:b.copy(Bitmap.Config.ARGB_8888,false);if(a!=b)b.recycle();out.add(new Template(spec[0],spec[1],a));}catch(Exception e){status("Template missing: "+spec[0]);}}return out;}
    private static final class Template{final String file,label;final int w,h;final int[] pixels;final List<Anchor> anchors;Template(String f,String l,Bitmap b){file=f;label=l;w=b.getWidth();h=b.getHeight();pixels=new int[w*h];b.getPixels(pixels,0,w,0,0,w,h);anchors=chooseAnchors(pixels,w,h,6);b.recycle();}}
    private static final class Anchor{final int x,y,color;Anchor(int x,int y,int c){this.x=x;this.y=y;color=c;}}
    private static final class Match{final int x,y;final double score;Match(int x,int y,double s){this.x=x;this.y=y;score=s;}}
    private static final class Coarse{final int x,y;final double sim;Coarse(int x,int y,double s){this.x=x;this.y=y;sim=s;}}
    private static List<Anchor> chooseAnchors(int[] p,int w,int h,int wanted){long rs=0,gs=0,bs=0,n=0;int sy=Math.max(1,h/10),sx=Math.max(1,w/10);for(int y=0;y<h;y+=sy)for(int x=0;x<w;x+=sx){int c=p[y*w+x];if(((c>>>24)&255)<80)continue;rs+=(c>>>16)&255;gs+=(c>>>8)&255;bs+=c&255;n++;}if(n==0)n=1;final double mr=(double)rs/n,mg=(double)gs/n,mb=(double)bs/n;ArrayList<Cand> cs=new ArrayList<>();for(int y=1;y<h-1;y+=Math.max(1,h/9))for(int x=1;x<w-1;x+=Math.max(1,w/9)){int c=p[y*w+x];if(((c>>>24)&255)<80)continue;double d=Math.abs(((c>>>16)&255)-mr)+Math.abs(((c>>>8)&255)-mg)+Math.abs((c&255)-mb);cs.add(new Cand(x,y,c,d));}cs.sort((a,b)->Double.compare(b.d,a.d));ArrayList<Anchor> out=new ArrayList<>();for(Cand c:cs){boolean ok=true;for(Anchor a:out)if(Math.abs(a.x-c.x)+Math.abs(a.y-c.y)<Math.max(4,Math.min(w,h)/5)){ok=false;break;}if(ok)out.add(new Anchor(c.x,c.y,c.color));if(out.size()>=wanted)break;}if(out.isEmpty())out.add(new Anchor(w/2,h/2,p[(h/2)*w+w/2]));return out;}
    private static final class Cand{final int x,y,color;final double d;Cand(int x,int y,int c,double d){this.x=x;this.y=y;color=c;this.d=d;}}
    private static int colorDiff(int a,int b){return Math.abs(((a>>>16)&255)-((b>>>16)&255))+Math.abs(((a>>>8)&255)-((b>>>8)&255))+Math.abs((a&255)-(b&255));}

    private void showOverlays(){if(wm!=null)return;wm=(WindowManager)getSystemService(WINDOW_SERVICE);int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        matchView=new MatchOverlayView(this);WindowManager.LayoutParams mlp=new WindowManager.LayoutParams(-1,-1,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);mlp.gravity=Gravity.TOP|Gravity.START;wm.addView(matchView,mlp);
        bottom=new TextView(this);bottom.setTextColor(Color.WHITE);bottom.setTextSize(12);bottom.setTypeface(Typeface.MONOSPACE);bottom.setPadding(dp(10),dp(8),dp(10),dp(8));bottom.setBackgroundColor(0xD811151B);WindowManager.LayoutParams blp=new WindowManager.LayoutParams(dp(390),-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);blp.gravity=Gravity.BOTTOM|Gravity.RIGHT;blp.x=dp(18);blp.y=dp(28);wm.addView(bottom,blp);
        center=new TextView(this);center.setTextColor(Color.WHITE);center.setTextSize(18);center.setTypeface(Typeface.DEFAULT_BOLD);center.setGravity(Gravity.CENTER);center.setPadding(dp(18),dp(12),dp(18),dp(12));center.setBackgroundColor(0xDD0F1319);center.setVisibility(View.GONE);WindowManager.LayoutParams clp=new WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);clp.gravity=Gravity.CENTER;wm.addView(center,clp);
        bubble=new TextView(this);bubble.setText("NX");bubble.setTextColor(Color.WHITE);bubble.setTextSize(16);bubble.setGravity(Gravity.CENTER);bubble.setTypeface(Typeface.DEFAULT_BOLD);GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);bg.setColor(0xEEB01824);bg.setStroke(dp(2),Color.WHITE);bubble.setBackground(bg);WindowManager.LayoutParams bp=new WindowManager.LayoutParams(dp(62),dp(62),type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);bp.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL;bp.x=dp(10);wm.addView(bubble,bp);
        menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);menu.setPadding(dp(10),dp(10),dp(10),dp(10));menu.setBackgroundColor(0xEE10141A);TextView title=new TextView(this);title.setText("FAC NX Debug v0.3.1");title.setTextColor(Color.WHITE);title.setTypeface(Typeface.DEFAULT_BOLD);menu.addView(title,new LinearLayout.LayoutParams(dp(210),dp(34)));Button stop=new Button(this);stop.setText("✕  STOP SCRIPT");stop.setTextColor(Color.WHITE);stop.setBackgroundColor(0xFFB01824);stop.setOnClickListener(v->stopScan("Stopped from floating control",true));menu.addView(stop,new LinearLayout.LayoutParams(dp(210),dp(50)));WindowManager.LayoutParams mp=new WindowManager.LayoutParams(dp(230),dp(108),type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);mp.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL;mp.x=dp(82);menu.setVisibility(View.GONE);wm.addView(menu,mp);bubble.setOnClickListener(v->menu.setVisibility(menu.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));}
    private void status(String s){main.post(()->{feed.addLast(s);while(feed.size()>7)feed.removeFirst();StringBuilder b=new StringBuilder();for(String x:feed){if(b.length()>0)b.append('\n');b.append(x);}if(bottom!=null)bottom.setText(b.toString());updateNotification(s);});}
    private final Runnable hideCenter=()->{if(center!=null)center.setVisibility(View.GONE);}; private void center(String s,long ms){main.post(()->{if(center==null)return;center.setText(s);center.setVisibility(View.VISIBLE);center.removeCallbacks(hideCenter);center.postDelayed(hideCenter,ms);});} private void error(String s){status("ERROR: "+s);center("ERROR • "+s,1800);}

    private synchronized void stopScan(String why,boolean user){if(stopping)return;stopping=true;running.set(false);status(why);center("SCRIPT STOPPED",900);main.postDelayed(()->{closeCapture();removeOverlays();stopForeground(true);stopSelf();},800);}
    private void closeCapture(){synchronized(frameLock){if(latestFrame!=null&&!latestFrame.isRecycled())latestFrame.recycle();latestFrame=null;}if(virtualDisplay!=null){try{virtualDisplay.release();}catch(Exception ignored){}virtualDisplay=null;}if(reader!=null){try{reader.close();}catch(Exception ignored){}reader=null;}if(projection!=null){try{projection.stop();}catch(Exception ignored){}projection=null;}}
    private void removeOverlays(){main.post(()->{if(wm==null)return;for(View v:new View[]{menu,bubble,bottom,center,matchView})if(v!=null)try{wm.removeView(v);}catch(Exception ignored){}wm=null;});}
    @Override public void onDestroy(){running.set(false);closeCapture();removeOverlays();scanner.shutdownNow();if(captureThread!=null)captureThread.quitSafely();super.onDestroy();}
    private Notification notification(String text){PendingIntent pi=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("FAC NX Lab Debug").setContentText(text.length()>90?text.substring(0,90):text).setContentIntent(pi).setOngoing(true).build();}
    private void updateNotification(String s){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.notify(NOTIFY_ID,notification(s));} private void createChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"NX Lab Debug Scanner",NotificationManager.IMPORTANCE_LOW));}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}} private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));} private static String fmt(double v){return String.format(Locale.US,"%.3f",v);} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
