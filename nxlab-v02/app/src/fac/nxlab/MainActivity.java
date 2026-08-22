package fac.nxlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String VERSION = "FAC NX Lab v0.2";
    private EditText threshold;
    private TextView state;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshState();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
    }

    private View buildUi() {
        int p = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p,p,p,p);
        root.setBackgroundColor(Color.rgb(12,15,19));

        root.addView(text(VERSION, 28, Color.WHITE, true), lp(-1,-2,0));
        TextView sub = text("Multi-template visual scanner\nAiwan recognition PNGs → screencap → match → live overlay", 14, Color.rgb(170,183,198), false);
        LinearLayout.LayoutParams slp=lp(-1,-2,0); slp.setMargins(0,dp(8),0,dp(18)); root.addView(sub,slp);

        state = text("Checking...",15,Color.rgb(255,193,7),true);
        root.addView(state,lp(-1,-2,0));

        TextView thTitle=text("Match threshold (0.70–0.99)",14,Color.WHITE,false);
        LinearLayout.LayoutParams tlp=lp(-1,-2,0); tlp.setMargins(0,dp(18),0,dp(6)); root.addView(thTitle,tlp);
        threshold=new EditText(this); threshold.setText("0.88"); threshold.setSingleLine(true); threshold.setTextColor(Color.WHITE); threshold.setBackgroundColor(Color.rgb(29,34,41)); threshold.setPadding(dp(12),0,dp(12),0); threshold.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); root.addView(threshold,lp(-1,dp(52),0));

        Button start=button("START MULTI-TEMPLATE SCAN");
        start.setOnClickListener(v->startScan()); root.addView(start,buttonLp());
        Button stop=button("STOP ACTIVE SCAN");
        stop.setOnClickListener(v->stopScan()); root.addView(stop,buttonLp());
        Button overlay=button("OPEN OVERLAY PERMISSION");
        overlay.setOnClickListener(v->openOverlaySettings()); root.addView(overlay,buttonLp());

        TextView info=text("During scanning:\n\n• floating NX ball on the right\n• tap ball → X / STOP SCRIPT\n• progress feed at bottom-right\n• important scan states in the center\n• matched templates get a red rectangle + arrow\n\nNo automatic taps are performed in v0.2.",15,Color.rgb(210,219,229),false);
        LinearLayout.LayoutParams ilp=lp(-1,-2,0); ilp.setMargins(0,dp(22),0,0); root.addView(info,ilp);
        return root;
    }

    private void startScan() {
        double th=0.88;
        try { th=Double.parseDouble(threshold.getText().toString().trim()); } catch(Exception ignored) {}
        th=Math.max(0.70,Math.min(0.99,th));
        final double value=th;
        new Thread(() -> {
            try {
                new ProcessBuilder("su","-c","appops set fac.nxlab android:system_alert_window allow 2>/dev/null || appops set fac.nxlab SYSTEM_ALERT_WINDOW allow 2>/dev/null || true").start().waitFor();
            } catch(Exception ignored) {}
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this,"Grant Display over other apps, then press Start again.",Toast.LENGTH_LONG).show();
                    openOverlaySettings();
                    return;
                }
                Intent i=new Intent(this,ScanService.class).setAction(ScanService.ACTION_START);
                i.putExtra("threshold",value);
                if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
                Toast.makeText(this,"Scanner starting. You can leave this screen.",Toast.LENGTH_LONG).show();
                moveTaskToBack(true);
            });
        },"nxlab-overlay-grant").start();
    }

    private void stopScan() {
        Intent i=new Intent(this,ScanService.class).setAction(ScanService.ACTION_STOP);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    private void openOverlaySettings() {
        if(Build.VERSION.SDK_INT<23) return;
        Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()));
        startActivity(i);
    }

    private void refreshState() {
        String abi=(Build.SUPPORTED_ABIS!=null&&Build.SUPPORTED_ABIS.length>0)?Build.SUPPORTED_ABIS[0]:Build.CPU_ABI;
        boolean overlay=Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(this);
        state.setText("ABI: "+abi+"  •  Overlay: "+(overlay?"READY":"NEEDS PERMISSION"));
        state.setTextColor(overlay?Color.rgb(78,218,118):Color.rgb(255,193,7));
    }

    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setBackgroundColor(Color.rgb(29,91,190));return b;}
    private LinearLayout.LayoutParams buttonLp(){LinearLayout.LayoutParams p=lp(-1,dp(54),0);p.setMargins(0,dp(10),0,0);return p;}
    private LinearLayout.LayoutParams lp(int w,int h,float weight){return new LinearLayout.LayoutParams(w,h,weight);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
