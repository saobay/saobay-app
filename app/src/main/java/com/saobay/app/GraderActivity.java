package com.saobay.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.media.ExifInterface;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Man hinh cham thi trac nghiem: chon de (da dong bo tu web) + loai phieu,
 * chup/chon anh phieu -> tu tim 4 diem dinh vi -> nan -> nhan dien -> cham.
 * Tim moc khong duoc thi mo man chinh goc bang tay (CornerActivity).
 */
public class GraderActivity extends Activity {
    private static final int REQ_CAMERA = 31, REQ_GALLERY = 32, REQ_CORNERS = 33, REQ_CAM_PERM = 34, REQ_LIVE = 35;

    private Spinner spExam, spSheet;
    private TextView tvExamInfo, tvGradeStatus;
    private ListView lvResults;
    private List<ExamStore.ExamMeta> exams = new ArrayList<>();
    private final SheetTypes.Type[] sheetTypes = SheetTypes.all();
    private Uri pendingPhotoUri;
    private boolean processing = false;

    private SharedPreferences prefs() { return getSharedPreferences("grader", MODE_PRIVATE); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grader);
        NavStrip.build(this, findViewById(R.id.navStripGrader), "grade");
        spExam = findViewById(R.id.spExam);
        spSheet = findViewById(R.id.spSheet);
        tvExamInfo = findViewById(R.id.tvExamInfo);
        tvGradeStatus = findViewById(R.id.tvGradeStatus);
        lvResults = findViewById(R.id.lvResults);

