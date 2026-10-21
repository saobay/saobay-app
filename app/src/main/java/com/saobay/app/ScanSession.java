package com.saobay.app;

import android.graphics.Bitmap;

/** Du lieu tam cua 1 luot quet phieu (truyen giua Grader -> Corner -> Result). */
public class ScanSession {
    public static Bitmap sourcePhoto;          // anh goc da xoay dung chieu
    public static OmrEngine.Gray prepGray;     // anh xam da thu nho <=1600
    public static float prepScale = 1f;        // prep px / photo px
    public static OmrEngine.Gray warped;       // anh da nan 1000x1414
    public static Bitmap warpedBitmap;         // warped dang Bitmap de hien/ve overlay
    public static OmrEngine.Det det;           // ket qua nhan dien
    public static SheetTypes.Type sheetType;
    public static String examId = "", examName = "";
    public static float[][] initialCorners;    // diem goi y cho man chinh goc (toa do anh goc)
    public static float[][] cornerPtsPhoto;    // diem GV da chot (toa do anh goc, TL TR BL BR)

    public static void clear() {
        sourcePhoto = null;
        prepGray = null;
        warped = null;
        warpedBitmap = null;
        det = null;
        initialCorners = null;
        cornerPtsPhoto = null;
    }
}
