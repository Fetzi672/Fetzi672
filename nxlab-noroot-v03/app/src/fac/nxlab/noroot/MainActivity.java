package fac.nxlab.noroot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
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
    private static final int REQ_CAPTURE = 4102;
    private static final int REQ_NOTIFY = 4103;
    private static final String VERSION = "FAC NX Lab Non-Root v0.3";

    private TextView overlayState;
    private TextView captureState;
    private EditText thresholdField;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(13, 16, 20));

        TextView title = text(VERSION, 26, Color.WHITE, true);
        root.addView(title, lp(-1, -2, 0, 0));

        TextView sub = text(
                "Non-root scanner using Android MediaProjection screen capture.\n" +
                "No su, no root shell, no privileged screenshot path.",
                14, Color.rgb(176, 187, 198), false);
        root.addView(sub, lp(-1, -2, 8, 14));

        overlayState = text("Overlay: checking...", 15, Color.WHITE, false);
        captureState = text("Screen capture: permission requested on each new capture session", 15, Color.WHITE, false);
        root.addView(overlayState, lp(-1, -2, 0, 5));
        root.addView(captureState, lp(-1, -2, 0, 14));

        Button overlay = button("GRANT FLOATING OVERLAY");
        overlay.setOnClickListener(v -> requestOverlay());
        root.addView(overlay, buttonLp());

        TextView thresholdLabel = text("Template threshold (0.70 – 0.99)", 14, Color.WHITE, false);
        root.addView(thresholdLabel, lp(-1, -2, 16, 5));

        thresholdField = new EditText(this);
        thresholdField.setText("0.88");
        thresholdField.setSingleLine(true);
        thresholdField.setTextColor(Color.WHITE);
        thresholdField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        thresholdField.setBackgroundColor(Color.rgb(31, 36, 43));
        thresholdField.setPadding(dp(12), 0, dp(12), 0);
        root.addView(thresholdField, new LinearLayout.LayoutParams(-1, dp(52)));

        Button start = button("START NON-ROOT SCREEN SCAN");
        start.setOnClickListener(v -> beginCapture());
        root.addView(start, buttonLp());

        Button stop = button("STOP SCANNER");
        stop.setBackgroundColor(Color.rgb(145, 35, 42));
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, ScanService.class).setAction(ScanService.ACTION_STOP);
            startService(i);
            Toast.makeText(this, "Stop requested", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, buttonLp());

        TextView info = text(
                "Test flow\n\n" +
                "1. Grant overlay permission once.\n" +
                "2. Tap START and accept Android's screen-capture dialog.\n" +
                "3. Switch to Aiwan / CoC.\n" +
                "4. The right-side NX ball stops the scanner.\n\n" +
                "During scanning, progress is shown bottom-right and in the center. " +
                "Matched templates receive a red rectangle, arrow, label and score.\n\n" +
                "This build does not auto-tap anything.",
                14, Color.rgb(200, 207, 215), false);
        root.addView(info, lp(-1, -2, 18, 0));

        return root;
    }

    private void beginCapture() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show();
            requestOverlay();
            return;
        }
        double threshold;
        try {
            threshold = Double.parseDouble(thresholdField.getText().toString().trim());
        } catch (Exception e) {
            threshold = 0.88;
        }
        if (threshold < 0.70) threshold = 0.70;
        if (threshold > 0.99) threshold = 0.99;
        thresholdField.setText(String.format(java.util.Locale.US, "%.2f", threshold));

        Intent capture = projectionManager.createScreenCaptureIntent();
        capture.putExtra("fac_nx_threshold", threshold);
        startActivityForResult(capture, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            captureState.setText("Screen capture: DENIED / CANCELLED");
            captureState.setTextColor(Color.rgb(255, 95, 95));
            return;
        }

        double threshold;
        try { threshold = Double.parseDouble(thresholdField.getText().toString().trim()); }
        catch (Exception e) { threshold = 0.88; }

        Intent service = new Intent(this, ScanService.class)
                .setAction(ScanService.ACTION_START)
                .putExtra(ScanService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScanService.EXTRA_PROJECTION_DATA, data)
                .putExtra(ScanService.EXTRA_THRESHOLD, threshold);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);

        captureState.setText("Screen capture: ACTIVE • MediaProjection granted");
        captureState.setTextColor(Color.rgb(85, 220, 125));
        Toast.makeText(this, "Scanner starting. Switch to the target app.", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay already allowed", Toast.LENGTH_SHORT).show();
            refreshState();
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private void refreshState() {
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        overlayState.setText(overlay ? "Overlay: READY" : "Overlay: NOT GRANTED");
        overlayState.setTextColor(overlay ? Color.rgb(85, 220, 125) : Color.rgb(255, 180, 60));
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Color.rgb(33, 100, 205));
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, dp(9), 0, 0);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