        // Loai phieu
        List<String> sheetNames = new ArrayList<>();
        for (SheetTypes.Type t : sheetTypes) sheetNames.add(t.name);
        spSheet.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sheetNames));
        String savedSheet = prefs().getString("sheet_type", "bgd2025");
        for (int i = 0; i < sheetTypes.length; i++) {
            if (sheetTypes[i].id.equals(savedSheet)) spSheet.setSelection(i);
        }
        spSheet.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs().edit().putString("sheet_type", sheetTypes[pos].id).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        spExam.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < exams.size()) {
                    prefs().edit().putString("exam_id", exams.get(pos).id).apply();
                    updateExamInfo();
                    refreshResults();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        findViewById(R.id.btnLiveScan).setOnClickListener(v -> {
            if (currentExamId().isEmpty()) {
                toast("Chưa có đề nào để chấm — hãy đồng bộ dữ liệu từ web trước.");
                return;
            }
            startActivityForResult(new Intent(this, CaptureActivity.class), REQ_LIVE);
        });
        findViewById(R.id.btnCamera).setOnClickListener(v -> startCamera());
        findViewById(R.id.btnGallery).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_GALLERY);
        });
        findViewById(R.id.btnUploadAll).setOnClickListener(v -> uploadAll());
        findViewById(R.id.btnCsv).setOnClickListener(v -> exportCsv());
        lvResults.setOnItemLongClickListener((parent, view, pos, id) -> {
            List<ResultsStore.Result> rs = ResultsStore.listForExam(this, currentExamId());
            if (pos < 0 || pos >= rs.size()) return false;
            ResultsStore.Result r = rs.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("SBD " + r.sbd + " — " + String.format(java.util.Locale.US, "%.2f", r.totalScore) + " điểm")
                    .setMessage("Mã đề " + r.code + " • Đúng " + r.correct + "/" + r.totalQ
                            + " • Chấm lúc " + ResultsStore.fmtTime(r.time)
                            + (r.raw != null && r.raw.optBoolean("synced", false) ? "\nĐã đẩy lên web ☁️" : "\nChưa đẩy lên web"))
                    .setPositiveButton("Đóng", null)
                    .setNegativeButton("Xoá kết quả này", (d, w) -> {
                        ResultsStore.delete(this, r);
                        refreshResults();
                        toast("Đã xoá kết quả của SBD " + r.sbd);
                    })
                    .show();
            return true;
        });

        reloadExams();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadExams();
        refreshResults();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------ de + loai phieu
    private void reloadExams() {
        List<ExamStore.ExamMeta> now = ExamStore.listCached(this);
        String curId = currentExamId();
        boolean same = now.size() == exams.size();
        if (same) {
            for (int i = 0; i < now.size(); i++) if (!now.get(i).id.equals(exams.get(i).id)) { same = false; break; }
        }
        if (same && !now.isEmpty()) return;
        exams = now;
        List<String> names = new ArrayList<>();
        for (ExamStore.ExamMeta m : exams) {
            names.add(m.name + (m.versions > 0 ? " (" + m.versions + " mã đề)" : "") + (m.schoolWide ? " • kho chung" : ""));
        }
        spExam.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String savedExam = prefs().getString("exam_id", "");
        for (int i = 0; i < exams.size(); i++) {
            if (exams.get(i).id.equals(savedExam)) spExam.setSelection(i);
        }
        updateExamInfo();
    }

    private String currentExamId() {
        int pos = spExam.getSelectedItemPosition();
        if (pos >= 0 && pos < exams.size()) return exams.get(pos).id;
        return prefs().getString("exam_id", "");
    }

    private SheetTypes.Type currentType() {
        int pos = spSheet.getSelectedItemPosition();
        return sheetTypes[Math.max(0, Math.min(sheetTypes.length - 1, pos))];
    }

    private void updateExamInfo() {
        if (exams.isEmpty()) {
            tvExamInfo.setText("Chưa có đề nào trong máy. Hãy về màn hình chính bấm \"🔄 Đồng bộ dữ liệu\" (đề được đẩy từ web: giao bài trong teacher hub là đề tự sang kho chấm).");
            return;
        }
        int pos = spExam.getSelectedItemPosition();
        if (pos < 0 || pos >= exams.size()) return;
        tvExamInfo.setText("Đang chọn: " + exams.get(pos).name + " — chụp phiếu của học sinh làm đề này để chấm.");
    }

    // ------------------------------------------------------------ chup / chon anh
    private void startCamera() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
            return;
        }
        launchCamera();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_CAM_PERM && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else if (req == REQ_CAM_PERM) {
            toast("Chưa có quyền camera — hãy cấp quyền camera cho app Sao Bay trong Cài đặt máy.");
        }
    }

    private void launchCamera() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "phieu_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SaoBay");
            }
            pendingPhotoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            toast("Không mở được camera: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int resultCode, Intent data) {
        super.onActivityResult(req, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (req == REQ_CAMERA && pendingPhotoUri != null) {
            Bitmap bmp = loadBitmap(pendingPhotoUri);
            if (bmp != null) processPhoto(bmp);
            else toast("Không đọc được ảnh vừa chụp.");
        } else if (req == REQ_GALLERY && data != null && data.getData() != null) {
            Bitmap bmp = loadBitmap(data.getData());
            if (bmp != null) processPhoto(bmp);
            else toast("Không đọc được ảnh đã chọn.");
        } else if (req == REQ_LIVE) {
            // Man quet truc tiep tra ve anh da cat trong khung A4 — chay dung luong cham san co
            Bitmap bmp = ScanSession.liveCapture;
            ScanSession.liveCapture = null;
            if (bmp != null) processPhoto(bmp);
            else toast("Chưa có ảnh quét trực tiếp.");
        } else if (req == REQ_CORNERS) {
            if (ScanSession.cornerPtsPhoto != null && ScanSession.prepGray != null) {
                warpAndDetect(ScanSession.cornerPtsPhoto);
            }
        }
    }

    /** Doc anh tu Uri, thu nho vua phai + xoay theo EXIF. */
    private Bitmap loadBitmap(Uri uri) {
        try {
            int orientation = 0;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {}
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxSide / sample > 2600) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) return null;
            int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap nb = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (nb != bmp) bmp.recycle();
                bmp = nb;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ xu ly anh
    private void processPhoto(Bitmap photo) {
        if (processing) return;
        String examId = currentExamId();
        if (examId.isEmpty()) {
            toast("Chưa có đề nào để chấm — hãy đồng bộ dữ liệu từ web trước.");
            return;
        }
        processing = true;
        ScanSession.clear();
        ScanSession.sourcePhoto = photo;
        ScanSession.examId = examId;
        int pos = spExam.getSelectedItemPosition();
        ScanSession.examName = pos >= 0 && pos < exams.size() ? exams.get(pos).name : examId;
        ScanSession.sheetType = currentType();
        tvGradeStatus.setText("Đang tìm 4 điểm định vị trên phiếu…");
        new Thread(() -> {
            OmrEngine.Gray prep = OmrEngine.toGray(photo, 1600);
            ScanSession.prepGray = prep;
            ScanSession.prepScale = (float) prep.w / photo.getWidth();
            OmrEngine.Markers mk = OmrEngine.findMarkers(prep);
            boolean ratioOk = false;
            if (mk.count == 4) {
                float minX = Math.min(Math.min(mk.pts[0][0], mk.pts[2][0]), Math.min(mk.pts[0][0], mk.pts[2][0]));
                float maxX = Math.max(mk.pts[1][0], mk.pts[3][0]);
                float minY = Math.min(mk.pts[0][1], mk.pts[1][1]);
                float maxY = Math.max(mk.pts[2][1], mk.pts[3][1]);
                double ratio = (maxX - minX) / Math.max(1f, (maxY - minY));
                double exp = (double) SheetTypes.W / SheetTypes.H;
                ratioOk = Math.abs(ratio - exp) / exp <= 0.25;
            }
            if (mk.count == 4 && ratioOk) {
                float[][] photoPts = new float[4][2];
                for (int i = 0; i < 4; i++) {
                    photoPts[i][0] = mk.pts[i][0] / ScanSession.prepScale;
                    photoPts[i][1] = mk.pts[i][1] / ScanSession.prepScale;
                }
                runOnUiThread(() -> warpAndDetect(photoPts));
            } else {
                // Goi y cac diem da thay (neu co) cho man chinh goc
                float[][] init = new float[4][2];
                boolean any = false;
                for (int i = 0; i < 4; i++) {
                    if (mk.pts[i] != null) {
                        init[i][0] = mk.pts[i][0] / ScanSession.prepScale;
                        init[i][1] = mk.pts[i][1] / ScanSession.prepScale;
                        any = true;
                    }
                }
                ScanSession.initialCorners = any ? init : null;
                final int found = mk.count;
                runOnUiThread(() -> {
                    processing = false;
                    tvGradeStatus.setText("Tự tìm thấy " + found + "/4 điểm định vị — hãy chỉnh tay 4 góc.");
                    startActivityForResult(new Intent(this, CornerActivity.class), REQ_CORNERS);
                });
            }
        }).start();
    }

    /** Nan anh theo 4 diem (toa do anh goc) roi nhan dien + mo man ket qua. */
    private void warpAndDetect(float[][] photoPts) {
        final SheetTypes.Type type = ScanSession.sheetType != null ? ScanSession.sheetType : currentType();
        tvGradeStatus.setText("Đang nắn phiếu và nhận diện đáp án…");
        new Thread(() -> {
            float s = ScanSession.prepScale;
            float[] flat = new float[8];
            for (int i = 0; i < 4; i++) {
                flat[2 * i] = photoPts[i][0] * s;
                flat[2 * i + 1] = photoPts[i][1] * s;
            }
            OmrEngine.Gray warped = OmrEngine.warp(ScanSession.prepGray, flat);
            OmrEngine.Det det = OmrEngine.detect(warped, type);
            ScanSession.warped = warped;
            ScanSession.warpedBitmap = OmrEngine.grayToBitmap(warped);
            ScanSession.det = det;
            runOnUiThread(() -> {
                processing = false;
                tvGradeStatus.setText("Đã nhận diện xong — kiểm tra lại SBD/mã đề/điểm trước khi lưu.");
                startActivity(new Intent(this, ResultActivity.class));
            });
        }).start();
    }

    // ------------------------------------------------------------ ket qua
    private void refreshResults() {
        String examId = currentExamId();
        List<ResultsStore.Result> rs = examId.isEmpty() ? new ArrayList<>() : ResultsStore.listForExam(this, examId);
        List<String> rows = new ArrayList<>();
        for (ResultsStore.Result r : rs) {
            boolean synced = r.raw != null && r.raw.optBoolean("synced", false);
            rows.add("SBD " + (r.sbd.isEmpty() ? "?" : r.sbd) + " • Mã " + r.code + " • "
                    + String.format(java.util.Locale.US, "%.2f", r.totalScore) + " điểm (đúng " + r.correct + "/" + r.totalQ + ")"
                    + " • " + ResultsStore.fmtTime(r.time) + (synced ? " • đã đẩy ☁️" : ""));
        }
        if (rows.isEmpty()) rows.add("(chưa chấm bài nào cho đề này)");
        lvResults.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
    }

    private void uploadAll() {
        String examId = currentExamId();
        if (examId.isEmpty()) { toast("Chưa chọn đề."); return; }
        toast("Đang đẩy kết quả lên web…");
        new Thread(() -> {
            List<ResultsStore.Result> rs = ResultsStore.listForExam(this, examId);
            int ok = 0, fail = 0, skipped = 0;
            for (ResultsStore.Result r : rs) {
                if (r.raw != null && r.raw.optBoolean("synced", false)) { skipped++; continue; }
                if (ResultsStore.upload(this, r)) {
                    ResultsStore.markSynced(this, r);
                    ok++;
                } else fail++;
            }
            final int fOk = ok, fFail = fail, fSkip = skipped;
            runOnUiThread(() -> {
                refreshResults();
                toast("Đẩy xong: " + fOk + " bài lên web" + (fSkip > 0 ? ", " + fSkip + " bài đã đẩy trước đó" : "")
                        + (fFail > 0 ? ", " + fFail + " bài lỗi (kiểm tra mạng rồi đẩy lại)" : ""));
            });
        }).start();
    }

    private void exportCsv() {
        String examId = currentExamId();
        if (examId.isEmpty()) { toast("Chưa chọn đề."); return; }
        List<ResultsStore.Result> rs = ResultsStore.listForExam(this, examId);
        if (rs.isEmpty()) { toast("Chưa có kết quả nào để xuất."); return; }
        String csv = ResultsStore.toCsv(rs);
        String fname = "ket-qua-" + examId + ".csv";
        new Thread(() -> {
            String where = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SaoBay");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) os.write(csv.getBytes("UTF-8"));
                        }
                        where = "thư mục Download/SaoBay/" + fname;
                    }
                }
                if (where == null) {
                    java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    java.io.File f = new java.io.File(dir, fname);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(csv.getBytes("UTF-8"));
                    fos.close();
                    where = f.getAbsolutePath();
                }
            } catch (Exception e) {
                where = null;
            }
            final String w = where;
            runOnUiThread(() -> toast(w != null ? "Đã xuất CSV: " + w : "Không xuất được CSV."));
        }).start();
    }
}
