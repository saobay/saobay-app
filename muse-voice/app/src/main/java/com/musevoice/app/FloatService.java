package com.musevoice.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Nut noi 📸 lo lung tren moi app: cham vao -> chup man hinh app dang xem
 * (qua MediaProjection, thay cap quyen 1 lan luc bat) -> mo lai Muse Voice
 * va dan anh vao khung soan cua luong chat dang mo do (chinh/phu deu duoc,
 * vi WebView khong dieu huong di dau ca trong luc app an xuong).
 * Keo tha de di chuyen nut; nut tu an trong luc chup de khong dinh vao anh.
 */
public class FloatService extends Service {
    public static boolean running = false;

    private static final String CHANNEL = "musevoice_float";
    private static final int NOTIF_ID = 1001;

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams params;
    private MediaProjection projection;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean capturing = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
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
        // Foreground service kieu mediaProjection (Android 14+ bat buoc
        // khai dung kieu + goi startForeground truoc khi tao projection)
        createChannel();
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { stopSelf(); return START_NOT_STICKY; }
        projection = mpm.getMediaProjection(resultCode, data);
        // Android 14+: phai dang ky callback truoc khi tao VirtualDisplay;
        // nguoi dung thu hoi quyen thi onStop goi -> dung dich vu gon gang.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, ui);
        if (bubble == null) createBubble();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Nút nổi Muse Voice", NotificationManager.IMPORTANCE_LOW));
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
        return b.setContentTitle("Muse Voice — nút nổi đang bật")
                .setContentText("Chạm nút 📸 nổi để chụp màn hình app đang xem và gửi vào khung soạn Muse.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .build();
    }

    private void createBubble() {
        bubble = new TextView(this);
        bubble.setText("📸");
        bubble.setTextSize(26);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#7C3AED"));
        bg.setStroke(3, Color.WHITE);
        bubble.setBackground(bg);
        bubble.setAlpha(0.92f);

        int size = dp(60);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        params.x = m.widthPixels - size - dp(10);
        params.y = dp(220);

        bubble.setOnTouchListener(new View.OnTouchListener() {
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
                            try { wm.updateViewLayout(bubble, params); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged && System.currentTimeMillis() - downAt < 500) onBubbleTap();
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(bubble, params);
    }

    private void onBubbleTap() {
        if (capturing || projection == null) return;
        capturing = true;
        // An nut noi truoc khi chup de nut khong dinh vao anh
        bubble.setVisibility(View.INVISIBLE);
        ui.postDelayed(this::captureOnce, 300);
    }

    private void captureOnce() {
        ImageReader reader = null;
        try {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
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
                    deliver(bmp);
                } catch (Exception e) {
                    finishCapture(fr, holder[0]);
                    failCapture();
                }
            }, ui);
            holder[0] = projection.createVirtualDisplay(
                    "musevoice-shot", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, ui);
            // Phong truong hop khong co khung hinh nao ve (app khoa man hinh...)
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
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
    }

    private void failCapture() {
        capturing = false;
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Chưa chụp được màn hình — thầy thử lại nhé.", Toast.LENGTH_LONG).show();
    }

    /** Ghi anh ra file roi mo lai Muse Voice, kem duong dan de app tu dan
     *  anh vao khung soan cua luong chat dang mo. */
    private void deliver(Bitmap bmp) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            bmp.recycle();
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) dir = getFilesDir();
            String name = "shot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".jpg";
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(bos.toByteArray());
            fos.close();

            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra("shot_path", f.getAbsolutePath());
            startActivity(open);
        } catch (Exception e) {
            Toast.makeText(this, "Đã chụp nhưng không mở lại được Muse Voice: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        super.onDestroy();
    }
}
