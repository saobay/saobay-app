package com.saobay.app;

/**
 * Hinh hoc cac loai phieu tra loi — port NGUYEN VAN tu SHEET_TYPES trong
 * teacher/omr-grader.html tren web (SB_OMRSHEET_20261014 / SB_BGDFORM_20261014)
 * de app cham KHOP 1-1 voi phieu da in tu web. Khung chuan W x H = 1000 x 1414,
 * 4 diem dinh vi (marker) o cac goc: 3 o vuong den + 1 vong tron (goc duoi phai),
 * tam cac marker trong khung chuan: (52,52) (948,52) (52,1362) (948,1362).
 */
public class SheetTypes {
    public static final int W = 1000, H = 1414;
    public static final float[] DST = {52, 52, 948, 52, 52, 1362, 948, 1362}; // TL TR BL BR

    public static class Grid {  // luoi so (SBD / ma de): cols cot x 10 hang (so 0-9)
        public int x0, y0, cols, dx, dy, sampleR;
        Grid(int x0, int y0, int cols, int dx, int dy, int sampleR) {
            this.x0 = x0; this.y0 = y0; this.cols = cols; this.dx = dx; this.dy = dy;
            this.sampleR = sampleR;
        }
    }

    public static class Type {
        public String id, name;
        public int codeDigits, sbdDigits;
        public Grid sbd, code;                       // null = loai phieu khong quet duoc muc nay
        // Phan I (trac nghiem ABCD)
        public int p1Count, p1Y0, p1RowH, p1RowsPerCol, p1SampleR;
        public int[] p1ColX, p1BubDX;
        public double p1Points = 0.25;
        // Phan II (dung/sai, chi bgd2025)
        public boolean hasP2;
        public int p2Y0, p2RowH, p2SubW, p2DDX, p2SDX, p2SampleR;
        public int[] p2ColX;
        // Phan III (tra loi ngan to so, chi bgd2025)
        public boolean hasP3;
        public int p3Y0, p3Dy, p3Dx, p3SampleR;
        public int[] p3ColX;
        public double p3Points = 0.5;
    }

    public static Type[] all() {
        return new Type[]{bgd2025(), mcq40(), mcq50()};
    }

    public static Type byId(String id) {
        for (Type t : all()) if (t.id.equals(id)) return t;
        return bgd2025();
    }

    public static Type bgd2025() {
        Type t = new Type();
        t.id = "bgd2025";
        t.name = "Phiếu BGD 2025 (40 TN + 8 Đ/S + 6 TLN)";
        t.codeDigits = 4;
        t.sbdDigits = 8;
        t.sbd = new Grid(672, 248, 8, 25, 26, 7);
        t.code = new Grid(888, 248, 4, 25, 26, 7);
        t.p1Count = 40;
        t.p1Y0 = 545; t.p1RowH = 35; t.p1RowsPerCol = 10;
        t.p1ColX = new int[]{55, 285, 515, 745};
        t.p1BubDX = new int[]{62, 106, 150, 194};
        t.p1SampleR = 9;
        t.hasP2 = true;
        t.p2Y0 = 950; t.p2RowH = 32; t.p2ColX = new int[]{55, 285, 515, 745};
        t.p2SubW = 115; t.p2DDX = 45; t.p2SDX = 85; t.p2SampleR = 7;
        t.hasP3 = true;
        t.p3Y0 = 1140; t.p3Dy = 16; t.p3Dx = 32; t.p3ColX = new int[]{70, 220, 370, 520, 670, 820};
        t.p3SampleR = 6;
        return t;
    }

    public static Type mcq40() {
        Type t = new Type();
        t.id = "mcq40";
        t.name = "Phiếu 40 câu ABCD (kiểm tra thường)";
        t.codeDigits = 3;
        t.sbdDigits = 6;
        t.p1Count = 40;
        t.p1Y0 = 500; t.p1RowH = 42; t.p1RowsPerCol = 20;
        t.p1ColX = new int[]{60, 520};
        t.p1BubDX = new int[]{100, 200, 300, 400};
        t.p1SampleR = 11;
        return t;
    }

    public static Type mcq50() {
        Type t = new Type();
        t.id = "mcq50";
        t.name = "Phiếu 50 câu ABCD (kiểm tra HK / thi thử)";
        t.codeDigits = 3;
        t.sbdDigits = 6;
        t.p1Count = 50;
        t.p1Y0 = 470; t.p1RowH = 36; t.p1RowsPerCol = 25;
        t.p1ColX = new int[]{60, 520};
        t.p1BubDX = new int[]{100, 200, 300, 400};
        t.p1SampleR = 10;
        t.p1Points = 0.2;
        return t;
    }
}
