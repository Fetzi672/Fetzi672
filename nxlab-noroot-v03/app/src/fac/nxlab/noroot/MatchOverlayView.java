package fac.nxlab.noroot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class MatchOverlayView extends View {
    private static final long LIFE_MS = 2800L;

    private static final class Box {
        final float x, y, w, h;
        final String label, score;
        final long born;
        Box(float x, float y, float w, float h, String label, String score) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.label = label; this.score = score; this.born = android.os.SystemClock.uptimeMillis();
        }
    }

    private final List<Box> boxes = new ArrayList<>();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MatchOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        stroke.setColor(Color.RED);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(3));
        fill.setColor(0x44FF0000);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTextSize(dp(14));
        text.setFakeBoldText(true);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public synchronized void add(float x, float y, float w, float h, String label, String score) {
        boxes.add(new Box(x, y, w, h, label, score));
        invalidate();
    }

    public synchronized void clear() {
        boxes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        long now = android.os.SystemClock.uptimeMillis();
        boolean more = false;
        synchronized (this) {
            Iterator<Box> it = boxes.iterator();
            while (it.hasNext()) {
                Box b = it.next();
                if (now - b.born > LIFE_MS) { it.remove(); continue; }
                more = true;
                RectF r = new RectF(b.x, b.y, b.x + b.w, b.y + b.h);
                c.drawRect(r, fill);
                c.drawRect(r, stroke);

                float cx = r.centerX();
                float arrowTop = Math.max(dp(18), r.top - dp(52));
                Path p = new Path();
                p.moveTo(cx, r.top - dp(4));
                p.lineTo(cx - dp(12), r.top - dp(23));
                p.lineTo(cx - dp(4), r.top - dp(23));
                p.lineTo(cx - dp(4), arrowTop);
                p.lineTo(cx + dp(4), arrowTop);
                p.lineTo(cx + dp(4), r.top - dp(23));
                p.lineTo(cx + dp(12), r.top - dp(23));
                p.close();
                c.drawPath(p, stroke);

                String s = b.label + "  " + b.score;
                float tw = text.measureText(s);
                float tx = Math.max(dp(6), Math.min(getWidth() - tw - dp(6), r.left));
                float ty = Math.max(dp(18), r.top - dp(30));
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(0xCC8E1118);
                c.drawRoundRect(new RectF(tx - dp(5), ty - dp(16), tx + tw + dp(5), ty + dp(5)), dp(4), dp(4), bg);
                c.drawText(s, tx, ty, text);
            }
        }
        if (more) postInvalidateDelayed(120);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
