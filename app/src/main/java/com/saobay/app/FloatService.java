package com.saobay.app;

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
 * (qua MediaProjection, cap quyen 1 lan luc bat) -> luu anh vao THU VIEN
 * ANH cua may (Pictures/SaoBay) de dung lai sau (dan vao Zalo, dinh kem
 * bai giang...). Keo tha de di chuyen nut; nut tu an trong luc chup de
 * khong dinh vao anh.
 */
public class FloatService extends Service {
    public static boolean running = false;

    private static final String CHANNEL = "saobay_float";
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
                .setContentText("Chạm nút 📸 nổi để chụp màn hình app đang xem, ảnh lưu vào thư viện ảnh của máy.")
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
                    "saobay-shot", w, h, m.densityDpi,
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
        Toast.makeText(this, "Chưa chụp được màn hình — thử lại nhé.", Toast.LENGTH_LONG).show();
    }

    /** Luu anh vua chup vao thu vien (MediaStore, thu muc Pictures/SaoBay);
     *  may cu (Android < 10) hoac loi MediaStore thi luu vao thu muc rieng
     *  cua app. Xong mo lai Sao Bay kem thong bao. */
    private void deliver(Bitmap bmp) {
        String where = null;
        try {
            String name = "SaoBay_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".jpg";
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/SaoBay");
                cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            }
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("MediaStore tra null");
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("khong mo duoc OutputStream");
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
            os.close();
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear();
                cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
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
