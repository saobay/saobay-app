package com.saobay.app;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Bo may cham OMR chay ngay tren dien thoai — port tu omr-grader web
 * (findSheetMarkers / detectSheet / scoreSheetType). Quy trinh:
 * anh -> xam -> lam mo -> nguong thich ung -> tim 4 diem dinh vi
 * -> nan phoi canh ve 1000x1414 -> lay mau do dam tung o tron -> chon o to.
 * Nguong mac dinh giong web: thFill=45, thGap=20 (thang do dam 0..255).
 */
public class OmrEngine {
    public static final int TH_FILL = 45, TH_GAP = 20;

    // ------------------------------------------------------------ anh xam
    public static class Gray {
        public final int w, h;
        public final int[] px; // 0..255
        Gray(int w, int h) { this.w = w; this.h = h; this.px = new int[w * h]; }
    }

    /** Bitmap -> anh xam, thu nho sao cho canh lon nhat <= maxDim (web: 1600). */
    public static Gray toGray(Bitmap bmp, int maxDim) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        float s = Math.min(1f, (float) maxDim / Math.max(w, h));
        Bitmap b = bmp;
        if (s < 1f) {
            b = Bitmap.createScaledBitmap(bmp, Math.round(w * s), Math.round(h * s), true);
            w = b.getWidth(); h = b.getHeight();
        }
        int[] argb = new int[w * h];
        b.getPixels(argb, 0, w, 0, 0, w, h);
        Gray g = new Gray(w, h);
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int r = (c >> 16) & 0xff, gg = (c >> 8) & 0xff, bl = c & 0xff;
            g.px[i] = (r * 299 + gg * 587 + bl * 114) / 1000;
        }
        if (b != bmp) b.recycle();
        return g;
    }

    /**
     * Dung anh xam truc tiep tu mat phang Y cua frame YUV_420_888 (camera2).
     * Mat phang Y la do sang (he so BT.601 299/587/114 — trung cong thuc toGray)
     * nen anh xam nay dung cho OMR tuong duong anh mau da qua toGray.
     * rotation: goc xoay THUAN chieu kim dong ho de anh dung chieu (0/90/180/270).
     * maxDim: gioi han canh dai nhat cua anh ra (<= 0 = giu do phan giai goc).
     * Dung cho quet truc tiep: phat hien o moc tren anh nho (maxDim ~1000)
     * va cat anh day du luc chup (maxDim <= 0).
     */
    public static Gray sampleGrayFromY(byte[] yPlane, int w, int h,
                                       int rowStride, int pixelStride,
                                       int rotation, int maxDim) {
        boolean swap = rotation == 90 || rotation == 270;
        int uw = swap ? h : w, uh = swap ? w : h;
        float s = maxDim > 0 ? Math.min(1f, (float) maxDim / Math.max(uw, uh)) : 1f;
        int ow = Math.max(1, Math.round(uw * s)), oh = Math.max(1, Math.round(uh * s));
        Gray g = new Gray(ow, oh);
        for (int oy = 0; oy < oh; oy++) {
            int uy = Math.min(uh - 1, (int) (oy / s));
            for (int ox = 0; ox < ow; ox++) {
                int ux = Math.min(uw - 1, (int) (ox / s));
                int bx, by;
                switch (rotation) {
                    case 90:  bx = uy;          by = h - 1 - ux;  break;
                    case 180: bx = w - 1 - ux;  by = h - 1 - uy;  break;
                    case 270: bx = w - 1 - uy;  by = ux;          break;
                    default:  bx = ux;          by = uy;         break;
                }
                int idx = by * rowStride + bx * pixelStride;
                g.px[oy * ow + ox] = idx >= 0 && idx < yPlane.length ? yPlane[idx] & 0xff : 255;
            }
        }
        return g;
    }

    /**
     * Phat hien 4 o moc truc tiep tren frame YUV — dung NGUYEN pipeline
     * findMarkers (mo -> nguong thich ung 51/12 -> thanh phan lien thong ->
     * chon o theo extent/circularity) tren anh xam thu nho toi detectMaxDim,
     * roi quy doi toa do ve he anh dung chieu day du (mk.imgW/imgH = kich
     * thuoc anh dung chieu). pts[] theo thu tu TL TR BL BR, null neu thieu.
     */
    public static Markers detectMarkersFromY(byte[] yPlane, int w, int h,
                                             int rowStride, int pixelStride,
                                             int rotation, int detectMaxDim) {
        Gray g = sampleGrayFromY(yPlane, w, h, rowStride, pixelStride, rotation, detectMaxDim);
        Markers mk = findMarkers(g);
        boolean swap = rotation == 90 || rotation == 270;
        int uw = swap ? h : w, uh = swap ? w : h;
        float fx = (float) uw / g.w, fy = (float) uh / g.h;
        if (fx != 1f || fy != 1f) {
            for (int i = 0; i < 4; i++) {
                if (mk.pts[i] != null) {
                    mk.pts[i][0] *= fx;
                    mk.pts[i][1] *= fy;
                }
            }
        }
        mk.imgW = uw;
        mk.imgH = uh;
        return mk;
    }

    /** Lam mo Gaussian gan dung (kernel 1 4 6 4 1, tach ngang/doc) nhu web. */
    static Gray blur(Gray g) {
        int w = g.w, h = g.h;
        int[] k = {1, 4, 6, 4, 1};
        int[] tmp = new int[w * h];
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0;
                for (int t = -2; t <= 2; t++) {
                    int xx = Math.max(0, Math.min(w - 1, x + t));
                    sum += k[t + 2] * g.px[y * w + xx];
                }
                tmp[y * w + x] = sum / 16;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0;
                for (int t = -2; t <= 2; t++) {
                    int yy = Math.max(0, Math.min(h - 1, y + t));
                    sum += k[t + 2] * tmp[yy * w + x];
                }
                out[y * w + x] = sum / 16;
            }
        }
        Gray r = new Gray(w, h);
        System.arraycopy(out, 0, r.px, 0, out.length);
        return r;
    }

    /** Nguong thich ung (web: adaptiveThreshold GAUSSIAN block 51, C 12,
     *  BINARY_INV). Ban Java dung trung binh cuc bo qua anh tich phan:
     *  diem toi (vat the) khi gray < mean(51x51) - 12. */
    static boolean[] adaptiveDark(Gray g, int block, int cSub) {
        int w = g.w, h = g.h, half = block / 2;
        long[] integ = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += g.px[y * w + x];
                integ[(y + 1) * (w + 1) + (x + 1)] = integ[y * (w + 1) + (x + 1)] + rowSum;
            }
        }
        boolean[] dark = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - half), y1 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - half), x1 = Math.min(w - 1, x + half);
                long sum = integ[(y1 + 1) * (w + 1) + (x1 + 1)] - integ[y0 * (w + 1) + (x1 + 1)]
                        - integ[(y1 + 1) * (w + 1) + x0] + integ[y0 * (w + 1) + x0];
                long cnt = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
                long mean = sum / cnt;
                dark[y * w + x] = g.px[y * w + x] < mean - cSub;
            }
        }
        return dark;
    }

    // ------------------------------------------------------- thanh phan lien thong
    static class Comp {
        double cx, cy;
        int area, perim, x0, y0, x1, y1;
    }

    static List<Comp> components(boolean[] dark, int w, int h) {
        int n = w * h;
        int[] label = new int[n];
        int[] parent = new int[n / 4 + 1024];
        int nLabel = 0;
        // pass 1: gan nhan tam + union-find
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!dark[i]) continue;
                int left = x > 0 ? label[i - 1] : 0;
                int top = y > 0 ? label[i - w] : 0;
                if (left == 0 && top == 0) {
                    if (nLabel + 1 >= parent.length) {
                        int[] np = new int[parent.length * 2];
                        System.arraycopy(parent, 0, np, 0, parent.length);
                        parent = np;
                    }
                    nLabel++;
                    parent[nLabel] = nLabel;
                    label[i] = nLabel;
                } else if (left != 0 && top != 0 && left != top) {
                    int r1 = find(parent, left), r2 = find(parent, top);
                    if (r1 != r2) parent[Math.max(r1, r2)] = Math.min(r1, r2);
                    label[i] = Math.min(r1, r2);
                } else {
                    label[i] = find(parent, left != 0 ? left : top);
                }
            }
        }
        // pass 2: thong ke theo goc union
        java.util.HashMap<Integer, Comp> map = new java.util.HashMap<>();
        java.util.HashMap<Integer, Long> sumX = new java.util.HashMap<>(), sumY = new java.util.HashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!dark[i]) continue;
                int r = find(parent, label[i]);
                Comp c = map.get(r);
                if (c == null) {
                    c = new Comp();
                    c.x0 = c.x1 = x; c.y0 = c.y1 = y;
                    map.put(r, c); sumX.put(r, 0L); sumY.put(r, 0L);
                }
                c.area++;
                sumX.put(r, sumX.get(r) + x);
                sumY.put(r, sumY.get(r) + y);
                if (x < c.x0) c.x0 = x;
                if (x > c.x1) c.x1 = x;
                if (y < c.y0) c.y0 = y;
                if (y > c.y1) c.y1 = y;
                boolean boundary = (x == 0 || !dark[i - 1]) || (x == w - 1 || !dark[i + 1])
                        || (y == 0 || !dark[i - w]) || (y == h - 1 || !dark[i + w]);
                if (boundary) c.perim++;
            }
        }
        List<Comp> out = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Comp> e : map.entrySet()) {
            Comp c = e.getValue();
            if (c.area == 0) continue;
            c.cx = (double) sumX.get(e.getKey()) / c.area;
            c.cy = (double) sumY.get(e.getKey()) / c.area;
            out.add(c);
        }
        return out;
    }

    private static int find(int[] parent, int a) {
        int r = a;
        while (parent[r] != r) r = parent[r];
        while (parent[a] != r) { int nx = parent[a]; parent[a] = r; a = nx; }
        return r;
    }

    // ------------------------------------------------------------ diem dinh vi
    public static class Markers {
        /** Tam 4 marker theo thu tu TL TR BL BR (toa do anh prep), null neu thieu. */
        public float[][] pts = new float[4][];
        public int count = 0;
        public double confidence = 0;
        public int imgW, imgH;
    }

    public static Markers findMarkers(Gray prep) {
        Markers mk = new Markers();
        mk.imgW = prep.w; mk.imgH = prep.h;
        Gray blurred = blur(prep);
        boolean[] dark = adaptiveDark(blurred, 51, 12);
        List<Comp> comps = components(dark, prep.w, prep.h);
        double imgArea = (double) prep.w * prep.h;
        List<Comp> cands = new ArrayList<>();
        for (Comp c : comps) {
            double rel = c.area / imgArea;
            if (rel < 0.0004 || rel > 0.04) continue;
            cands.add(c);
        }
        Comp tl = pick(cands, prep.w, prep.h, false, false, false);
        Comp tr = pick(cands, prep.w, prep.h, true, false, false);
        Comp bl = pick(cands, prep.w, prep.h, false, true, false);
        Comp br = pick(cands, prep.w, prep.h, true, true, true);
        Comp[] chosen = {tl, tr, bl, br};
        int cnt = 0;
        for (int i = 0; i < 4; i++) {
            if (chosen[i] != null) {
                mk.pts[i] = new float[]{(float) chosen[i].cx, (float) chosen[i].cy};
                cnt++;
            }
        }
        mk.count = cnt;
        mk.confidence = confidence(mk, chosen);
        return mk;
    }

    /** Chon ung vien trong 1/4 khung.
     *  SB_MARKERCORNER_20261022 (kiem chung tren phieu BGD den trang chuan thay gui):
     *  tren phieu that TAT CA o vuong den bang nhau (~0.00054 dien tich tuong doi)
     *  nen luat "dien tich lon nhat" bat nham o vuong phu giua phieu. Luat dung:
     *  trong cac o VUONG DAC (extent > 0.80, khong phai tron) cua goc phan tu,
     *  chon o GAN GOC KHUNG ANH NHAT (hoa thi lay dien tich lon hon).
     *  Khong co o vuong dac thi moi quay ve luat cu (dung loai tron/vuong,
     *  dien tich lon nhat). Ap dung ca 4 goc — phieu that goc BR cung la o vuong. */
    private static Comp pick(List<Comp> cands, int w, int h,
                             boolean right, boolean bottom, boolean wantCircle) {
        List<Comp> inQ = new ArrayList<>();
        for (Comp c : cands) {
            if ((c.cx < w / 2.0) == !right && (c.cy < h / 2.0) == !bottom) inQ.add(c);
        }
        if (inQ.isEmpty()) return null;
        double cornerX = right ? w : 0, cornerY = bottom ? h : 0;
        Comp bestSq = null;
        double bestD2 = Double.MAX_VALUE;
        for (Comp c : inQ) {
            if (isCircle(c) || extent(c) <= 0.80) continue;
            double dx = c.cx - cornerX, dy = c.cy - cornerY;
            double d2 = dx * dx + dy * dy;
            if (bestSq == null || d2 < bestD2 - 1e-9
                    || (Math.abs(d2 - bestD2) <= 1e-9 && c.area > bestSq.area)) {
                bestSq = c;
                bestD2 = d2;
            }
        }
        if (bestSq != null) return bestSq;
        List<Comp> pool = new ArrayList<>();
        for (Comp c : inQ) if (isCircle(c) == wantCircle) pool.add(c);
        if (pool.isEmpty()) pool = inQ;
        Comp best = null;
        for (Comp c : pool) if (best == null || c.area > best.area) best = c;
        return best;
    }

    private static double extent(Comp c) {
        double bw = (c.x1 - c.x0 + 1), bh = (c.y1 - c.y0 + 1);
        return c.area / Math.max(1.0, bw * bh);
    }

    /** Vong tron dac: extent ~ pi/4 (0.785) + chu vi tron; o vuong dac extent ~1. */
    private static boolean isCircle(Comp c) {
        if (c.perim <= 0) return false;
        double circ = 4 * Math.PI * c.area / ((double) c.perim * c.perim);
        return extent(c) < 0.90 && circ > 0.62;
    }

    /** Do tin cay khung 0..1 — port markerConfidence cua web. */
    static double confidence(Markers mk, Comp[] chosen) {
        for (Comp c : chosen) if (c == null) return 0;
        double topY = Math.abs(mk.pts[0][1] - mk.pts[1][1]) / mk.imgH;
        double botY = Math.abs(mk.pts[2][1] - mk.pts[3][1]) / mk.imgH;
        double leftX = Math.abs(mk.pts[0][0] - mk.pts[2][0]) / mk.imgW;
        double rightX = Math.abs(mk.pts[1][0] - mk.pts[3][0]) / mk.imgW;
        int[] areas = {chosen[0].area, chosen[1].area, chosen[2].area, chosen[3].area};
        java.util.Arrays.sort(areas);
        double areaRatio = areas[3] / Math.max(1, areas[0]);
        double score = 1 - Math.min(0.4, (topY + botY + leftX + rightX) * 2);
        if (areaRatio > 4) score -= 0.3;
        return Math.max(0, Math.min(1, score));
    }

    // ------------------------------------------------------------ nan phoi canh
    /** Giai he 8x8 tim homography src->dst (4 cap diem, h33=1). */
    static double[] homography(float[] src, float[] dst) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[2 * i], y = src[2 * i + 1];
            double X = dst[2 * i], Y = dst[2 * i + 1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -X * x, -X * y, X};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -Y * x, -Y * y, Y};
        }
        for (int col = 0, row = 0; col < 8 && row < 8; col++, row++) {
            int piv = row;
            for (int r = row; r < 8; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            double[] tmp = a[row]; a[row] = a[piv]; a[piv] = tmp;
            double d = a[row][col];
            if (Math.abs(d) < 1e-12) continue;
            for (int r = 0; r < 8; r++) {
                if (r == row) continue;
                double f = a[r][col] / d;
                for (int c = col; c < 9; c++) a[r][c] -= f * a[row][c];
            }
        }
        double[] h = new double[8];
        for (int i = 0; i < 8; i++) h[i] = a[i][8] / (Math.abs(a[i][i]) < 1e-12 ? 1e-12 : a[i][i]);
        return h;
    }

    /** Nan anh xam nguon ve khung chuan 1000x1414 theo 4 tam marker (TL TR BL BR). */
    public static Gray warp(Gray src, float[] srcPts) {
        double[] hInv = homography(SheetTypes.DST, srcPts); // dst -> src
        Gray out = new Gray(SheetTypes.W, SheetTypes.H);
        for (int Y = 0; Y < out.h; Y++) {
            for (int X = 0; X < out.w; X++) {
                double den = hInv[6] * X + hInv[7] * Y + 1;
                double sx = (hInv[0] * X + hInv[1] * Y + hInv[2]) / den;
                double sy = (hInv[3] * X + hInv[4] * Y + hInv[5]) / den;
                out.px[Y * out.w + X] = bilinear(src, sx, sy);
            }
        }
        return out;
    }

    private static int bilinear(Gray g, double sx, double sy) {
        if (sx < 0 || sy < 0 || sx > g.w - 1 || sy > g.h - 1) return 255;
        int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
        int x1 = Math.min(g.w - 1, x0 + 1), y1 = Math.min(g.h - 1, y0 + 1);
        double fx = sx - x0, fy = sy - y0;
        double v = g.px[y0 * g.w + x0] * (1 - fx) * (1 - fy)
                + g.px[y0 * g.w + x1] * fx * (1 - fy)
                + g.px[y1 * g.w + x0] * (1 - fx) * fy
                + g.px[y1 * g.w + x1] * fx * fy;
        return (int) Math.round(v);
    }

    public static Bitmap grayToBitmap(Gray g) {
        Bitmap b = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888);
        int[] argb = new int[g.w * g.h];
        for (int i = 0; i < argb.length; i++) {
            int v = g.px[i];
            argb[i] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
        b.setPixels(argb, 0, g.w, 0, 0, g.w, g.h);
        return b;
    }

    // ------------------------------------------------------------ lay mau o tron
    /** Do dam = 255 - sang trung binh trong hinh tron ban kinh r (web sampleDarkest). */
    static double darkness(Gray g, double cx, double cy, int r) {
        int x0 = (int) Math.round(cx), y0 = (int) Math.round(cy);
        long sum = 0;
        int cnt = 0;
        for (int dy = -r; dy <= r; dy++) {
            int dxMax = (int) Math.floor(Math.sqrt(r * r - dy * dy));
            for (int dx = -dxMax; dx <= dxMax; dx++) {
                int x = x0 + dx, y = y0 + dy;
                if (x < 0 || y < 0 || x >= g.w || y >= g.h) continue;
                sum += g.px[y * g.w + x];
                cnt++;
            }
        }
        if (cnt == 0) return 0;
        return 255 - (double) sum / cnt;
    }

    /** Chon o to nhat: -1 = bo trong, -2 = mo ho (2 o gan nhau) — giong pickBest web. */
    static int pickBest(double[] darks) {
        double m1 = -1, m2 = -1;
        int bi = -1;
        for (int k = 0; k < darks.length; k++) {
            if (darks[k] > m1) { m2 = m1; m1 = darks[k]; bi = k; }
            else if (darks[k] > m2) { m2 = darks[k]; }
        }
        if (m1 < TH_FILL) return -1;
        if (m1 - m2 < TH_GAP) return -2;
        return bi;
    }

    // ------------------------------------------------------------ nhan dien phieu
    public static class Det {
        public String sbd = "", code = "";
        public String[] p1 = new String[0];
        public String[][] p2 = new String[0][];
        public String[] p3 = new String[0];
    }

    public static Det detect(Gray warped, SheetTypes.Type t) {
        Det det = new Det();
        // Phan I
        det.p1 = new String[t.p1Count];
        for (int q = 0; q < t.p1Count; q++) {
            int col = q / t.p1RowsPerCol, row = q % t.p1RowsPerCol;
            double[] darks = new double[4];
            for (int k = 0; k < 4; k++) {
                darks[k] = darkness(warped, t.p1ColX[col] + t.p1BubDX[k],
                        t.p1Y0 + row * t.p1RowH, t.p1SampleR);
            }
            int bi = pickBest(darks);
            det.p1[q] = bi >= 0 ? String.valueOf("ABCD".charAt(bi)) : (bi == -2 ? "?" : "");
        }
        // SBD + ma de
        if (t.sbd != null) det.sbd = detectGrid(warped, t.sbd);
        if (t.code != null) det.code = detectGrid(warped, t.code);
        // Phan II (D/S)
        if (t.hasP2) {
            det.p2 = new String[8][4];
            for (int b = 0; b < 4; b++) {
                for (int j = 0; j < 2; j++) {
                    int qi = b * 2 + j;
                    int subX = t.p2ColX[b] + j * t.p2SubW;
                    for (int i = 0; i < 4; i++) {
                        int y = t.p2Y0 + i * t.p2RowH;
                        double dD = darkness(warped, subX + t.p2DDX, y, t.p2SampleR);
                        double dS = darkness(warped, subX + t.p2SDX, y, t.p2SampleR);
                        int bi = pickBest(new double[]{dD, dS});
                        det.p2[qi][i] = bi == 0 ? "D" : bi == 1 ? "S" : (bi == -2 ? "?" : "");
                    }
                }
            }
        }
        // Phan III (to so: cot 0 dau '-', cac cot sau dau ',')
        if (t.hasP3) {
            det.p3 = new String[6];
            for (int q = 0; q < 6; q++) {
                int x0 = t.p3ColX[q];
                StringBuilder sb = new StringBuilder();
                List<String> chars = new ArrayList<>();
                for (int c = 0; c < 4; c++) {
                    int cx = x0 + c * t.p3Dx;
                    List<String> labels = new ArrayList<>();
                    List<Double> darks = new ArrayList<>();
                    if (c == 0) {
                        labels.add("-");
                        darks.add(darkness(warped, cx, t.p3Y0, t.p3SampleR));
                    } else {
                        labels.add(",");
                        darks.add(darkness(warped, cx, t.p3Y0 + t.p3Dy, t.p3SampleR));
                    }
                    int yDig = c == 0 ? t.p3Y0 + t.p3Dy : t.p3Y0 + 2 * t.p3Dy;
                    for (int d = 0; d < 10; d++) {
                        labels.add(String.valueOf(d));
                        darks.add(darkness(warped, cx, yDig + d * t.p3Dy, t.p3SampleR));
                    }
                    double[] da = new double[darks.size()];
                    for (int i = 0; i < da.length; i++) da[i] = darks.get(i);
                    int bi = pickBest(da);
                    chars.add(bi >= 0 ? labels.get(bi) : "");
                }
                int e = chars.size();
                while (e > 0 && chars.get(e - 1).isEmpty()) e--;
                for (int i = 0; i < e; i++) sb.append(chars.get(i));
                det.p3[q] = sb.toString();
            }
        }
        return det;
    }

    private static String detectGrid(Gray warped, SheetTypes.Grid cfg) {
        StringBuilder s = new StringBuilder();
        for (int c = 0; c < cfg.cols; c++) {
            double[] darks = new double[10];
            for (int d = 0; d < 10; d++) {
                darks[d] = darkness(warped, cfg.x0 + c * cfg.dx, cfg.y0 + d * cfg.dy, cfg.sampleR);
            }
            int bi = pickBest(darks);
            if (bi >= 0) s.append(bi);
            else if (bi == -2) s.append('?');
        }
        return s.toString();
    }

    // ------------------------------------------------------------ dap an + cham diem
    /** Chuan hoa cau tra loi ngan: nhat dau tru, bo khoang trang, '.' -> ',' (web normShort). */
    public static String normShort(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u2212\\u2010-\\u2015]", "-")
                .replaceAll("\\s+", "")
                .replace(".", ",");
    }

    public static class Key {
        public String[] p1;
        public String[][] p2 = new String[0][];
        public String[] p3 = new String[0];
        public double pointsP1, pointsP3;
    }

    /** Chuan hoa 1 "version" (ma de) trong file de bank3 thanh Key — port normKey web. */
    public static Key normKey(JSONObject v, SheetTypes.Type t) {
        Key k = new Key();
        k.p1 = new String[t.p1Count];
        for (int i = 0; i < k.p1.length; i++) k.p1[i] = "";
        k.pointsP1 = t.p1Points;
        k.pointsP3 = t.p3Points;
        if (v == null) return k;
        Object src = v.has("p1") ? v.opt("p1") : v.opt("answers");
        fillP1(k.p1, src);
        if (t.hasP2) {
            k.p2 = new String[8][4];
            for (int i = 0; i < 8; i++) for (int j = 0; j < 4; j++) k.p2[i][j] = "";
            JSONObject p2 = v.optJSONObject("p2");
            if (p2 != null) {
                for (int i = 1; i <= 8; i++) {
                    JSONArray arr = p2.optJSONArray(String.valueOf(i));
                    if (arr == null) continue;
                    for (int j = 0; j < 4 && j < arr.length(); j++) {
                        String vv = arr.optString(j, "").toUpperCase();
                        if (vv.equals("T") || vv.equals("D") || vv.equals("Đ")) k.p2[i - 1][j] = "D";
                        else if (vv.equals("F") || vv.equals("S")) k.p2[i - 1][j] = "S";
                    }
                }
            }
        }
        if (t.hasP3) {
            k.p3 = new String[6];
            for (int i = 0; i < 6; i++) k.p3[i] = "";
            JSONObject p3 = v.optJSONObject("p3");
            if (p3 != null) {
                for (int i = 1; i <= 6; i++) {
                    String val = p3.optString(String.valueOf(i), "");
                    if (!val.isEmpty()) k.p3[i - 1] = normShort(val);
                }
            }
        }
        JSONObject pts = v.optJSONObject("points");
        if (pts != null) {
            if (pts.optDouble("p1", 0) > 0) k.pointsP1 = pts.optDouble("p1");
            if (pts.optDouble("p3", 0) > 0) k.pointsP3 = pts.optDouble("p3");
        }
        return k;
    }

    private static void fillP1(String[] out, Object src) {
        if (src instanceof JSONArray) {
            JSONArray a = (JSONArray) src;
            for (int i = 0; i < a.length() && i < out.length; i++) {
                String s = a.optString(i, "").toUpperCase();
                if (!s.isEmpty() && "ABCD".indexOf(s.charAt(0)) >= 0) out[i] = s.substring(0, 1);
            }
        } else if (src instanceof JSONObject) {
            JSONObject o = (JSONObject) src;
            for (int i = 1; i <= out.length; i++) {
                String s = o.optString(String.valueOf(i), "").toUpperCase();
                if (!s.isEmpty() && "ABCD".indexOf(s.charAt(0)) >= 0) out[i - 1] = s.substring(0, 1);
            }
        }
    }

    public static class Score {
        public double total, p1Score, p2Score, p3Score;
        public int correct, totalQ, c1, p2Full, c3;
        public boolean[] p1Detail;
        public int[] p2Right;       // so y dung tung cau Phan II
        public boolean[] p2HasKey;
        public boolean[] p3Detail;
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    /** Cham diem — port scoreSheetType web (thang D/S Phan II: 0/0.1/0.25/0.5/1.0). */
    public static Score score(Det det, Key key, SheetTypes.Type t) {
        Score sc = new Score();
        int n1 = t.p1Count;
        sc.p1Detail = new boolean[n1];
        int c1 = 0;
        for (int i = 0; i < n1; i++) {
            String a = i < det.p1.length ? det.p1[i] : "";
            String kk = i < key.p1.length ? key.p1[i] : "";
            boolean ok = a != null && !a.isEmpty() && !" ?".contains(a) && !"".equals(kk) && a.equals(kk);
            if (ok) c1++;
            sc.p1Detail[i] = ok;
        }
        sc.c1 = c1;
        int p1Keyed = 0;
        for (String s : key.p1) if (s != null && !s.isEmpty()) p1Keyed++;
        sc.p1Score = round2(c1 * key.pointsP1);

        double p2Score = 0;
        int p2Keyed = 0, p2Full = 0;
        double[] tfPts = {0, 0.1, 0.25, 0.5, 1.0};
        sc.p2Right = new int[det.p2.length];
        sc.p2HasKey = new boolean[det.p2.length];
        for (int qi = 0; qi < det.p2.length; qi++) {
            String[] kk = qi < key.p2.length ? key.p2[qi] : new String[0];
            int keyed = 0;
            for (String s : kk) if (s != null && !s.isEmpty()) keyed++;
            boolean hasKey = keyed == 4;
            int nr = 0;
            for (int j = 0; j < 4; j++) {
                String a = det.p2[qi][j];
                String kv = j < kk.length ? kk[j] : "";
                if (a != null && !a.isEmpty() && !" ?".contains(a) && kv != null && !kv.isEmpty() && a.equals(kv)) nr++;
            }
            sc.p2Right[qi] = nr;
            sc.p2HasKey[qi] = hasKey;
            if (hasKey) {
                p2Keyed++;
                if (nr == 4) p2Full++;
                p2Score += tfPts[Math.min(4, nr)];
            }
        }
        sc.p2Score = round2(p2Score);
        sc.p2Full = p2Full;

        double p3Score = 0;
        int c3 = 0, p3Keyed = 0;
        sc.p3Detail = new boolean[det.p3.length];
        for (int qi = 0; qi < det.p3.length; qi++) {
            String kv = qi < key.p3.length ? key.p3[qi] : "";
            if (kv != null && !kv.isEmpty()) p3Keyed++;
            String a = det.p3[qi];
            boolean ok = a != null && !a.isEmpty() && !" ?".contains(a)
                    && kv != null && !kv.isEmpty() && normShort(a).equals(kv);
            if (ok) { c3++; p3Score += key.pointsP3; }
            sc.p3Detail[qi] = ok;
        }
        sc.p3Score = round2(p3Score);
        sc.c3 = c3;

        sc.totalQ = p1Keyed + p2Keyed + p3Keyed;
        sc.correct = c1 + p2Full + c3;
        sc.total = round2(sc.p1Score + p2Score + p3Score);
        return sc;
    }
}
