package com.saobay.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Cum nut noi tren moi app (cap quyen 1 lan luc bat):
 *  - 📸 CHUP man hinh app dang xem -> luu vao thu vien anh (Pictures/SaoBay).
 *  - 🔴 GHI man hinh -> quay video (hinh thuan, chua co tieng) -> dung lai
 *    thi luu vao thu vien video (Movies/SaoBay). Dang quay nut doi thanh
 *    ⏹ kem dong ho dem gio.
 * Keo tha de di chuyen ca cum nut; cum nut tu an trong luc chup anh.
 */
public class FloatService extends Service {
    public static boolean running = false;

    private static final String CHANNEL = "saobay_float";
    private static final int NOTIF_ID = 1001;

    private WindowManager windowManager;
    private LinearLayout panel;
    private TextView btnShot, btnRec, tvTimer;
    private WindowManager.LayoutParams params;
    private MediaProjection projection;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean capturing = false;

    // ---- ghi man hinh ----
    private boolean recording = false;
    private boolean recHasMic = false;
    private MediaRecorder recorder;
    private VirtualDisplay recDisplay;
    private ParcelFileDescriptor recPfd;
    private Uri recUri;
    private File recFile;
    private long recStartMs = 0;
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long s = (System.currentTimeMillis() - recStartMs) / 1000;
            tvTimer.setText(String.format(Locale.US, "⏺ %d:%02d", s / 60, s % 60));
            ui.postDelayed(this, 500);
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent != null ? intent.getIntExtra("resultCode", 0) : 0;
        Intent data = intent != null ? (Intent) intent.getParcelableExtra("data") : null;
        if (resultCode == 0 || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannel();
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            // Co quyen mic thi dang ky them kieu microphone (Android 14+ bat buoc
            // de thu tieng tu dich vu chay nen).
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIF_ID, notif, types);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { stopSelf(); return START_NOT_STICKY; }
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, ui);
        if (panel == null) createPanel();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Nút nổi Sao Bay", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Sao Bay — nút nổi đang bật")
                .setContentText("📸 chụp màn hình / 🔴 ghi màn hình, lưu vào thư viện của máy.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .build();
    }

    private TextView makeBubble(String emoji, int sizeDp, int color) {
        TextView tv = new TextView(this);
        tv.setText(emoji);
        tv.setTextSize(sizeDp >= 56 ? 26 : 22);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(3, Color.WHITE);
        tv.setBackground(bg);
        tv.setAlpha(0.94f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        btnShot = makeBubble("📸", 60, Color.parseColor("#7C3AED"));
        btnRec = makeBubble("🔴", 52, Color.parseColor("#991B1B"));
        LinearLayout.LayoutParams recLp = (LinearLayout.LayoutParams) btnRec.getLayoutParams();
        recLp.topMargin = dp(8);
        btnRec.setLayoutParams(recLp);

        tvTimer = new TextView(this);
        tvTimer.setTextColor(Color.WHITE);
        tvTimer.setTextSize(12);
        tvTimer.setGravity(Gravity.CENTER);
        tvTimer.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(Color.parseColor("#CC111827"));
        tbg.setCornerRadius(dp(8));
        tvTimer.setBackground(tbg);
        tvTimer.setVisibility(View.GONE);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(6);
        tvTimer.setLayoutParams(tLp);

        panel.addView(btnShot);
        panel.addView(btnRec);
        panel.addView(tvTimer);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics m = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(m);
        params.x = m.widthPixels - dp(70);
        params.y = dp(220);

        // Cham = bam nut (xac dinh nut theo toa do), keo = di chuyen ca cum
        panel.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private long downAt;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x; startY = params.y;
                        touchX = e.getRawX(); touchY = e.getRawY();
                        downAt = System.currentTimeMillis();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (Math.abs(dx) + Math.abs(dy) > 12) dragged = true;
                        if (dragged) {
                            params.x = startX + dx;
                            params.y = startY + dy;
                            try { windowManager.updateViewLayout(panel, params); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged && System.currentTimeMillis() - downAt < 600) {
                            float y = e.getY();
                            if (y >= btnRec.getTop() && y <= btnRec.getBottom()) onRecTap();
                            else onShotTap();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        windowManager.addView(panel, params);
    }

    // ------------------------------------------------------------ chup man hinh
    private void onShotTap() {
        if (capturing || projection == null) return;
        if (recording) {
            Toast.makeText(this, "Đang ghi màn hình — hãy dừng ghi trước khi chụp.", Toast.LENGTH_LONG).show();
            return;
        }
        capturing = true;
        panel.setVisibility(View.INVISIBLE);
        ui.postDelayed(this::captureOnce, 300);
    }

    private void captureOnce() {
        ImageReader reader = null;
        try {
            DisplayMetrics m = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(m);
            final int w = m.widthPixels, h = m.heightPixels;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            final ImageReader fr = reader;
            final boolean[] done = {false};
            VirtualDisplay[] holder = new VirtualDisplay[1];
            reader.setOnImageAvailableListener(r -> {
                if (done[0]) return;
                Image img;
                try { img = r.acquireLatestImage(); } catch (Exception e) { img = null; }
                if (img == null) return;
                done[0] = true;
                try {
                    Bitmap bmp = imageToBitmap(img, w, h);
                    img.close();
                    finishCapture(fr, holder[0]);
                    deliverShot(bmp);
                } catch (Exception e) {
                    finishCapture(fr, holder[0]);
                    failCapture();
                }
            }, ui);
            holder[0] = projection.createVirtualDisplay(
                    "saobay-shot", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, ui);
            final ImageReader timeoutReader = reader;
            ui.postDelayed(() -> {
                if (!done[0]) {
                    done[0] = true;
                    finishCapture(timeoutReader, holder[0]);
                    failCapture();
                }
            }, 3500);
        } catch (Exception e) {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            failCapture();
        }
    }

    private Bitmap imageToBitmap(Image img, int w, int h) {
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        Bitmap bmp = Bitmap.createBitmap(
                w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (rowPadding > 0) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
            bmp.recycle();
            bmp = cropped;
        }
        int max = Math.max(bmp.getWidth(), bmp.getHeight());
        if (max > 1600) {
            float s = 1600f / max;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    bmp, Math.round(bmp.getWidth() * s), Math.round(bmp.getHeight() * s), true);
            bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }

    private void finishCapture(ImageReader reader, VirtualDisplay vd) {
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        try { reader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
        capturing = false;
        if (panel != null) panel.setVisibility(View.VISIBLE);
    }

    private void failCapture() {
        capturing = false;
        if (panel != null) panel.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Chưa chụp được màn hình — thử lại nhé.", Toast.LENGTH_LONG).show();
    }

    /** Luu anh chup vao thu vien (Pictures/SaoBay), loi thi luu thu muc app. */
    private void deliverShot(Bitmap bmp) {
        String where = null;
        try {
            String name = "SaoBay_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/SaoBay");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("MediaStore tra null");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("khong mo duoc OutputStream");
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
            os.close();
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            }
            where = "thư viện ảnh (Pictures/SaoBay)";
        } catch (Exception e) {
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) dir = getFilesDir();
                String name = "shot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date()) + ".jpg";
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                fos.close();
                where = "thư mục của app: " + f.getAbsolutePath();
            } catch (Exception ignored) {}
        }
        bmp.recycle();
        Toast.makeText(this, where != null
                ? "📸 Đã chụp màn hình — lưu vào " + where
                : "Đã chụp nhưng không lưu được ảnh.", Toast.LENGTH_LONG).show();
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(open); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ ghi man hinh
    private void onRecTap() {
        if (capturing || projection == null) return;
        if (recording) stopRecording(true);
        else startRecording();
    }

    private void startRecording() {
        try {
            DisplayMetrics m = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(m);
            int w = m.widthPixels, h = m.heightPixels;
            float scale = Math.min(1f, 1600f / Math.max(w, h));
            w = Math.round(w * scale) & ~1;
            h = Math.round(h * scale) & ~1;

            String name = "SaoBay_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".mp4";
            recUri = null;
            recPfd = null;
            recFile = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/SaoBay");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                recUri = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (recUri != null) {
                    recPfd = getContentResolver().openFileDescriptor(recUri, "w");
                }
            }
            if (recPfd == null) {
                File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (dir == null) dir = getFilesDir();
                recFile = new File(dir, name);
            }

            boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            recorder = new MediaRecorder();
            if (mic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (recPfd != null) recorder.setOutputFile(recPfd.getFileDescriptor());
            else recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.setVideoSize(w, h);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(6_000_000);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (mic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(128_000);
                recorder.setAudioSamplingRate(44_100);
            }
            recorder.prepare();
            recHasMic = mic;

            recDisplay = projection.createVirtualDisplay(
                    "saobay-rec", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, ui);
            recorder.start();

            recording = true;
            recStartMs = System.currentTimeMillis();
            btnRec.setText("⏹");
            tvTimer.setVisibility(View.VISIBLE);
            tvTimer.setText("⏺ 0:00");
            ui.post(timerTick);
            Toast.makeText(this, recHasMic
                    ? "🔴 Đang ghi màn hình (có tiếng mic)… Chạm ⏹ để dừng và lưu."
                    : "🔴 Đang ghi màn hình KHÔNG tiếng — app chưa được cấp quyền Micro. Mở app Sao Bay cấp quyền để ghi có tiếng.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            cleanupRecorder();
            Toast.makeText(this, "Chưa ghi được màn hình (" + e.getMessage()
                    + "). Nếu vừa chụp ảnh xong, hãy tắt nút nổi rồi bật lại (cấp quyền mới) rồi ghi.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording(boolean save) {
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(timerTick);
        tvTimer.setVisibility(View.GONE);
        btnRec.setText("🔴");
        try {
            if (recorder != null) recorder.stop();
        } catch (Exception e) {
            save = false; // dung qua som -> file hong
        }
        cleanupRecorder();
        if (recUri != null) {
            try {
                if (save) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(recUri, cv, null, null);
                    Toast.makeText(this, "🎬 Đã lưu video vào thư viện (Movies/SaoBay).", Toast.LENGTH_LONG).show();
                } else {
                    getContentResolver().delete(recUri, null, null);
                    Toast.makeText(this, "Đã huỷ đoạn ghi (quá ngắn hoặc lỗi).", Toast.LENGTH_LONG).show();
                }
            } catch (Exception ignored) {}
            recUri = null;
        } else if (recFile != null) {
            if (save) {
                final File f = recFile;
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()},
                        new String[]{"video/mp4"}, null);
                Toast.makeText(this, "🎬 Đã lưu video: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                //noinspection ResultOfMethodCallIgnored
                recFile.delete();
            }
            recFile = null;
        }
    }

    private void cleanupRecorder() {
        try { if (recDisplay != null) recDisplay.release(); } catch (Exception ignored) {}
        recDisplay = null;
        try { if (recorder != null) { recorder.reset(); recorder.release(); } } catch (Exception ignored) {}
        recorder = null;
        try { if (recPfd != null) recPfd.close(); } catch (Exception ignored) {}
        recPfd = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (recording) stopRecording(true);
        if (panel != null && windowManager != null) {
            try { windowManager.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        super.onDestroy();
    }
}
