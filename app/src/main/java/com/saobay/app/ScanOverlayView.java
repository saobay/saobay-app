package com.saobay.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lop phu tren preview cua man quet truc tiep (CaptureActivity):
 * lam toi phan ngoai khung, ve khung A4 VIEN XANH, vong tron tai cac o moc
 * da bat duoc va dong dem "Da bat X/4 o moc" ngay tren khung.
 * Toa do khung/vong tron do CaptureActivity tinh (he toa do view) roi set vao.
 */
public class ScanOverlayView extends View {
    private RectF guide;
    private float[][] markerPts;   // toa do view cua 4 o moc (TL TR BL BR), muc null = chua bat duoc
    private int markerCount = 0;
    private boolean locked = false; // da bat du 4 o va on dinh -> khung sang hon

    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBg = new Paint();

    public ScanOverlayView(Context c) { super(c); init(); }
    public ScanOverlayView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        dimPaint.setColor(Color.argb(110, 0, 0, 0));
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(5f);
        framePaint.setColor(Color.parseColor("#22C55E"));
        markFill.setStyle(Paint.Style.FILL);
        markFill.setColor(Color.parseColor("#22C55E"));
        markRing.setStyle(Paint.Style.STROKE);
        markRing.setStrokeWidth(4f);
        markRing.setColor(Color.WHITE);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(6f, 0, 2, Color.BLACK);
        textBg.setColor(Color.argb(150, 0, 0, 0));
    }

    public void setGuide(RectF r) { guide = r; invalidate(); }

    public void setMarkers(float[][] pts, int count, boolean locked) {
        markerPts = pts;
        markerCount = count;
        this.locked = locked;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (guide == null) return;
        // Toi 4 vung ngoai khung
        cv.drawRect(0, 0, w, guide.top, dimPaint);
        cv.drawRect(0, guide.bottom, w, h, dimPaint);
        cv.drawRect(0, guide.top, guide.left, guide.bottom, dimPaint);
        cv.drawRect(guide.right, guide.top, w, guide.bottom, dimPaint);
        // Vien xanh A4 (+ goc nhan manh)
        framePaint.setColor(locked ? Color.parseColor("#4ADE80") : Color.parseColor("#22C55E"));
        framePaint.setStrokeWidth(locked ? 8f : 5f);
        cv.drawRect(guide, framePaint);
        float cl = Math.min(46f, guide.width() * 0.12f);
        framePaint.setStrokeWidth(10f);
        cv.drawLine(guide.left, guide.top, guide.left + cl, guide.top, framePaint);
        cv.drawLine(guide.left, guide.top, guide.left, guide.top + cl, framePaint);
        cv.drawLine(guide.right, guide.top, guide.right - cl, guide.top, framePaint);
        cv.drawLine(guide.right, guide.top, guide.right, guide.top + cl, framePaint);
        cv.drawLine(guide.left, guide.bottom, guide.left + cl, guide.bottom, framePaint);
        cv.drawLine(guide.left, guide.bottom, guide.left, guide.bottom - cl, framePaint);
        cv.drawLine(guide.right, guide.bottom, guide.right - cl, guide.bottom, framePaint);
        cv.drawLine(guide.right, guide.bottom, guide.right, guide.bottom - cl, framePaint);
        // Vong tron tai o moc da bat duoc
        if (markerPts != null) {
            float r = Math.max(13f, w * 0.018f);
            for (int i = 0; i < 4; i++) {
                if (markerPts[i] == null) continue;
                cv.drawCircle(markerPts[i][0], markerPts[i][1], r, markFill);
                cv.drawCircle(markerPts[i][0], markerPts[i][1], r, markRing);
            }
        }
        // Dong dem o moc phia tren khung
        String s = "Đã bắt " + markerCount + "/4 ô mốc";
        textPaint.setTextSize(Math.max(40f, w * 0.05f));
        float tw = textPaint.measureText(s);
        float ty = Math.max(textPaint.getTextSize() + 18f, guide.top - 26f);
        RectF bg = new RectF((w - tw) / 2f - 22f, ty - textPaint.getTextSize() - 12f,
                (w + tw) / 2f + 22f, ty + 14f);
        cv.drawRoundRect(bg, 16f, 16f, textBg);
        cv.drawText(s, w / 2f, ty, textPaint);
    }
}
