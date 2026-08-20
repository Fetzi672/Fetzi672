package fac.guard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads only accessibility text owned by com.cocfz.com.freescript. Chinese and
 * legacy Aiwan/cocfz branding is covered at the same screen coordinates and FAC
 * renders an English replacement above it. The protected app is never modified.
 */
public final class TextMaskAccessibilityService extends AccessibilityService {
    private static final int MAX_NODES=280;
    private static final long TRANSIENT_MS=3200L;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable=this::scanNow;
    private final ArrayList<TransientMask> transientMasks=new ArrayList<>();
    private TextTranslator translator;

    private static final class TransientMask{
        final Rect bounds;final String text;final long until;
        TransientMask(Rect b,String t,long u){bounds=new Rect(b);text=t;until=u;}
    }

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        translator=new TextTranslator(this);
        AccessibilityServiceInfo i=new AccessibilityServiceInfo();
        i.eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            |AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            |AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            |AccessibilityEvent.TYPE_VIEW_SCROLLED
            |AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        i.feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC;
        i.notificationTimeout=60;
        i.packageNames=new String[]{RootOps.TARGET_PACKAGE};
        i.flags=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(i);
        FloatingMenuController.notifySuccess(this,"English UI","Chinese/Aiwan runtime text mask is active");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null||event.getPackageName()==null)return;
        if(!RootOps.TARGET_PACKAGE.contentEquals(event.getPackageName()))return;

        if(event.getEventType()==AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            captureTransientNotification(event);

        main.removeCallbacks(scanRunnable);
        main.postDelayed(scanRunnable,45L);
    }

    private void captureTransientNotification(AccessibilityEvent event){
        StringBuilder raw=new StringBuilder();
        if(event.getText()!=null){for(CharSequence x:event.getText()){if(x!=null&&x.length()>0){if(raw.length()>0)raw.append(' ');raw.append(x);}}}
        CharSequence desc=event.getContentDescription();if(desc!=null&&desc.length()>0){if(raw.length()>0)raw.append(' ');raw.append(desc);}
        String source=raw.toString().trim();
        if(!TextTranslator.shouldMask(source))return;
        String english=translator==null?"FAC Message":translator.translate(source,false);
        if(english==null||english.trim().length()==0)english="FAC Message";

        Rect bounds=new Rect();
        AccessibilityNodeInfo src=event.getSource();
        try{if(src!=null)src.getBoundsInScreen(bounds);}catch(Exception ignored){}finally{try{if(src!=null)src.recycle();}catch(Exception ignored){}}
        if(bounds.width()<8||bounds.height()<8){
            DisplayMetrics dm=getResources().getDisplayMetrics();
            int w=dm.widthPixels,h=dm.heightPixels;
            bounds.set((int)(w*.08f),(int)(h*.76f),(int)(w*.92f),(int)(h*.86f));
        }
        synchronized(transientMasks){
            transientMasks.add(new TransientMask(bounds,english,SystemClock.elapsedRealtime()+TRANSIENT_MS));
            while(transientMasks.size()>6)transientMasks.remove(0);
        }
        FloatingMenuController.notifyInfo(this,"FAC Runtime",english);
    }

    @Override public void onInterrupt(){FloatingMenuController.updateTextMask(this,"");}

    @Override public boolean onUnbind(android.content.Intent intent){
        FloatingMenuController.updateTextMask(this,"");
        synchronized(transientMasks){transientMasks.clear();}
        return super.onUnbind(intent);
    }

    private void scanNow(){
        if(!LicenseStore.isSessionActive(this)||!LicenseStore.isLocallyValid(this)||!RootOps.isTargetRunning()){
            FloatingMenuController.updateTextMask(this,"");return;
        }
        StringBuilder out=new StringBuilder(12288);
        HashSet<String> seen=new HashSet<>();
        int count=0;

        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null){
            try{
                if(root.getPackageName()!=null&&RootOps.TARGET_PACKAGE.contentEquals(root.getPackageName())){
                    ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);
                    while(!q.isEmpty()&&count<MAX_NODES){
                        AccessibilityNodeInfo n=q.removeFirst();
                        for(int x=0;x<n.getChildCount();x++){
                            AccessibilityNodeInfo c=n.getChild(x);if(c!=null)q.addLast(c);
                        }
                        if(!n.isVisibleToUser())continue;
                        CharSequence text=n.getText();if(text==null||text.length()==0)text=n.getContentDescription();
                        if(text==null)continue;
                        String raw=text.toString().trim();if(raw.length()==0||raw.length()>900||!TextTranslator.shouldMask(raw))continue;
                        Rect b=new Rect();n.getBoundsInScreen(b);
                        if(b.width()<3||b.height()<3||b.left<0||b.top<0)continue;
                        String translated=translator==null?"FAC Message":translator.translate(raw,n.isClickable());
                        if(translated==null||translated.trim().length()==0)translated=n.isClickable()?"FAC Action":"FAC Message";
                        translated=translated.replace('\n',' ').replace('\r',' ').trim();
                        count=appendMask(out,seen,b,translated,n.isClickable(),count);
                    }
                }
            }finally{try{root.recycle();}catch(Exception ignored){}}
        }

        long now=SystemClock.elapsedRealtime();
        synchronized(transientMasks){
            Iterator<TransientMask> it=transientMasks.iterator();
            while(it.hasNext()){
                TransientMask m=it.next();
                if(now>=m.until){it.remove();continue;}
                count=appendMask(out,seen,m.bounds,m.text,false,count);
            }
        }
        FloatingMenuController.updateTextMask(this,out.toString());
        if(hasTransient())main.postDelayed(scanRunnable,180L);
    }

    private boolean hasTransient(){synchronized(transientMasks){return !transientMasks.isEmpty();}}

    private static int appendMask(StringBuilder out,Set<String> seen,Rect b,String translated,boolean clickable,int count){
        String key=b.left+":"+b.top+":"+b.right+":"+b.bottom+":"+translated;
        if(!seen.add(key))return count;
        out.append(b.left).append('\t').append(b.top).append('\t').append(b.right).append('\t').append(b.bottom).append('\t')
           .append(Base64.encodeToString(translated.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)).append('\t')
           .append(clickable?"1":"0").append('\n');
        return count+1;
    }
}
