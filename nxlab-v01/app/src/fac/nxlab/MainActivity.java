package fac.nxlab;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String VERSION = "FAC NX Lab v0.1";
    private static final String ROOT_DIR = "/data/local/tmp/fac_nxlab";
    private static final String RUNNER = ROOT_DIR + "/nxlab-runner";
    private static final String CUSTOM_LUA = "/sdcard/Download/NXLab/main.lua";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView log;
    private EditText xField;
    private EditText yField;
    private Button selfTestButton;
    private Button tapButton;
    private Button writeSampleButton;
    private Button runCustomButton;
    private volatile boolean staged;

    private static final String SAMPLE_LUA =
            "print(\"=== NXLab editable Lua ===\")\n" +
            "print(\"This file is executed by the lrappsoft-compatible runner.\")\n" +
            "local info = getSystemInfo()\n" +
            "print(\"screen=\" .. tostring(info.width) .. \"x\" .. tostring(info.height))\n" +
            "print(\"package=\" .. tostring(getPackageName()))\n" +
            "print(\"activity=\" .. tostring(getActivityName()))\n" +
            "print(\"root=\" .. tostring(touch.isRooted()))\n" +
            "print(\"Lua hot-load works. Edit this file and run it again.\")\n" +
            "-- Example only; remove -- to test a tap yourself:\n" +
            "-- sleep(3000); tap(360, 640)\n";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        append("Booting " + VERSION + "...");
        append("ABI: " + primaryAbi());
        append("Runner: AutoGo + autogo_scriptengine v0.0.22 / lrappsoft device+touch");
        refreshRootStatus();
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(13, 16, 20));

        TextView title = new TextView(this);
        title.setText(VERSION);
        title.setTextColor(Color.WHITE);
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("First NX/Lazy-Elf compatibility proof-of-concept\nLua engine → Android bridge → lrappsoft API");
        subtitle.setTextColor(Color.rgb(175, 185, 196));
        subtitle.setTextSize(14);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(0, dp(8), 0, dp(14));
        root.addView(subtitle, subLp);

        status = new TextView(this);
        status.setText("Root: checking...");
        status.setTextColor(Color.rgb(255, 193, 7));
        status.setTextSize(15);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        selfTestButton = makeButton("RUN LUA SELF-TEST");
        selfTestButton.setOnClickListener(v -> runSelfTest());
        root.addView(selfTestButton, buttonLp());

        TextView touchTitle = new TextView(this);
        touchTitle.setText("Touch test (3 second delay)");
        touchTitle.setTextColor(Color.WHITE);
        touchTitle.setTextSize(15);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.setMargins(0, dp(16), 0, dp(6));
        root.addView(touchTitle, tLp);

        LinearLayout coords = new LinearLayout(this);
        coords.setOrientation(LinearLayout.HORIZONTAL);
        xField = makeNumberField("X", "360");
        yField = makeNumberField("Y", "640");
        coords.addView(xField, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams yLp = new LinearLayout.LayoutParams(0, dp(52), 1);
        yLp.setMargins(dp(8), 0, 0, 0);
        coords.addView(yField, yLp);
        root.addView(coords, new LinearLayout.LayoutParams(-1, -2));

        tapButton = makeButton("ARM TAP TEST");
        tapButton.setOnClickListener(v -> runTapTest());
        root.addView(tapButton, buttonLp());

        writeSampleButton = makeButton("WRITE SAMPLE LUA");
        writeSampleButton.setOnClickListener(v -> writeSampleLua());
        root.addView(writeSampleButton, buttonLp());

        runCustomButton = makeButton("RUN /DOWNLOAD/NXLAB/MAIN.LUA");
        runCustomButton.setOnClickListener(v -> runCustomLua());
        root.addView(runCustomButton, buttonLp());

        TextView logTitle = new TextView(this);
        logTitle.setText("Runner output");
        logTitle.setTextColor(Color.WHITE);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextSize(15);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(-1, -2);
        ltp.setMargins(0, dp(16), 0, dp(6));
        root.addView(logTitle, ltp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(6, 8, 11));
        log = new TextView(this);
        log.setTextColor(Color.rgb(146, 255, 173));
        log.setTextSize(12);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(dp(10), dp(10), dp(10), dp(10));
        scroll.addView(log, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        return root;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Color.rgb(33, 100, 205));
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, dp(9), 0, 0);
        return lp;
    }

    private EditText makeNumberField(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setBackgroundColor(Color.rgb(30, 34, 40));
        e.setPadding(dp(12), 0, dp(12), 0);
        return e;
    }

    private void refreshRootStatus() {
        worker.execute(() -> {
            try {
                ExecResult r = execRoot("id", 5000);
                boolean ok = r.code == 0 && r.output.contains("uid=0");
                main.post(() -> {
                    status.setText(ok ? "Root: READY • Runner: not staged" : "Root: NOT AVAILABLE");
                    status.setTextColor(ok ? Color.rgb(78, 218, 118) : Color.rgb(255, 90, 90));
                    setButtonsEnabled(ok && supportedAbi());
                });
            } catch (Exception e) {
                main.post(() -> {
                    status.setText("Root: ERROR • " + e.getMessage());
                    status.setTextColor(Color.rgb(255, 90, 90));
                    setButtonsEnabled(false);
                });
            }
        });
    }

    private void runSelfTest() {
        runTask("Lua self-test", () -> runRunner("selftest", 35000));
    }

    private void runTapTest() {
        final int x;
        final int y;
        try {
            x = Integer.parseInt(xField.getText().toString().trim());
            y = Integer.parseInt(yField.getText().toString().trim());
            if (x < 0 || y < 0) throw new NumberFormatException();
        } catch (Exception e) {
            Toast.makeText(this, "Enter valid X/Y coordinates", Toast.LENGTH_SHORT).show();
            return;
        }

        setButtonsEnabled(false);
        append("\n--- Touch test ---");
        worker.execute(() -> {
            try {
                stageRunner();
                main.post(() -> {
                    Toast.makeText(MainActivity.this, "Tap armed: 3 seconds. Switch to the target screen.", Toast.LENGTH_LONG).show();
                    moveTaskToBack(true);
                });
                SystemClock.sleep(450);
                ExecResult r = runRunner("tap " + x + " " + y, 15000);
                main.post(() -> append(r.output + "\nexit=" + r.code));
            } catch (Exception e) {
                main.post(() -> append("ERROR: " + e));
            } finally {
                main.post(() -> setButtonsEnabled(true));
            }
        });
    }

    private void writeSampleLua() {
        setButtonsEnabled(false);
        append("\n--- Write sample Lua ---");
        worker.execute(() -> {
            try {
                byte[] data = SAMPLE_LUA.getBytes(StandardCharsets.UTF_8);
                ExecResult r = pipeToRoot(
                        "mkdir -p /sdcard/Download/NXLab && cat > " + shellQuote(CUSTOM_LUA) + " && chmod 0644 " + shellQuote(CUSTOM_LUA),
                        new ByteArrayInputStream(data), 10000);
                main.post(() -> append("path=" + CUSTOM_LUA + "\n" + r.output + "\nexit=" + r.code));
            } catch (Exception e) {
                main.post(() -> append("ERROR: " + e));
            } finally {
                main.post(() -> setButtonsEnabled(true));
            }
        });
    }

    private void runCustomLua() {
        runTask("Custom Lua", () -> runRunner("runfile " + shellQuote(CUSTOM_LUA), 35000));
    }

    private void runTask(String name, Task task) {
        setButtonsEnabled(false);
        append("\n--- " + name + " ---");
        worker.execute(() -> {
            try {
                ExecResult r = task.run();
                main.post(() -> append(r.output + "\nexit=" + r.code));
            } catch (Exception e) {
                main.post(() -> append("ERROR: " + e));
            } finally {
                main.post(() -> setButtonsEnabled(true));
            }
        });
    }

    private ExecResult runRunner(String args, long timeoutMs) throws Exception {
        stageRunner();
        String cmd = "cd " + shellQuote(ROOT_DIR) + " && " + shellQuote(RUNNER) + " " + args + " 2>&1";
        return execRoot(cmd, timeoutMs);
    }

    private synchronized void stageRunner() throws Exception {
        if (staged) return;
        String abi = primaryAbi();
        if (!"x86_64".equals(abi)) {
            throw new IOException("v0.1 test runner currently supports x86_64 only; device ABI=" + abi);
        }

        String asset = "runner/x86_64/nxlab-runner";
        try (InputStream in = getAssets().open(asset)) {
            ExecResult r = pipeToRoot(
                    "mkdir -p " + shellQuote(ROOT_DIR) +
                            " && cat > " + shellQuote(RUNNER) +
                            " && chmod 0755 " + shellQuote(RUNNER) +
                            " && rm -f " + shellQuote(ROOT_DIR + "/ags.dex") + " " + shellQuote(ROOT_DIR + "/libashmem.so"),
                    in, 25000);
            if (r.code != 0) {
                throw new IOException("runner staging failed: " + r.output);
            }
        } catch (FileNotFoundException e) {
            throw new IOException("runner asset missing for " + abi, e);
        }
        staged = true;
        main.post(() -> status.setText("Root: READY • Runner: STAGED • ABI: " + abi));
    }

    private ExecResult execRoot(String command, long timeoutMs) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = drain(p.getInputStream(), buffer);
        int code = waitFor(p, timeoutMs);
        reader.join(1000);
        return new ExecResult(code, new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim());
    }

    private ExecResult pipeToRoot(String command, InputStream source, long timeoutMs) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = drain(p.getInputStream(), buffer);
        Thread writer = new Thread(() -> {
            try (OutputStream out = p.getOutputStream()) {
                byte[] block = new byte[64 * 1024];
                int n;
                while ((n = source.read(block)) >= 0) {
                    if (n > 0) out.write(block, 0, n);
                }
                out.flush();
            } catch (IOException ignored) {
            }
        }, "nxlab-root-writer");
        writer.start();

        int code;
        try {
            code = waitFor(p, timeoutMs);
        } catch (Exception e) {
            try { p.getOutputStream().close(); } catch (Exception ignored) {}
            p.destroy();
            writer.interrupt();
            throw e;
        }
        writer.join(1000);
        reader.join(1000);
        return new ExecResult(code, new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim());
    }

    private Thread drain(InputStream input, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try (InputStream in = input) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    if (n > 0) out.write(b, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, "nxlab-root-reader");
        t.start();
        return t;
    }

    private int waitFor(Process p, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        for (;;) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                if (SystemClock.uptimeMillis() >= deadline) {
                    p.destroy();
                    throw new IOException("process timed out after " + timeoutMs + " ms");
                }
                Thread.sleep(50);
            }
        }
    }

    private String primaryAbi() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            return Build.SUPPORTED_ABIS[0];
        }
        return Build.CPU_ABI;
    }

    private boolean supportedAbi() {
        return "x86_64".equals(primaryAbi());
    }

    private void setButtonsEnabled(boolean enabled) {
        selfTestButton.setEnabled(enabled);
        tapButton.setEnabled(enabled);
        writeSampleButton.setEnabled(enabled);
        runCustomButton.setEnabled(enabled);
    }

    private void append(String text) {
        if (log == null) return;
        if (log.length() > 0) log.append("\n");
        log.append(text == null ? "" : text);
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private interface Task {
        ExecResult run() throws Exception;
    }

    private static final class ExecResult {
        final int code;
        final String output;
        ExecResult(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }
}
