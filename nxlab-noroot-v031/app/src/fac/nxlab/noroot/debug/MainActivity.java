package fac.nxlab.noroot.debug;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE=301, REQ_OVERLAY=302;
    private EditText threshold;
    private TextView status;

    @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(buildUi());refresh();}

    private LinearLayout buildUi(){
        int p=dp(18); LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(p,p,p,p); r.setBackgroundColor(0xFF0D1014);
        TextView t=text("FAC NX Lab Non-Root v0.3.1",28,Color.WHITE,true); r.addView(t);
        r.addView(text("Debug scanner • MediaProjection • red matches / yellow candidates",14,0xFFB9C1CB,false));
        status=text("Checking overlay permission...",15,0xFFFFC107,true); LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2); slp.setMargins(0,dp(12),0,dp(8)); r.addView(status,slp);
        threshold=new EditText(this); threshold.setText("0.88"); threshold.setHint("Match threshold"); threshold.setSingleLine(true); threshold.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); threshold.setTextColor(Color.WHITE); threshold.setHintTextColor(Color.GRAY); threshold.setBackgroundColor(0xFF20252B); threshold.setPadding(dp(12),0,dp(12),0); r.addView(threshold,new LinearLayout.LayoutParams(-1,dp(52)));
        Button overlay=button("1. GRANT / CHECK FLOATING OVERLAY"); overlay.setOnClickListener(v->openOverlay()); r.addView(overlay,lp());
        Button start=button("2. START NON-ROOT DEBUG SCAN"); start.setOnClickListener(v->requestCapture()); r.addView(start,lp());
        Button stop=button("STOP SCANNER"); stop.setBackgroundColor(0xFF8E1118); stop.setOnClickListener(v->{Intent i=new Intent(this,ScanService.class).setAction(ScanService.ACTION_STOP);startService(i);}); r.addView(stop,lp());
        TextView info=text("Startup overlay self-test: a RED box + arrow should appear briefly.\nDuring scan: RED = threshold match, YELLOW = best candidate below threshold.\nThe live feed prints best=score / required=threshold for every template.\nNo root and no automatic taps.",14,0xFFD4DAE2,false); LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(-1,-2); ilp.setMargins(0,dp(16),0,0); r.addView(info,ilp);
        return r;
    }
    private void requestCapture(){
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){Toast.makeText(this,"Grant overlay permission first",Toast.LENGTH_SHORT).show();openOverlay();return;}
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE); startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==REQ_OVERLAY){refresh();return;}if(req==REQ_CAPTURE&&result==RESULT_OK&&data!=null){double th=0.88;try{th=Double.parseDouble(threshold.getText().toString().trim());}catch(Exception ignored){}th=Math.max(0.70,Math.min(0.99,th));Intent i=new Intent(this,ScanService.class).setAction(ScanService.ACTION_START);i.putExtra(ScanService.EXTRA_RESULT_CODE,result);i.putExtra(ScanService.EXTRA_PROJECTION_DATA,data);i.putExtra(ScanService.EXTRA_THRESHOLD,th);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);Toast.makeText(this,"Scanner started • switch to CoC",Toast.LENGTH_LONG).show();moveTaskToBack(true);} }
    private void openOverlay(){if(Build.VERSION.SDK_INT<23)return;Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()));startActivityForResult(i,REQ_OVERLAY);}
    private void refresh(){boolean ok=Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(this);status.setText(ok?"Overlay: READY • Capture: Android MediaProjection":"Overlay: PERMISSION REQUIRED");status.setTextColor(ok?0xFF4EDA76:0xFFFF5A5A);}
    private TextView text(String s,int sz,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sz);v.setTextColor(color);if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setGravity(Gravity.CENTER);b.setBackgroundColor(0xFF2164CD);return b;}
    private LinearLayout.LayoutParams lp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.setMargins(0,dp(9),0,0);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
