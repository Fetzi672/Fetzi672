package fac.nxlab.noroot;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScanService extends Service {
    public static final String ACTION_START = "fac.nxlab.noroot.START_SCAN";
    public static final String ACTION_STOP = "fac.nxlab.noroot.STOP_SCAN";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_PROJECTION_DATA = "projection_data";
    public static final String EXTRA_THRESHOLD = "threshold";

    private static final String CHANNEL = "nxlab_noroot_scan";
    private static final int NOTIFY_ID = 2303;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object frameLock = new Object();
    private final ArrayDeque<String> feed = new ArrayDeque<>();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private DisplayManager displayManager;
    private Bitmap latestFrame;
    private long lastFrameAt;
    private int captureWidth;
    private int captureHeight;
    private int captureDensity;
    private double threshold = 0.88;
    private volatile boolean stopping;

    private WindowManager wm;
    private TextView bubble;
    private LinearLayout menu;
    private TextView bottom;
    private TextView center;
    private MatchOverlayView matchView;

    private List<Template> templates = Collections.emptyList();

    private static final String[][] TEMPLATE_SPECS = new String[][]{
            {"settings.png", "Settings"},
            {"white_x.png", "White X"},
            {"chest_1.png", "Chest 1"},
            {"chest_2.png", "Chest 2"},
            {"chest_3.png", "Chest 3"},
            {"chest_4.png", "Chest 4"},
            {"giant_gauntlet.png", "Giant Gauntlet"},
            {"frozen_arrow.png", "Frozen Arrow"},
            {"eternal_tome.png", "Eternal Tome"},
            {"rage_vial.png", "Rage Vial"},
            {"invisibility_vial.png", "Invisibility Vial"},
            {"archer_puppet.png", "Archer Puppet"},
            {"healing_tome.png", "Healing Tome"},
            {"life_gem.png", "Life Gem"}
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) {
            if (!running.get() || stopping) return;
            main.removeCallbacks(reconfigureCapture);
            main.postDelayed(reconfigureCapture, 350);
        }
    };

    private final Runnable reconfigureCapture = new Runnable() {
        @Override public void run() {
            if (!running.get() || projection == null || stopping) return;
            DisplayMetrics dm = realDisplayMetrics();
            if (dm.widthPixels == captureWidth && dm.heightPixels == captureHeight && dm.densityDpi == captureDensity) return;
            status("Display changed → rebuilding capture " + dm.widthPixels + "x" + dm.heightPixels);
            try { setupCapture(dm); }
            catch (Exception e) { error("Capture rebuild failed: " + e.getMessage()); }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        captureThread = new HandlerThread("nxlab-media-projection");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopScan("Stop requested", true);
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction()) || running.get()) return START_NOT_STICKY;

        startForeground(NOTIFY_ID, notification("Waiting for MediaProjection"));
        threshold = clamp(intent.getDoubleExtra(EXTRA_THRESHOLD, 0.88), 0.70, 0.99);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent projectionData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            error("MediaProjection permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            error("Floating overlay permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, projectionData);
            if (projection == null) throw new IllegalStateException("getMediaProjection returned null");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (!stopping) stopScan("Android ended screen capture", false);
                }
            }, main);

            templates = loadTemplates();
            if (templates.isEmpty()) throw new IllegalStateException("No embedded templates found");

            running.set(true);
            stopping = false;
            displayManager.registerDisplayListener(displayListener, main);
            showOverlays();
            setupCapture(realDisplayMetrics());
            center("NON-ROOT CAPTURE READY", 1100);
            status("MediaProjection active • " + templates.size() + " templates • threshold " + fmt(threshold));
            scanner.execute(this::scanLoop);
        } catch (Exception e) {
            error("Start failed: " + e.getMessage());
            stopScan("Start failed", false);
        }
        return START_NOT_STICKY;
    }

    private void setupCapture(DisplayMetrics dm) {
        closeCaptureSurface();
        captureWidth = dm.widthPixels;
        captureHeight = dm.heightPixels;
        captureDensity = dm.densityDpi;
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> consumeFrame(reader), captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "FAC-NX-Lab-NonRoot",
                captureWidth, captureHeight, captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        status("Capture surface " + captureWidth + "x" + captureHeight + " @ " + captureDensity + "dpi");
    }

    private void consumeFrame(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < 220) return;
            lastFrameAt = now;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * captureWidth;
            int paddedWidth = captureWidth + Math.max(0, rowPadding / Math.max(1, pixelStride));

            Bitmap padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);
            Bitmap frame = paddedWidth == captureWidth ? padded : Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
            if (frame != padded) padded.recycle();

            synchronized (frameLock) {
                Bitmap old = latestFrame;
                latestFrame = frame;
                if (old != null && old != frame && !old.isRecycled()) old.recycle();
            }
        } catch (Throwable t) {
            status("Frame decode warning: " + t.getClass().getSimpleName());
        } finally {
            if (image != null) image.close();
        }
    }

    private void scanLoop() {
        int cycle = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Bitmap screen = copyLatestFrame();
            if (screen == null) {
                status("Waiting for first captured frame...");
                sleep(250);
                continue;
            }

            cycle++;
            int sw = screen.getWidth(), sh = screen.getHeight();
            int[] sp = new int[sw * sh];
            screen.getPixels(sp, 0, sw, 0, 0, sw, sh);
            screen.recycle();
            status("Cycle " + cycle + " • frame " + sw + "x" + sh);

            int found = 0;
            for (int i = 0; i < templates.size() && running.get(); i++) {
                Template t = templates.get(i);
                String progress = "Scanning " + (i + 1) + "/" + templates.size() + " • " + t.label;
                status(progress);
                center(progress, 380);
                Match m = findTemplate(sp, sw, sh, t, threshold);
                if (m != null && m.score >= threshold) {
                    found++;
                    final Match fm = m;
                    main.post(() -> {
                        if (matchView != null) matchView.add(fm.x, fm.y, t.w, t.h, t.label, fmt(fm.score));
                    });
                    center("MATCH FOUND • " + t.label, 1200);
                    status("MATCH " + t.label + " @ " + m.x + "," + m.y + " score=" + fmt(m.score));
                }
            }

            if (!running.get()) break;
            if (found == 0) center("NO MATCH • cycle " + cycle, 750);
            else status("Cycle " + cycle + " complete • " + found + " match(es)");
            sleep(650);
        }
        if (!stopping) stopScan("Scanner loop ended", false);
    }

    private Match findTemplate(int[] screen, int sw, int sh, Template t, double required) {
        if (t.w > sw || t.h > sh) return null;
        int maxX = sw - t.w, maxY = sh - t.h;
        int anchorLimit = Math.max(70, (int) ((1.0 - required) * 765.0 * 2.0));
        Match best = null;

        for (int y = 0; y <= maxY && running.get(); y += 2) {
            int row = y * sw;
            for (int x = 0; x <= maxX; x += 2) {
                boolean pass = true;
                for (Anchor a : t.anchors) {
                    int sc = screen[row + a.y * sw + x + a.x];
                    if (colorDiff(sc, a.color) > anchorLimit) { pass = false; break; }
                }
                if (!pass) continue;

                Match local = refine(screen, sw, sh, t, x, y, required);
                if (local != null && (best == null || local.score > best.score)) best = local;
                if (best != null && best.score >= required) return best;
            }
        }
        return best;
    }

    private Match refine(int[] screen, int sw, int sh, Template t, int x, int y, double required) {
        Match best = null;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int rx = x + dx, ry = y + dy;
                if (rx < 0 || ry < 0 || rx + t.w > sw || ry + t.h > sh) continue;
                double s = score(screen, sw, t, rx, ry);
                if (best == null || s > best.score) best = new Match(rx, ry, s);
                if (s >= required) return best;
            }
        }
        return best;
    }

    private double score(int[] screen, int sw, Template t, int ox, int oy) {
        long diff = 0;
        int count = 0;
        int step = t.w * t.h > 2600 ? 2 : 1;
        for (int y = 0; y < t.h; y += step) {
            int sr = (oy + y) * sw + ox;
            int tr = y * t.w;
            for (int x = 0; x < t.w; x += step) {
                int tc = t.pixels[tr + x];
                int alpha = (tc >>> 24) & 0xFF;
                if (alpha < 80) continue;
                diff += colorDiff(screen[sr + x], tc);
                count++;
            }
        }
        if (count == 0) return 0.0;
        double s = 1.0 - (double) diff / ((double) count * 765.0);
        return Math.max(0.0, Math.min(1.0, s));
    }

    private List<Template> loadTemplates() {
        ArrayList<Template> out = new ArrayList<>();
        for (String[] spec : TEMPLATE_SPECS) {
            try (InputStream in = getAssets().open("templates/" + spec[0])) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b == null) continue;
                Bitmap argb = b.getConfig() == Bitmap.Config.ARGB_8888 ? b : b.copy(Bitmap.Config.ARGB_8888, false);
                if (argb != b) b.recycle();
                out.add(new Template(spec[0], spec[1], argb));
            } catch (Exception e) {
                status("Template missing: " + spec[0]);
            }
        }
        return out;
    }

    private static final class Template {
        final String file, label;
        final int w, h;
        final int[] pixels;
        final List<Anchor> anchors;
        Template(String file, String label, Bitmap bitmap) {
            this.file = file; this.label = label; this.w = bitmap.getWidth(); this.h = bitmap.getHeight();
            this.pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            this.anchors = chooseAnchors(pixels, w, h, 6);
            bitmap.recycle();
        }
    }

    private static final class Anchor {
        final int x, y, color;
        Anchor(int x, int y, int color) { this.x = x; this.y = y; this.color = color; }
    }

    private static final class Match {
        final int x, y;
        final double score;
        Match(int x, int y, double score) { this.x = x; this.y = y; this.score = score; }
    }

    private static List<Anchor> chooseAnchors(int[] p, int w, int h, int wanted) {
        long rs = 0, gs = 0, bs = 0, n = 0;
        int sy = Math.max(1, h / 10), sx = Math.max(1, w / 10);
        for (int y = 0; y < h; y += sy) for (int x = 0; x < w; x += sx) {
            int c = p[y * w + x];
            if (((c >>> 24) & 255) < 80) continue;
            rs += (c >>> 16) & 255; gs += (c >>> 8) & 255; bs += c & 255; n++;
        }
        if (n == 0) n = 1;
        final int ar = (int) (rs / n), ag = (int) (gs / n), ab = (int) (bs / n);

        ArrayList<int[]> candidates = new ArrayList<>();
        for (int y = 1; y < h - 1; y += Math.max(1, h / 9)) {
            for (int x = 1; x < w - 1; x += Math.max(1, w / 9)) {
                int c = p[y * w + x];
                if (((c >>> 24) & 255) < 80) continue;
                int d = Math.abs(((c >>> 16) & 255) - ar) + Math.abs(((c >>> 8) & 255) - ag) + Math.abs((c & 255) - ab);
                candidates.add(new int[]{x, y, c, d});
            }
        }
        Collections.sort(candidates, (a, b) -> Integer.compare(b[3], a[3]));
        ArrayList<Anchor> out = new ArrayList<>();
        int separation = Math.max(3, Math.min(w, h) / 5);
        for (int[] c : candidates) {
            boolean ok = true;
            for (Anchor a : out) if (Math.abs(a.x - c[0]) + Math.abs(a.y - c[1]) < separation) { ok = false; break; }
            if (ok) out.add(new Anchor(c[0], c[1], c[2]));
            if (out.size() >= wanted) break;
        }
        if (out.isEmpty()) out.add(new Anchor(w / 2, h / 2, p[(h / 2) * w + w / 2]));
        return out;
    }

    private Bitmap copyLatestFrame() {
        synchronized (frameLock) {
            if (latestFrame == null || latestFrame.isRecycled()) return null;
            return latestFrame.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    private void showOverlays() {
        main.post(() -> {
            if (wm != null) return;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

            matchView = new MatchOverlayView(this);
            WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(-1, -1, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            mlp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(matchView, mlp);

            bottom = new TextView(this);
            bottom.setTextColor(Color.WHITE); bottom.setTextSize(12); bottom.setTypeface(Typeface.MONOSPACE);
            bottom.setPadding(dp(10), dp(8), dp(10), dp(8)); bottom.setBackgroundColor(0xD912171D);
            WindowManager.LayoutParams blp = new WindowManager.LayoutParams(dp(330), -2, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            blp.gravity = Gravity.BOTTOM | Gravity.RIGHT; blp.x = dp(12); blp.y = dp(28);
            wm.addView(bottom, blp);

            center = new TextView(this);
            center.setTextColor(Color.WHITE); center.setTextSize(20); center.setTypeface(Typeface.DEFAULT_BOLD);
            center.setGravity(Gravity.CENTER); center.setPadding(dp(18), dp(12), dp(18), dp(12)); center.setBackgroundColor(0xDD0E1319);
            center.setVisibility(View.GONE);
            WindowManager.LayoutParams clp = new WindowManager.LayoutParams(-2, -2, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            clp.gravity = Gravity.CENTER;
            wm.addView(center, clp);

            bubble = new TextView(this);
            bubble.setText("NX"); bubble.setTextColor(Color.WHITE); bubble.setTextSize(16); bubble.setTypeface(Typeface.DEFAULT_BOLD); bubble.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(0xEEB01824); bg.setStroke(dp(2), Color.WHITE); bubble.setBackground(bg);
            WindowManager.LayoutParams bp = new WindowManager.LayoutParams(dp(58), dp(58), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            bp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL; bp.x = dp(8);
            wm.addView(bubble, bp);
            installBubbleTouch(bp);

            menu = new LinearLayout(this);
            menu.setOrientation(LinearLayout.VERTICAL); menu.setPadding(dp(10), dp(10), dp(10), dp(10)); menu.setBackgroundColor(0xF010141A);
            TextView title = new TextView(this); title.setText("FAC NX Non-Root"); title.setTextColor(Color.WHITE); title.setTextSize(14); title.setTypeface(Typeface.DEFAULT_BOLD);
            menu.addView(title, new LinearLayout.LayoutParams(dp(190), dp(34)));
            Button stop = new Button(this); stop.setText("✕  STOP SCRIPT"); stop.setTextColor(Color.WHITE); stop.setBackgroundColor(0xFFB01824);
            stop.setOnClickListener(v -> stopScan("Stopped from floating control", true));
            menu.addView(stop, new LinearLayout.LayoutParams(dp(190), dp(48)));
            WindowManager.LayoutParams mp = new WindowManager.LayoutParams(dp(210), dp(104), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            mp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL; mp.x = dp(76);
            menu.setVisibility(View.GONE);
            wm.addView(menu, mp);
        });
    }

    private void installBubbleTouch(WindowManager.LayoutParams lp) {
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downY; int startY; boolean moved;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = e.getRawY(); startY = lp.y; moved = false; return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - downY; if (Math.abs(dy) > 8) moved = true;
                        lp.y = startY - (int) dy;
                        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) {}
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (!moved && menu != null) menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                        return true;
                }
                return false;
            }
        });
    }

    private void status(String s) {
        main.post(() -> {
            feed.addLast(s);
            while (feed.size() > 6) feed.removeFirst();
            if (bottom != null) {
                StringBuilder b = new StringBuilder();
                for (String x : feed) { if (b.length() > 0) b.append('\n'); b.append(x); }
                bottom.setText(b.toString());
            }
            updateNotification(s);
        });
    }

    private final Runnable hideCenter = () -> { if (center != null) center.setVisibility(View.GONE); };
    private void center(String s, long ms) {
        main.post(() -> {
            if (center == null) return;
            center.setText(s); center.setVisibility(View.VISIBLE); center.removeCallbacks(hideCenter); center.postDelayed(hideCenter, ms);
        });
    }
    private void error(String s) { status("ERROR: " + s); center("ERROR • " + s, 1800); }

    private synchronized void stopScan(String reason, boolean user) {
        if (stopping) return;
        stopping = true;
        running.set(false);
        status(reason);
        center(user ? "SCRIPT STOPPED" : "CAPTURE ENDED", 900);
        cleanupProjection();
        main.postDelayed(() -> {
            removeOverlays();
            stopForeground(true);
            stopSelf();
        }, 900);
    }

    private void cleanupProjection() {
        try { if (displayManager != null) displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        closeCaptureSurface();
        MediaProjection p = projection; projection = null;
        if (p != null) try { p.stop(); } catch (Exception ignored) {}
        synchronized (frameLock) {
            if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
            latestFrame = null;
        }
    }

    private void closeCaptureSurface() {
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} virtualDisplay = null; }
        if (imageReader != null) { try { imageReader.close(); } catch (Exception ignored) {} imageReader = null; }
    }

    private void removeOverlays() {
        main.post(() -> {
            if (wm == null) return;
            View[] views = new View[]{menu, bubble, bottom, center, matchView};
            for (View v : views) if (v != null) try { wm.removeView(v); } catch (Exception ignored) {}
            wm = null; menu = null; bubble = null; bottom = null; center = null; matchView = null;
        });
    }

    private DisplayMetrics realDisplayMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 17) w.getDefaultDisplay().getRealMetrics(dm); else w.getDefaultDisplay().getMetrics(dm);
        return dm;
    }

    private Notification notification(String s) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("FAC NX Lab Non-Root")
                .setContentText(s.length() > 90 ? s.substring(0, 90) : s)
                .setContentIntent(pi).setOngoing(true).build();
    }

    private void updateNotification(String s) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID, notification(s)); }
        catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "NX Lab Screen Capture", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        running.set(false);
        cleanupProjection();
        removeOverlays();
        scanner.shutdownNow();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    private static int colorDiff(int a, int b) {
        return Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255)) +
                Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)) +
                Math.abs((a & 255) - (b & 255));
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.3f", v); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
