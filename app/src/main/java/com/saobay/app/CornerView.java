package com.saobay.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Khung anh cho phep keo 4 cham ve dung tam 4 o moc o 4 goc phieu.
 * Diem luu theo TOA DO ANH GOC, thu tu: 0 tren-trai, 1 tren-phai,
 * 2 duoi-trai, 3 duoi-phai (o tron).
 */
public class CornerView extends View {
    private Bitmap bmp;
    private final float[][] pts = new float[4][2];
    private boolean hasPts = false;
    private float scale = 1f, offX = 0f, offY = 0f;
    private int dragIdx = -1;

    private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CornerView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        linePaint.setColor(Color.parseColor("#F59E0B"));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        dotPaint.setColor(Color.parseColor("#7C3AED"));
        dotPaint.setStyle(Paint.Style.FILL);
        dotStroke.setColor(Color.WHITE);
        dotStroke.setStyle(Paint.Style.STROKE);
        dotStroke.setStrokeWidth(4f);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(30f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
    }

    public void setBitmap(Bitmap b, float[][] initial) {
        bmp = b;
        if (initial != null && initial.length == 4) {
            for (int i = 0; i < 4; i++) {
                pts[i][0] = initial[i][0];
                pts[i][1] = initial[i][1];
            }
            hasPts = true;
        } else {
            resetPoints();
        }
        computeFit();
        invalidate();
    }

    public Bitmap bitmap() { return bmp; }

    public void resetPoints() {
        if (bmp == null) return;
        float w = bmp.getWidth(), h = bmp.getHeight();
        pts[0] = new float[]{w * 0.08f, h * 0.05f};
        pts[1] = new float[]{w * 0.92f, h * 0.05f};
        pts[2] = new float[]{w * 0.08f, h * 0.95f};
        pts[3] = new float[]{w * 0.92f, h * 0.95f};
        hasPts = true;
        invalidate();
    }

    public float[][] getPoints() {
        float[][] out = new float[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = pts[i][0];
            out[i][1] = pts[i][1];
        }
        return out;
    }

    private void computeFit() {
        if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
        scale = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        offX = (getWidth() - bmp.getWidth() * scale) / 2f;
        offY = (getHeight() - bmp.getHeight() * scale) / 2f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeFit();
    }

    private float vx(float ix) { return offX + ix * scale; }
    private float vy(float iy) { return offY + iy * scale; }
    private float ix(float vx) { return (vx - offX) / scale; }
    private float iy(float vy) { return (vy - offY) / scale; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bmp == null) return;
        computeFit();
        canvas.drawBitmap(bmp, null, new android.graphics.RectF(offX, offY,
                offX + bmp.getWidth() * scale, offY + bmp.getHeight() * scale), imgPaint);
        if (!hasPts) return;
        // Khung noi 4 diem: cac canh 0-1, 1-3, 3-2, 2-0
        canvas.drawLine(vx(pts[0][0]), vy(pts[0][1]), vx(pts[1][0]), vy(pts[1][1]), linePaint);
        canvas.drawLine(vx(pts[1][0]), vy(pts[1][1]), vx(pts[3][0]), vy(pts[3][1]), linePaint);
        canvas.drawLine(vx(pts[3][0]), vy(pts[3][1]), vx(pts[2][0]), vy(pts[2][1]), linePaint);
        canvas.drawLine(vx(pts[2][0]), vy(pts[2][1]), vx(pts[0][0]), vy(pts[0][1]), linePaint);
        float r = 30f;
        for (int i = 0; i < 4; i++) {
            float x = vx(pts[i][0]), y = vy(pts[i][1]);
            canvas.drawCircle(x, y, r, dotPaint);
            canvas.drawCircle(x, y, r, dotStroke);
            canvas.drawText(String.valueOf(i + 1), x, y + 11f, labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null || !hasPts) return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                float best = 90f;
                dragIdx = -1;
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(e.getX() - vx(pts[i][0]), e.getY() - vy(pts[i][1]));
                    if (d < best) { best = d; dragIdx = i; }
                }
                return dragIdx >= 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragIdx < 0) return false;
                float nx = ix(e.getX()), ny = iy(e.getY());
                pts[dragIdx][0] = Math.max(0, Math.min(bmp.getWidth() - 1, nx));
                pts[dragIdx][1] = Math.max(0, Math.min(bmp.getHeight() - 1, ny));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragIdx = -1;
                return true;
            default:
                return false;
        }
    }
}
