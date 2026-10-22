package com.saobay.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.RectF;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Man quet phieu TRUC TIEP bang camera2 (khong qua intent chup cua he thong):
 *  - Preview truc tiep tu ImageReader YUV_420_888 (mat phang Y = anh xam).
 *  - Lop phu ve khung A4 VIEN XANH + vong tron tai cac o moc bat duoc
 *    va dong dem "Da bat X/4 o moc".
 *  - Moi ~200ms chay phat hien o moc (OmrEngine.detectMarkersFromY — dung
 *    nguyen pipeline cua bo may cham) tren thread rieng.
 *  - Du 4 o moc trong 3 lan kiem tra lien tiep, vi tri lech <= 2% canh anh
 *    -> TU CHUP: cat anh dung vung trong khung A4 roi tra ve GraderActivity
 *    (ScanSession.liveCapture) de chay DUNG luong cham san co (processPhoto).
 *  - Nut "Chup ngay" chay cung duong cat + cham do.
 */
public class CaptureActivity extends Activity {
    private static final int REQ_CAM_PERM = 41;
    private static final int DETECT_MAXDIM = 1000;  // canh dai cua anh xam dung de phat hien truc tiep
    private static final long DETECT_INTERVAL_MS = 200;
    private static final int STABLE_NEED = 3;       // so lan kiem tra lien tiep du 4 o + on dinh

    private ImageView ivPreview;
    private ScanOverlayView overlay;
    private TextView tvStatus;
    private Button btnSnap;

    // Camera2
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private int streamW = 0, streamH = 0;   // kich thuoc frame theo chieu sensor
    private int rotationDeg = 0;            // xoay thuan kim dong ho de anh dung chieu
    private int uw = 0, uh = 0;             // kich thuoc frame sau khi xoay dung chieu

    // Frame moi nhat (moi frame la 1 ban sao rieng, khong bi ghi de -> doc an toan)
    private final Object frameLock = new Object();
    private byte[] yBuf, uBuf, vBuf;
    private int yRS, yPS, uRS, uPS, vRS, vPS;
    private volatile boolean hasFrame = false;

    // Anh xa view <-> anh dung chieu (center-crop giong ImageView CENTER_CROP)
    static class ViewMap {
        final float s, ox, oy;
        ViewMap(int vw, int vh, int iw, int ih) {
            s = Math.max((float) vw / iw, (float) vh / ih);
            ox = (vw - iw * s) / 2f;
            oy = (vh - ih * s) / 2f;
        }
        float toViewX(float ix) { return ix * s + ox; }
        float toViewY(float iy) { return iy * s + oy; }
        float toImageX(float vx) { return (vx - ox) / s; }
        float toImageY(float vy) { return (vy - oy) / s; }
    }

    private volatile ViewMap viewMap;
    private volatile RectF guideRect;

    // Phat hien truc tiep
    private final ExecutorService detectExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean detectBusy = new AtomicBoolean(false);
    private volatile long lastDetectStart = 0;
    private volatile long lastPreviewAt = 0;
    private final Object stateLock = new Object();
    private float[][] prevPts = null;
    private int stableCount = 0;
    private volatile boolean captured = false;

    /** Khung A4 (ti le 1:1.414) trong he toa do view: rong 90% man hinh, cao toi da 78%. */
    static RectF guideRectFor(int vw, int vh) {
        float fw = vw * 0.90f;
        float fh = fw * 1.41421356f;
        float maxH = vh * 0.78f;
        if (fh > maxH) { fh = maxH; fw = fh / 1.41421356f; }
        float left = (vw - fw) / 2f;
        float top = (vh - fh) / 2f - vh * 0.04f;
        if (top < 8f) top = 8f;
        return new RectF(left, top, left + fw, top + fh);
    }

    // ------------------------------------------------------------ vong doi
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ivPreview = findViewById(R.id.ivPreview);
        overlay = findViewById(R.id.overlay);
        tvStatus = findViewById(R.id.tvStatus);
        btnSnap = findViewById(R.id.btnSnap);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSnap.setOnClickListener(v -> {
            if (captured) return;
            if (!hasFrame) {
                Toast.makeText(this, "Camera chưa sẵn sàng…", Toast.LENGTH_SHORT).show();
                return;
            }
            captured = true;
            btnSnap.setEnabled(false);
            tvStatus.setText("Đang cắt ảnh trong khung và chấm…");
            detectExec.execute(this::doCapture);
        });
        overlay.getViewTreeObserver().addOnGlobalLayoutListener(() -> updateMap());

