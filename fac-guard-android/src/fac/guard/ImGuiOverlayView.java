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

/** GLSurfaceView host for the native Dear ImGui FAC panel. */
public final class ImGuiOverlayView extends GLSurfaceView implements GLSurfaceView.Renderer {
    public interface Listener {
        void onClose();
        void onSave(String settingsProtocol);
        void onRecheck();
    }

    private static final int ACTION_CLOSE=1;
    private static final int ACTION_SAVE=2;
    private static final int ACTION_RECHECK=4;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final float density;
    private String initialStatus;
    private String initialSettings;
    private volatile boolean keyboardShown;
    private volatile boolean detached;

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
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setFocusable(true);setFocusableInTouchMode(true);requestFocus();
    }

    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        nativeInit(density,initialStatus,initialSettings);
    }

    @Override public void onSurfaceChanged(GL10 gl,int width,int height){nativeResize(width,height);}

    @Override public void onDrawFrame(GL10 gl){
        int action=nativeRender();
        boolean wants=nativeWantsTextInput();
        if(wants!=keyboardShown){
            keyboardShown=wants;
            main.post(()->setKeyboardVisible(wants));
        }
        if(action!=0){
            if((action&ACTION_RECHECK)!=0&&listener!=null)main.post(listener::onRecheck);
            if((action&ACTION_SAVE)!=0&&listener!=null){
                final String dump=nativeDumpSettings();
                main.post(()->listener.onSave(dump));
            }else if((action&ACTION_CLOSE)!=0&&listener!=null){
                main.post(listener::onClose);
            }
        }
    }

    public void updateStatus(final String status){
        if(detached)return;
        queueEvent(()->nativeSetStatus(status==null?"":status));
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        requestFocus();
        nativeTouch(e.getActionMasked(),e.getX(),e.getY());
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int u=event.getUnicodeChar();
        nativeKey(event.getKeyCode(),event.getAction(),u);
        return true;
    }

    @Override public boolean onCheckIsTextEditor(){return true;}

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs){
        outAttrs.inputType=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this,false){
            @Override public boolean commitText(CharSequence text,int newCursorPosition){
                if(text!=null)nativeAddText(text.toString());return true;
            }
            @Override public boolean setComposingText(CharSequence text,int newCursorPosition){
                if(text!=null)nativeAddText(text.toString());return true;
            }
            @Override public boolean deleteSurroundingText(int beforeLength,int afterLength){
                nativeKey(KeyEvent.KEYCODE_DEL,KeyEvent.ACTION_DOWN,0);
                nativeKey(KeyEvent.KEYCODE_DEL,KeyEvent.ACTION_UP,0);
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event){
                nativeKey(event.getKeyCode(),event.getAction(),event.getUnicodeChar());return true;
            }
        };
    }

    private void setKeyboardVisible(boolean visible){
        if(detached)return;
        InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(imm==null)return;
        if(visible){requestFocus();imm.showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);}
        else imm.hideSoftInputFromWindow(getWindowToken(),0);
    }

    @Override protected void onDetachedFromWindow(){
        detached=true;
        setKeyboardVisible(false);
        try{queueEvent(ImGuiOverlayView::nativeShutdown);}catch(Exception ignored){}
        super.onDetachedFromWindow();
    }

    private static native void nativeInit(float density,String status,String settings);
    private static native void nativeResize(int width,int height);
    private static native int nativeRender();
    private static native void nativeTouch(int action,float x,float y);
    private static native void nativeKey(int keyCode,int action,int unicodeChar);
    private static native void nativeAddText(String text);
    private static native boolean nativeWantsTextInput();
    private static native String nativeDumpSettings();
    private static native void nativeSetStatus(String status);
    private static native void nativeShutdown();
}
