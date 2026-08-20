package fac.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Base64;
import android.view.View;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Trusted accessibility-overlay renderer for English FAC text replacements.
 * The owning window is TYPE_ACCESSIBILITY_OVERLAY + FLAG_NOT_TOUCHABLE, so it
 * can visually cover legacy text without becoming an untrusted touch blocker.
 */
public final class AccessibilityMaskView extends View {
    private static final class Mask {
        float l,t,r,b; String text; boolean clickable;
    }

    private final ArrayList<Mask> masks=new ArrayList<>();
    private final Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    private final float density;

    public AccessibilityMaskView(Context c){
        super(c);
        density=getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        text.setColor(Color.rgb(248,248,250));
        text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD));
    }

    public void setProtocol(String protocol){
        final ArrayList<Mask> next=parse(protocol==null?"":protocol);
        post(()->{synchronized(masks){masks.clear();masks.addAll(next);}invalidate();});
    }

    public void clear(){setProtocol("");}

    private ArrayList<Mask> parse(String protocol){
        ArrayList<Mask> out=new ArrayList<>();
        String[] lines=protocol.split("\\n");
        for(String line:lines){
            if(line==null||line.length()==0)continue;
            String[] x=line.split("\\t",-1);if(x.length<6)continue;
            try{
                Mask m=new Mask();
                m.l=Float.parseFloat(x[0]);m.t=Float.parseFloat(x[1]);m.r=Float.parseFloat(x[2]);m.b=Float.parseFloat(x[3]);
                m.text=new String(Base64.decode(x[4],Base64.NO_WRAP),StandardCharsets.UTF_8).trim();
                m.clickable="1".equals(x[5]);
                if(m.r>m.l&&m.b>m.t&&m.text.length()>0)out.add(m);
            }catch(Exception ignored){}
        }
        return out;
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        final ArrayList<Mask> snapshot;
        synchronized(masks){snapshot=new ArrayList<>(masks);}
        final float pad=2.0f*density;
        for(Mask m:snapshot){
            float l=Math.max(0,m.l-pad),t=Math.max(0,m.t-pad),r=Math.min(getWidth(),m.r+pad),b=Math.min(getHeight(),m.b+pad);
            if(r-l<4||b-t<4)continue;
            bg.setColor(m.clickable?Color.rgb(48,20,25):Color.rgb(17,18,22));
            c.drawRoundRect(new RectF(l,t,r,b),4*density,4*density,bg);

            float boxW=Math.max(8,r-l-6*density),boxH=Math.max(8,b-t-4*density);
            float size=Math.min(15*density,boxH*0.68f);
            size=Math.max(9*density,size);
            text.setTextSize(size);
            while(size>8*density&&text.measureText(m.text)>boxW){size-=0.75f*density;text.setTextSize(size);}
            Paint.FontMetrics fm=text.getFontMetrics();
            float baseline=t+(b-t-fm.bottom+fm.top)/2f-fm.top;
            c.save();c.clipRect(l,t,r,b);
            c.drawText(m.text,l+3*density,baseline,text);
            c.restore();
        }
    }
}