        camThread = new HandlerThread("saobay-cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
        } else {
            openCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_CAM_PERM) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "Chưa có quyền camera — hãy cấp quyền camera cho app Sao Bay trong Cài đặt máy.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        detectExec.shutdownNow();
        if (camThread != null) camThread.quitSafely();
        super.onDestroy();
    }

    private void updateMap() {
        int vw = overlay.getWidth(), vh = overlay.getHeight();
        if (vw == 0 || vh == 0 || uw == 0 || uh == 0) return;
        viewMap = new ViewMap(vw, vh, uw, uh);
        guideRect = guideRectFor(vw, vh);
        overlay.setGuide(guideRect);
    }

    // ------------------------------------------------------------ camera2
    private void openCamera() {
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null) { Toast.makeText(this, "Máy không có camera.", Toast.LENGTH_LONG).show(); finish(); return; }
            String camId = null;
            int sensorOri = 90;
            Size chosen = null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                StreamConfigurationMap scm = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (scm == null) continue;
                camId = id;
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOri = so != null ? so : 90;
                chosen = chooseSize(scm.getOutputSizes(ImageFormat.YUV_420_888));
                break;
            }
            if (camId == null || chosen == null) {
                Toast.makeText(this, "Không tìm được camera sau.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            streamW = chosen.getWidth();
            streamH = chosen.getHeight();
            int disp = getWindowManager().getDefaultDisplay().getRotation();
            int dispDeg = disp == Surface.ROTATION_90 ? 90 : disp == Surface.ROTATION_180 ? 180
                    : disp == Surface.ROTATION_270 ? 270 : 0;
            rotationDeg = (sensorOri - dispDeg + 360) % 360;
            boolean swap = rotationDeg == 90 || rotationDeg == 270;
            uw = swap ? streamH : streamW;
            uh = swap ? streamW : streamH;
            updateMap();

            reader = ImageReader.newInstance(streamW, streamH, ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(this::onImageAvailable, camHandler);
            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cd) { camera = cd; startSession(); }
                @Override public void onDisconnected(CameraDevice cd) { cd.close(); }
                @Override public void onError(CameraDevice cd, int error) {
                    cd.close();
                    runOnUiThread(() -> {
                        Toast.makeText(CaptureActivity.this, "Lỗi camera (" + error + ").", Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            }, camHandler);
        } catch (Exception e) {
            Toast.makeText(this, "Không mở được camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** Chon kich thuoc YUV: canh dai <= 1600, dien tich lon nhat; khong co thi lay canh dai nho nhat. */
    private static Size chooseSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            int mx = Math.max(s.getWidth(), s.getHeight());
            if (mx > 1600) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) {
            if (best == null || Math.max(s.getWidth(), s.getHeight()) < Math.max(best.getWidth(), best.getHeight())) best = s;
        }
        return best;
    }

    private void startSession() {
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            camera.createCaptureSession(Arrays.asList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                s.setRepeatingRequest(b.build(), null, camHandler);
                            } catch (Exception ignored) {}
                            runOnUiThread(() -> tvStatus.setText("Đưa phiếu vào khung xanh, giữ đủ sáng…"));
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> {
                                Toast.makeText(CaptureActivity.this, "Không khởi động được camera.", Toast.LENGTH_LONG).show();
                                finish();
                            });
                        }
                    }, camHandler);
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Không khởi động được camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    // ------------------------------------------------------------ frame ve
    private static byte[] copyPlane(Image.Plane p) {
        ByteBuffer buf = p.getBuffer().duplicate();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private void onImageAvailable(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
        if (img == null) return;
        byte[] ys, us, vs;
        int yrs, yps, urs, ups, vrs, vps;
        try {
            Image.Plane[] planes = img.getPlanes();
            ys = copyPlane(planes[0]);
            us = copyPlane(planes[1]);
            vs = copyPlane(planes[2]);
            yrs = planes[0].getRowStride(); yps = planes[0].getPixelStride();
            urs = planes[1].getRowStride(); ups = planes[1].getPixelStride();
            vrs = planes[2].getRowStride(); vps = planes[2].getPixelStride();
        } finally {
            img.close();
        }
        synchronized (frameLock) {
            yBuf = ys; uBuf = us; vBuf = vs;
            yRS = yrs; yPS = yps; uRS = urs; uPS = ups; vRS = vrs; vPS = vps;
            hasFrame = true;
        }
        long now = SystemClock.uptimeMillis();
        // Preview mau (giai doan: moi ~80ms)
        if (now - lastPreviewAt >= 80) {
            lastPreviewAt = now;
            Bitmap prev = buildPreviewBitmap(ys, us, vs, yrs, yps, urs, ups, vrs, vps);
            if (prev != null) {
                runOnUiThread(() -> ivPreview.setImageBitmap(prev));
            }
        }
        // Phat hien o moc (moi ~200ms, 1 viec 1 luc)
        if (!captured && now - lastDetectStart >= DETECT_INTERVAL_MS && detectBusy.compareAndSet(false, true)) {
            lastDetectStart = now;
            final byte[] ySnap = ys;
            final int rs = yrs, ps = yps;
            detectExec.execute(() -> runDetection(ySnap, rs, ps));
        }
    }

    /** Anh xem truoc mau tu 3 mat phang YUV, thu nho canh dai <= 800, da xoay dung chieu. */
    private Bitmap buildPreviewBitmap(byte[] y, byte[] u, byte[] v,
                                      int yrs, int yps, int urs, int ups, int vrs, int vps) {
        try {
            float s = Math.min(1f, 800f / Math.max(uw, uh));
            int ow = Math.max(1, Math.round(uw * s)), oh = Math.max(1, Math.round(uh * s));
            int[] argb = new int[ow * oh];
            int cw = (streamW + 1) / 2, chh = (streamH + 1) / 2;
            for (int oy = 0; oy < oh; oy++) {
                int uy2 = Math.min(uh - 1, (int) (oy / s));
                for (int ox = 0; ox < ow; ox++) {
                    int ux2 = Math.min(uw - 1, (int) (ox / s));
                    int bx, by;
                    switch (rotationDeg) {
                        case 90:  bx = uy2;               by = streamH - 1 - ux2; break;
                        case 180: bx = streamW - 1 - ux2;  by = streamH - 1 - uy2; break;
                        case 270: bx = streamW - 1 - uy2;  by = ux2;               break;
                        default:  bx = ux2;                by = uy2;               break;
                    }
                    int Y = y[by * yrs + bx * yps] & 0xff;
                    int cx = Math.min(cw - 1, bx / 2), cy = Math.min(chh - 1, by / 2);
                    int ui = cy * urs + cx * ups, vi = cy * vrs + cx * vps;
                    int U = (ui >= 0 && ui < u.length ? u[ui] & 0xff : 128) - 128;
                    int V = (vi >= 0 && vi < v.length ? v[vi] & 0xff : 128) - 128;
                    int R = clamp255((int) (Y + 1.370705f * V));
                    int G = clamp255((int) (Y - 0.698001f * V - 0.337633f * U));
                    int B = clamp255((int) (Y + 1.732446f * U));
                    argb[oy * ow + ox] = 0xff000000 | (R << 16) | (G << 8) | B;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            bmp.setPixels(argb, 0, ow, 0, 0, ow, oh);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(255, v); }

    // ------------------------------------------------------------ phat hien + on dinh
    private void runDetection(byte[] ySnap, int rs, int ps) {
        try {
            OmrEngine.Markers mk = OmrEngine.detectMarkersFromY(
                    ySnap, streamW, streamH, rs, ps, rotationDeg, DETECT_MAXDIM);
            boolean ok4 = mk.count == 4;
            int newStable;
            synchronized (stateLock) {
                if (ok4) {
                    newStable = (stableCount > 0 && prevPts != null && allClose(prevPts, mk.pts))
                            ? stableCount + 1 : 1;
                    prevPts = copyPts(mk.pts);
                } else {
                    newStable = 0;
                    prevPts = null;
                }
                stableCount = newStable;
            }
            // Cap nhat lop phu + trang thai
            ViewMap m = viewMap;
            float[][] vpts = new float[4][];
            if (m != null) {
                for (int i = 0; i < 4; i++) {
                    if (mk.pts[i] != null) vpts[i] = new float[]{m.toViewX(mk.pts[i][0]), m.toViewY(mk.pts[i][1])};
                }
            }
            final float[][] fv = vpts;
            final int cnt = mk.count, stab = newStable;
            runOnUiThread(() -> {
                overlay.setMarkers(fv, cnt, cnt == 4);
                if (!captured) {
                    if (cnt < 4) {
                        tvStatus.setText("Đã bắt " + cnt + "/4 ô mốc — đưa phiếu vào khung xanh, giữ đủ sáng, tránh bóng che góc.");
                    } else if (stab < STABLE_NEED) {
                        tvStatus.setText("Đã bắt 4/4 ô mốc — giữ yên điện thoại…");
                    }
                }
            });
            if (ok4 && newStable >= STABLE_NEED && !captured) {
                captured = true;
                runOnUiThread(() -> {
                    btnSnap.setEnabled(false);
                    tvStatus.setText("Đã bắt đủ 4 ô mốc — đang chấm…");
                });
                doCapture();
            }
        } catch (Exception ignored) {
        } finally {
            detectBusy.set(false);
        }
    }

    private static float[][] copyPts(float[][] pts) {
        float[][] out = new float[4][];
        for (int i = 0; i < 4; i++) if (pts[i] != null) out[i] = new float[]{pts[i][0], pts[i][1]};
        return out;
    }

    /** On dinh: tung o moc lech giua 2 lan kiem tra <= 2% canh dai cua anh. */
    private boolean allClose(float[][] a, float[][] b) {
        double tol = 0.02 * Math.max(uw, uh);
        for (int i = 0; i < 4; i++) {
            if (a[i] == null || b[i] == null) return false;
            double dx = a[i][0] - b[i][0], dy = a[i][1] - b[i][1];
            if (dx * dx + dy * dy > tol * tol) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ chup: cat khung A4 -> tra ve Grader
    private void doCapture() {
        try {
            byte[] ys;
            int rs, ps;
            synchronized (frameLock) {
                if (!hasFrame) return;
                ys = yBuf; rs = yRS; ps = yPS;
            }
            OmrEngine.Gray full = OmrEngine.sampleGrayFromY(ys, streamW, streamH, rs, ps, rotationDeg, 0);
            OmrEngine.Gray use = full;
            ViewMap m = viewMap;
            RectF g = guideRect;
            if (m != null && g != null) {
                int x0 = clampInt(Math.round(m.toImageX(g.left)), 0, full.w - 1);
                int y0 = clampInt(Math.round(m.toImageY(g.top)), 0, full.h - 1);
                int x1 = clampInt(Math.round(m.toImageX(g.right)), x0 + 1, full.w);
                int y1 = clampInt(Math.round(m.toImageY(g.bottom)), y0 + 1, full.h);
                if (x1 - x0 >= 200 && y1 - y0 >= 200) use = cropGray(full, x0, y0, x1, y1);
            }
            Bitmap bmp = OmrEngine.grayToBitmap(use);
            ScanSession.liveCapture = bmp;
            runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception e) {
            captured = false;
            runOnUiThread(() -> {
                btnSnap.setEnabled(true);
                tvStatus.setText("Chưa chụp được — thử lại, đưa phiếu vào khung xanh.");
            });
        }
    }

    private static OmrEngine.Gray cropGray(OmrEngine.Gray g, int x0, int y0, int x1, int y1) {
        int w = x1 - x0, h = y1 - y0;
        OmrEngine.Gray out = new OmrEngine.Gray(w, h);
        for (int y = 0; y < h; y++) {
            System.arraycopy(g.px, (y0 + y) * g.w + x0, out.px, y * w, w);
        }
        return out;
    }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : Math.min(hi, v); }
}
