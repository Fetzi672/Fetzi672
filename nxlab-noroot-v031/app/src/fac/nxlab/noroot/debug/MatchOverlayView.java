package fac.nxlab.noroot.debug;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.util.*;

public final class MatchOverlayView extends View {
    private static final int MATCH=1, CANDIDATE=2, SELFTEST=3;
    private static final long MATCH_LIFE=5000L, CANDIDATE_LIFE=2300L, SELF_LIFE=2200L;
    private static final class Box { final float x,y,w,h; final String label,score; final int type; final long born; Box(float x,float y,float w,float h,String l,String s,int t){this.x=x;this.y=y;this.w=w;this.h=h;label=l;score=s;type=t;born=SystemClock.uptimeMillis();} }
    private final List<Box> boxes=new ArrayList<>();
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG), fill=new Paint(Paint.ANTI_ALIAS_FLAG), text=new Paint(Paint.ANTI_ALIAS_FLAG), arrow=new Paint(Paint.ANTI_ALIAS_FLAG), tagBg=new Paint(Paint.ANTI_ALIAS_FLAG);
    public MatchOverlayView(Context c){super(c);setWillNotDraw(false);setBackgroundColor(Color.TRANSPARENT);text.setColor(Color.WHITE);text.setTextSize(dp(14));text.setFakeBoldText(true);arrow.setStyle(Paint.Style.FILL);tagBg.setStyle(Paint.Style.FILL);}
    public synchronized void addMatch(float x,float y,float w,float h,String label,String score){boxes.add(new Box(x,y,w,h,label,score,MATCH));invalidate();}
    public synchronized void setCandidates(List<float[]> list,String label){Iterator<Box> it=boxes.iterator();while(it.hasNext())if(it.next().type==CANDIDATE)it.remove();if(list!=null)for(float[] v:list)boxes.add(new Box(v[0],v[1],v[2],v[3],label,String.format(java.util.Locale.US,"%.3f",v[4]),CANDIDATE));invalidate();}
    public void showSelfTest(){post(()->{float w=Math.max(dp(150),getWidth()*0.18f),h=Math.max(dp(80),getHeight()*0.14f);float x=getWidth()*0.34f,y=getHeight()*0.25f;synchronized(this){boxes.add(new Box(x,y,w,h,"OVERLAY SELF-TEST","RED BOX = OK",SELFTEST));}invalidate();});}
    @Override protected void onDraw(Canvas c){super.onDraw(c);long now=SystemClock.uptimeMillis();boolean more=false;synchronized(this){Iterator<Box> it=boxes.iterator();while(it.hasNext()){Box b=it.next();long life=b.type==CANDIDATE?CANDIDATE_LIFE:(b.type==MATCH?MATCH_LIFE:SELF_LIFE);if(now-b.born>life){it.remove();continue;}more=true;int color=b.type==CANDIDATE?0xFFFFC107:0xFFFF2020;stroke.setColor(color);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(dp(b.type==CANDIDATE?3:5));fill.setColor(b.type==CANDIDATE?0x33FFC107:0x44FF2020);fill.setStyle(Paint.Style.FILL);arrow.setColor(color);tagBg.setColor(b.type==CANDIDATE?0xDD8A6900:0xDD8E1118);RectF r=new RectF(b.x,b.y,b.x+b.w,b.y+b.h);c.drawRect(r,fill);c.drawRect(r,stroke);float cx=r.centerX(), tipY=Math.max(dp(8),r.top-dp(4)), top=Math.max(dp(12),r.top-dp(66));Path p=new Path();p.moveTo(cx,tipY);p.lineTo(cx-dp(15),tipY-dp(23));p.lineTo(cx-dp(6),tipY-dp(23));p.lineTo(cx-dp(6),top);p.lineTo(cx+dp(6),top);p.lineTo(cx+dp(6),tipY-dp(23));p.lineTo(cx+dp(15),tipY-dp(23));p.close();c.drawPath(p,arrow);String s=(b.type==CANDIDATE?"BEST CANDIDATE • ":"MATCH • ")+b.label+" • "+b.score;float tw=text.measureText(s),tx=Math.max(dp(6),Math.min(getWidth()-tw-dp(6),r.left)),ty=Math.max(dp(20),r.top-dp(34));c.drawRoundRect(new RectF(tx-dp(6),ty-dp(18),tx+tw+dp(6),ty+dp(6)),dp(5),dp(5),tagBg);c.drawText(s,tx,ty,text);}}if(more)postInvalidateDelayed(100);}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
