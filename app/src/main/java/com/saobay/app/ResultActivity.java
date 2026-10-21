package com.saobay.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Man ket qua 1 phieu vua quet: anh da nan + vong mau dung/sai, diem tung phan,
 * sua SBD/ho ten/lop, cham vao tung cau Phan I de sua dap an doc duoc,
 * doi ma de (version) de cham lai ngay. Bam Luu thi ghi vao kho ket qua.
 */
public class ResultActivity extends Activity {
    private OmrEngine.Det det;
    private SheetTypes.Type type;
    private JSONObject detail;
    private OmrEngine.Key key;
    private String curCode = "";
    private OmrEngine.Score score;

    private TextView tvScoreBig, tvScoreParts, tvP2Info, tvP3Info;
    private ImageView ivWarped;
    private Spinner spVersion;
    private EditText etSbd, etName, etClass;
    private GridLayout glAnswers;
    private final List<Button> chips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        det = ScanSession.det;
        type = ScanSession.sheetType;
        if (det == null || type == null || ScanSession.warpedBitmap == null) {
            Toast.makeText(this, "Chưa có dữ liệu quét — hãy chụp phiếu trước.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        tvScoreBig = findViewById(R.id.tvScoreBig);
        tvScoreParts = findViewById(R.id.tvScoreParts);
        tvP2Info = findViewById(R.id.tvP2Info);
        tvP3Info = findViewById(R.id.tvP3Info);
        ivWarped = findViewById(R.id.ivWarped);
        spVersion = findViewById(R.id.spVersion);
        etSbd = findViewById(R.id.etSbd);
        etName = findViewById(R.id.etName);
        etClass = findViewById(R.id.etClass);
        glAnswers = findViewById(R.id.glAnswers);

        detail = ExamStore.loadDetail(this, ScanSession.examId);
        List<String> codes = ExamStore.versionCodes(detail);
        if (codes.isEmpty()) codes.add("");
        spVersion.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, codes));
        int sel = 0;
        if (!det.code.isEmpty()) {
            int idx = codes.indexOf(det.code);
            if (idx >= 0) sel = idx;
        }
        spVersion.setSelection(sel);
        curCode = codes.get(sel);
        spVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                curCode = (String) p.getItemAtPosition(pos);
                reloadKeyAndScore();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        etSbd.setText(det.sbd);
        buildChips();
        findViewById(R.id.btnSaveResult).setOnClickListener(v -> saveResult());
        findViewById(R.id.btnDiscard).setOnClickListener(v -> finish());
        reloadKeyAndScore();
    }

    private void reloadKeyAndScore() {
        JSONObject ver = ExamStore.versionOf(detail, curCode);
        key = OmrEngine.normKey(ver, type);
        rescore();
    }

    // ------------------------------------------------------------ chips Phan I
    private void buildChips() {
        glAnswers.removeAllViews();
        chips.clear();
        String cycle = " ABCD?";
        for (int q = 0; q < type.p1Count; q++) {
            final int qi = q;
            Button b = new Button(this);
            b.setTextSize(12);
            b.setPadding(2, 2, 2, 2);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                String cur = qi < det.p1.length ? det.p1[qi] : "";
                int idx = cycle.indexOf(cur.isEmpty() ? ' ' : cur.charAt(0));
                char next = cycle.charAt((idx + 1) % cycle.length());
                det.p1[qi] = next == ' ' ? "" : String.valueOf(next);
                rescore();
            });
            glAnswers.addView(b);
            chips.add(b);
        }
    }

    private void updateChips() {
        for (int q = 0; q < chips.size(); q++) {
            String a = q < det.p1.length ? det.p1[q] : "";
            String k = key != null && q < key.p1.length ? key.p1[q] : "";
            Button b = chips.get(q);
            b.setText((q + 1) + ":" + (a.isEmpty() ? "–" : a));
            if (!a.isEmpty() && !a.equals("?") && !k.isEmpty()) {
                b.setTextColor(a.equals(k) ? Color.parseColor("#16A34A") : Color.parseColor("#DC2626"));
            } else if ("?".equals(a)) {
                b.setTextColor(Color.parseColor("#D97706"));
            } else {
                b.setTextColor(Color.parseColor("#44403C"));
            }
        }
    }

    // ------------------------------------------------------------ cham lai + ve overlay
    private void rescore() {
        score = OmrEngine.score(det, key, type);
        tvScoreBig.setText("Điểm: " + String.format(Locale.US, "%.2f", score.total));
        StringBuilder sb = new StringBuilder();
        sb.append("Phần I: đúng ").append(score.c1).append(" câu — ")
          .append(String.format(Locale.US, "%.2f", score.p1Score)).append(" điểm");
        if (type.hasP2) {
            sb.append("\nPhần II: ").append(String.format(Locale.US, "%.2f", score.p2Score)).append(" điểm");
        }
        if (type.hasP3) {
            sb.append("\nPhần III: đúng ").append(score.c3).append(" câu — ")
              .append(String.format(Locale.US, "%.2f", score.p3Score)).append(" điểm");
        }
        tvScoreParts.setText(sb.toString());
        // Phan II/III tom tat
        if (type.hasP2 && det.p2.length > 0) {
            StringBuilder t = new StringBuilder("Phần II (Đ/S): ");
            for (int qi = 0; qi < det.p2.length; qi++) {
                if (qi > 0) t.append(" • ");
                t.append("Câu ").append(qi + 1).append(": ");
                for (int j = 0; j < 4; j++) t.append(det.p2[qi][j].isEmpty() ? "–" : det.p2[qi][j]);
                if (score.p2HasKey != null && qi < score.p2HasKey.length && score.p2HasKey[qi]) {
                    t.append(" (đúng ").append(score.p2Right[qi]).append("/4 ý)");
                }
            }
            tvP2Info.setText(t.toString());
        } else {
            tvP2Info.setText("");
        }
        if (type.hasP3 && det.p3.length > 0) {
            StringBuilder t = new StringBuilder("Phần III (tô số): ");
            for (int qi = 0; qi < det.p3.length; qi++) {
                if (qi > 0) t.append(" • ");
                t.append("Câu ").append(qi + 1).append(": ").append(det.p3[qi].isEmpty() ? "(trống)" : det.p3[qi]);
                if (key != null && qi < key.p3.length && !key.p3[qi].isEmpty()) {
                    t.append(score.p3Detail[qi] ? " ✓" : " (đáp án: " + key.p3[qi] + ")");
                }
            }
            tvP3Info.setText(t.toString());
        } else {
            tvP3Info.setText("");
        }
        updateChips();
        drawOverlay();
    }

    private void drawOverlay() {
        Bitmap base = ScanSession.warpedBitmap;
        if (base == null) return;
        Bitmap bmp = base.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(bmp);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(5);
        Paint ringThin = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringThin.setStyle(Paint.Style.STROKE);
        ringThin.setStrokeWidth(4);
        int green = Color.parseColor("#16A34A"), red = Color.parseColor("#DC2626"), orange = Color.parseColor("#D97706");
        // Phan I
        int r1 = type.p1SampleR + 4;
        for (int q = 0; q < type.p1Count; q++) {
            String a = q < det.p1.length ? det.p1[q] : "";
            String k = key != null && q < key.p1.length ? key.p1[q] : "";
            int col = q / type.p1RowsPerCol, row = q % type.p1RowsPerCol;
            float y = type.p1Y0 + row * type.p1RowH;
            for (int kk = 0; kk < 4; kk++) {
                String L = String.valueOf("ABCD".charAt(kk));
                float x = type.p1ColX[col] + type.p1BubDX[kk];
                if (L.equals(a)) {
                    ring.setColor(k.isEmpty() || a.equals(k) ? green : red);
                    ring.setPathEffect(null);
                    c.drawCircle(x, y, r1, ring);
                } else if ("?".equals(a) && kk == 0) {
                    ring.setColor(orange);
                    ring.setPathEffect(new DashPathEffect(new float[]{8, 5}, 0));
                    c.drawCircle(x, y, r1, ring);
                    ring.setPathEffect(null);
                }
            }
            // Danh dau X xanh o o dung khi HS chon sai
            if (!a.isEmpty() && !" ?".contains(a) && !k.isEmpty() && !a.equals(k)) {
                int ki = "ABCD".indexOf(k.charAt(0));
                if (ki >= 0) {
                    float x = type.p1ColX[col] + type.p1BubDX[ki];
                    ring.setColor(green);
                    ring.setStrokeWidth(6);
                    c.drawLine(x - 12, y - 12, x + 12, y + 12, ring);
                    c.drawLine(x + 12, y - 12, x - 12, y + 12, ring);
                    ring.setStrokeWidth(5);
                }
            }
        }
        // Phan II
        if (type.hasP2) {
            int r2 = type.p2SampleR + 4;
            for (int qi = 0; qi < det.p2.length; qi++) {
                int b = qi / 2, j = qi % 2;
                int subX = type.p2ColX[b] + j * type.p2SubW;
                String[] kk = key != null && qi < key.p2.length ? key.p2[qi] : new String[0];
                for (int i = 0; i < 4; i++) {
                    float y = type.p2Y0 + i * type.p2RowH;
                    String a = det.p2[qi][i];
                    String kv = i < kk.length ? kk[i] : "";
                    if ("D".equals(a) || "S".equals(a)) {
                        float x = subX + ("D".equals(a) ? type.p2DDX : type.p2SDX);
                        ringThin.setColor(kv.isEmpty() || a.equals(kv) ? green : red);
                        c.drawCircle(x, y, r2, ringThin);
                    }
                }
            }
        }
        ivWarped.setImageBitmap(bmp);
    }

    // ------------------------------------------------------------ luu
    private void saveResult() {
        try {
            JSONObject r = new JSONObject();
            r.put("examId", ScanSession.examId);
            r.put("examName", ScanSession.examName);
            r.put("code", curCode);
            r.put("sbd", etSbd.getText().toString().trim());
            r.put("studentName", etName.getText().toString().trim());
            r.put("className", etClass.getText().toString().trim());
            r.put("sheetTypeId", type.id);
            r.put("time", System.currentTimeMillis());
            r.put("p1", new JSONArray(java.util.Arrays.asList(det.p1)));
            JSONArray p2 = new JSONArray();
            for (String[] row : det.p2) p2.put(new JSONArray(java.util.Arrays.asList(row)));
            r.put("p2", p2);
            r.put("p3", new JSONArray(java.util.Arrays.asList(det.p3)));
            r.put("totalScore", score.total);
            r.put("correct", score.correct);
            r.put("totalQ", score.totalQ);
            JSONObject parts = new JSONObject();
            parts.put("p1Score", score.p1Score);
            parts.put("p2Score", score.p2Score);
            parts.put("p3Score", score.p3Score);
            parts.put("p1Correct", score.c1);
            r.put("parts", parts);
            r.put("synced", false);
            r.put("teacherUid", Session.uid(this));
            r.put("source", "app-saobay");
            ResultsStore.save(this, r);
            Toast.makeText(this, "Đã lưu kết quả (SBD " + r.optString("sbd") + " — "
                    + String.format(Locale.US, "%.2f", score.total) + " điểm). Chấm bài tiếp theo nhé.", Toast.LENGTH_LONG).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Không lưu được: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
