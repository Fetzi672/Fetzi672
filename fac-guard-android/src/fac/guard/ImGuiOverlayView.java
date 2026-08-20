package fac.guard;

import android.content.*;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Transparent Dear ImGui surface. When the panel is closed the WindowManager
 * keeps this view fullscreen but NOT_TOUCHABLE for masks/toasts. When opened,
 * FloatingMenuController resizes the actual Android window to the panel bounds,
 * so touches outside the panel pass straight through to the protected app.
 */
public final class ImGuiOverlayView extends GLSurfaceView implements GLSurfaceView.Renderer {
    public interface Listener {
        void onClose();
        void onSave(String settingsProtocol);
        void onRecheck();
        void onLayout(float widthFraction,float heightFraction,float uiScale);
    }

    private static final int ACTION_CLOSE=1;
    private static final int ACTION_SAVE=2;
    private static final int ACTION_RECHECK=4;
    private static final int ACTION_LAYOUT=8;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final float density;
    private final String initialStatus;
    private final String initialSettings;
    private volatile boolean keyboardShown;
    private volatile boolean detached;
    private volatile boolean renderScheduled;
    private volatile boolean nativeReady;
    private volatile float pendingWidth=.78f,pendingHeight=.78f,pendingScale=1.0f;

    static { System.loadLibrary("facui"); }

    public ImGuiOverlayView(Context context,String status,String settings,Listener l){
        super(context);
        listener=l;initialStatus=status==null?"":status;initialSettings=settings==null?"":settings;
        density=getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8,8,8,8,16,0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(true);setFocusableInTouchMode(true);
    }

    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        nativeInit(density,initialStatus,initialSettings);
        nativeSetUiLayout(pendingWidth,pendingHeight,pendingScale);
        nativeReady=true;requestRender();
    }
    @Override public void onSurfaceChanged(GL10 gl,int width,int height){nativeResize(width,height);requestRender();}

    @Override public void onDrawFrame(GL10 gl){
        renderScheduled=false;
        int action=nativeRender();
        boolean wants=nativeWantsTextInput();
        if(wants!=keyboardShown){keyboardShown=wants;main.post(()->setKeyboardVisible(wants));}
        if(action!=0){
            if((action&ACTION_RECHECK)!=0&&listener!=null)main.post(listener::onRecheck);
            if((action&ACTION_LAYOUT)!=0&&listener!=null){
                final String layout=nativeDumpUiLayout();
                main.post(()->dispatchLayout(layout));
            }
            if((action&ACTION_SAVE)!=0&&listener!=null){
                final String dump=nativeDumpSettings();main.post(()->listener.onSave(dump));
            }else if((action&ACTION_CLOSE)!=0&&listener!=null)main.post(listener::onClose);
        }
        if(nativeNeedsAnimation())scheduleRender(33L);
    }

    private void dispatchLayout(String layout){
        if(listener==null||layout==null)return;
        try{
            String[] x=layout.split("\\t");
            if(x.length<3)return;
            listener.onLayout(Float.parseFloat(x[0]),Float.parseFloat(x[1]),Float.parseFloat(x[2]));
        }catch(Exception ignored){}
    }

    private void scheduleRender(long delay){
        if(detached||renderScheduled)return;renderScheduled=true;
        main.postDelayed(()->{if(!detached)requestRender();},delay);
    }

    private void nativeCall(Runnable r){if(detached||!nativeReady)return;queueEvent(()->{r.run();requestRender();});}

    public void updateStatus(String status){nativeCall(()->nativeSetStatus(status==null?"":status));}
    public void updateSettings(String settings){nativeCall(()->nativeSetSettings(settings==null?"":settings));}
    public void updateTextMask(String protocol){nativeCall(()->nativeSetTextMask(protocol==null?"":protocol));}
    public void setPanelOpen(boolean open){nativeCall(()->nativeSetPanelOpen(open));}
    public void setUiLayout(float widthFraction,float heightFraction,float scale){
        pendingWidth=widthFraction;pendingHeight=heightFraction;pendingScale=scale;
        nativeCall(()->nativeSetUiLayout(pendingWidth,pendingHeight,pendingScale));
    }
    public void notify(int type,String title,String content){nativeCall(()->nativeNotify(type,title==null?"":title,content==null?"":content));}

    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        requestFocus();if(nativeReady)nativeTouch(e.getActionMasked(),e.getX(),e.getY());requestRender();return true;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(nativeReady)nativeKey(event.getKeyCode(),event.getAction(),event.getUnicodeChar());requestRender();return true;}
    @Override public boolean onCheckIsTextEditor(){return true;}

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs){
        outAttrs.inputType=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this,false){
            @Override public boolean commitText(CharSequence text,int newCursorPosition){if(text!=null&&nativeReady)nativeAddText(text.toString());requestRender();return true;}
            @Override public boolean setComposingText(CharSequence text,int newCursorPosition){if(text!=null&&nativeReady)nativeAddText(text.toString());requestRender();return true;}
            @Override public boolean deleteSurroundingText(int beforeLength,int afterLength){if(nativeReady){nativeKey(KeyEvent.KEYCODE_DEL,KeyEvent.ACTION_DOWN,0);nativeKey(KeyEvent.KEYCODE_DEL,KeyEvent.ACTION_UP,0);}requestRender();return true;}
            @Override public boolean sendKeyEvent(KeyEvent event){if(nativeReady)nativeKey(event.getKeyCode(),event.getAction(),event.getUnicodeChar());requestRender();return true;}
        };
    }

    private void setKeyboardVisible(boolean visible){
        if(detached)return;InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);if(imm==null)return;
        if(visible){requestFocus();imm.showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);}else imm.hideSoftInputFromWindow(getWindowToken(),0);
    }

    @Override protected void onDetachedFromWindow(){
        detached=true;setKeyboardVisible(false);main.removeCallbacksAndMessages(null);
        if(nativeReady){try{queueEvent(()->{nativeShutdown();nativeReady=false;});}catch(Exception ignored){nativeReady=false;}}
        super.onDetachedFromWindow();
    }

    private static native void nativeInit(float density,String status,String settings);
    private static native void nativeResize(int width,int height);
    private static native int nativeRender();
    private static native void nativeTouch(int action,float x,float y);
    private static native void nativeKey(int keyCode,int action,int unicodeChar);
    private static native void nativeAddText(String text);
    private static native boolean nativeWantsTextInput();
    private static native boolean nativeNeedsAnimation();
    private static native String nativeDumpSettings();
    private static native String nativeDumpUiLayout();
    private static native void nativeSetStatus(String status);
    private static native void nativeSetSettings(String settings);
    private static native void nativeSetTextMask(String protocol);
    private static native void nativeSetPanelOpen(boolean open);
    private static native void nativeSetUiLayout(float widthFraction,float heightFraction,float scale);
    private static native void nativeNotify(int type,String title,String content);
    private static native void nativeShutdown();
}
