package com.saobay.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.widget.Toast;

/** Man chinh 4 goc phieu bang tay khi app khong tu tim thay diem dinh vi. */
public class CornerActivity extends Activity {
    private CornerView cornerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_corner);
        cornerView = findViewById(R.id.cornerView);
        Bitmap bmp = ScanSession.sourcePhoto;
        if (bmp == null) {
            Toast.makeText(this, "Chưa có ảnh phiếu.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        cornerView.setBitmap(bmp, ScanSession.initialCorners);
        findViewById(R.id.btnCornerReset).setOnClickListener(v -> cornerView.resetPoints());
        findViewById(R.id.btnRotate).setOnClickListener(v -> rotate());
        findViewById(R.id.btnCornerDone).setOnClickListener(v -> {
            ScanSession.cornerPtsPhoto = cornerView.getPoints();
            setResult(RESULT_OK);
            finish();
        });
    }

    private void rotate() {
        Bitmap old = ScanSession.sourcePhoto;
        if (old == null) return;
        Matrix m = new Matrix();
        m.postRotate(90);
        Bitmap nb = Bitmap.createBitmap(old, 0, 0, old.getWidth(), old.getHeight(), m, true);
        ScanSession.sourcePhoto = nb;
        cornerView.setBitmap(nb, null);
        Toast.makeText(this, "Đã xoay ảnh 90° — kéo lại 4 chấm về 4 góc phiếu.", Toast.LENGTH_SHORT).show();
    }
}
