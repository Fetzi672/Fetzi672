package fac.guard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads only accessibility text owned by com.cocfz.com.freescript, converts it
 * to English and sends screen-space masks to the FAC ImGui overlay. It never
 * changes the protected process or its view hierarchy.
 */
public final class TextMaskAccessibilityService extends AccessibilityService {
    private static final int MAX_NODES=220;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable=this::scanNow;
    private TextTranslator translator;

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        translator=new TextTranslator(this);
        AccessibilityServiceInfo i=new AccessibilityServiceInfo();
        i.eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            |AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            |AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            |AccessibilityEvent.TYPE_VIEW_SCROLLED;
        i.feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC;
        i.notificationTimeout=80;
        i.packageNames=new String[]{RootOps.TARGET_PACKAGE};
        i.flags=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(i);
        FloatingMenuController.notifyInfo(this,"Text Mask","English UI masking ready");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null||event.getPackageName()==null)return;
        if(!RootOps.TARGET_PACKAGE.contentEquals(event.getPackageName()))return;
        main.removeCallbacks(scanRunnable);
        main.postDelayed(scanRunnable,70L);
    }

    @Override public void onInterrupt(){FloatingMenuController.updateTextMask(this,"");}

    @Override public boolean onUnbind(android.content.Intent intent){
        FloatingMenuController.updateTextMask(this,"");
        return super.onUnbind(intent);
    }

    private void scanNow(){
        if(!LicenseStore.isSessionActive(this)||!LicenseStore.isLocallyValid(this)||!RootOps.isTargetRunning()){
            FloatingMenuController.updateTextMask(this,"");return;
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){FloatingMenuController.updateTextMask(this,"");return;}
        try{
            if(root.getPackageName()==null||!RootOps.TARGET_PACKAGE.contentEquals(root.getPackageName())){
                FloatingMenuController.updateTextMask(this,"");return;
            }
            ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);
            StringBuilder out=new StringBuilder(8192);
            HashSet<String> seen=new HashSet<>();int count=0;
            while(!q.isEmpty()&&count<MAX_NODES){
                AccessibilityNodeInfo n=q.removeFirst();
                for(int x=0;x<n.getChildCount();x++){
                    AccessibilityNodeInfo c=n.getChild(x);if(c!=null)q.addLast(c);
                }
                if(!n.isVisibleToUser())continue;
                CharSequence text=n.getText();
                if(text==null||text.length()==0)text=n.getContentDescription();
                if(text==null)continue;
                String raw=text.toString().trim();if(raw.length()==0||raw.length()>700)continue;
                Rect b=new Rect();n.getBoundsInScreen(b);
                if(b.width()<3||b.height()<3||b.left<0||b.top<0)continue;
                String translated=translator==null?raw:translator.translate(raw,n.isClickable());
                if(translated==null||translated.trim().length()==0)translated=n.isClickable()?"Action":"Status";
                translated=translated.replace('\n',' ').replace('\r',' ').trim();
                String key=b.left+":"+b.top+":"+b.right+":"+b.bottom+":"+translated;
                if(!seen.add(key))continue;
                out.append(b.left).append('\t').append(b.top).append('\t').append(b.right).append('\t').append(b.bottom).append('\t')
                   .append(Base64.encodeToString(translated.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)).append('\t')
                   .append(n.isClickable()?"1":"0").append('\n');
                count++;
            }
            FloatingMenuController.updateTextMask(this,out.toString());
        }finally{
            try{root.recycle();}catch(Exception ignored){}
        }
    }
}
