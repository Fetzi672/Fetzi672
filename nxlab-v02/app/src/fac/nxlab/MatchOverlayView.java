package fac.nxlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public final class MatchOverlayView extends View {
    static final class Box { float x,y,w,h; String label,score; Box(float x,float y,float w,float h,String l,String s){this.x=x;this.y=y;this.w=w;this.h=h;label=l;score=s;} }
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Box> boxes=new ArrayList<>();

    public MatchOverlayView(Context c){super(c);setWillNotDraw(false);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(dp(4));stroke.setColor(Color.RED);fill.setStyle(Paint.Style.FILL);fill.setColor(0xDDCC1010);text.setColor(Color.WHITE);text.setTextSize(dp(14));text.setFakeBoldText(true);}
    public synchronized void add(float x,float y,float w,float h,String label,String score){boxes.add(new Box(x,y,w,h,label,score));if(boxes.size()>8)boxes.remove(0);postInvalidate();}
    public synchronized void clear(){boxes.clear();postInvalidate();}
    @Override protected synchronized void onDraw(Canvas c){super.onDraw(c);for(Box b:boxes){RectF r=new RectF(b.x,b.y,b.x+b.w,b.y+b.h);c.drawRect(r,stroke);float sx=Math.max(dp(18),b.x-dp(70)),sy=Math.max(dp(24),b.y-dp(65));float ex=b.x,ey=b.y;stroke.setStrokeWidth(dp(5));c.drawLine(sx,sy,ex,ey,stroke);double a=Math.atan2(ey-sy,ex-sx);float len=dp(18);Path p=new Path();p.moveTo(ex,ey);p.lineTo((float)(ex-len*Math.cos(a-0.55)),(float)(ey-len*Math.sin(a-0.55)));p.moveTo(ex,ey);p.lineTo((float)(ex-len*Math.cos(a+0.55)),(float)(ey-len*Math.sin(a+0.55)));c.drawPath(p,stroke);String s=b.label+"  "+b.score;float tw=text.measureText(s);float top=Math.max(dp(2),b.y-dp(27));c.drawRect(b.x,top,b.x+tw+dp(12),top+dp(24),fill);c.drawText(s,b.x+dp(6),top+dp(17),text);}}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
