package com.saobay.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Ket qua cham luu trong may (moi bai 1 file JSON trong files/results),
 * xuat CSV giong dinh dang omr-grader web, va day len web qua
 * POST /api/v2/teacher/upload -> data/bank2/<examId>__<sbd>.json khi GV bam day.
 */
public class ResultsStore {
    public static class Result {
        public String examId, examName, code, sbd, studentName, className, sheetTypeId;
        public long time;
        public double totalScore;
        public int correct, totalQ;
        public JSONObject raw; // ban JSON day du (de day len web / xem lai)
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "results");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String fileName(String examId, String sbd, long time) {
        String safeSbd = (sbd == null || sbd.isEmpty()) ? "khong-sbd" : sbd.replaceAll("[^0-9A-Za-z]", "");
        return examId + "__" + safeSbd + "__" + time + ".json";
    }

    public static void save(Context ctx, JSONObject r) {
        String name = fileName(r.optString("examId"), r.optString("sbd"), r.optLong("time", System.currentTimeMillis()));
        ExamStore.writeFile(new File(dir(ctx), name), r.toString());
    }

    public static List<Result> listForExam(Context ctx, String examId) {
        List<Result> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            String s = ExamStore.readFile(f);
            if (s == null) continue;
            try {
                JSONObject o = new JSONObject(s);
                if (!o.optString("examId").equals(examId)) continue;
                Result r = new Result();
                r.examId = o.optString("examId");
                r.examName = o.optString("examName");
                r.code = o.optString("code");
                r.sbd = o.optString("sbd");
                r.studentName = o.optString("studentName");
                r.className = o.optString("className");
                r.sheetTypeId = o.optString("sheetTypeId");
                r.time = o.optLong("time");
                r.totalScore = o.optDouble("totalScore");
                r.correct = o.optInt("correct");
                r.totalQ = o.optInt("totalQ");
                r.raw = o;
                out.add(r);
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Long.compare(a.time, b.time));
        return out;
    }

    public static void delete(Context ctx, Result r) {
        try {
            new File(dir(ctx), fileName(r.examId, r.sbd, r.time)).delete();
        } catch (Exception ignored) {}
    }

    public static void markSynced(Context ctx, Result r) {
        try {
            r.raw.put("synced", true);
            String name = fileName(r.examId, r.sbd, r.time);
            ExamStore.writeFile(new File(dir(ctx), name), r.raw.toString());
        } catch (Exception ignored) {}
    }

    /** Day 1 ket qua len web (data/bank2). Thanh cong -> true. */
    public static boolean upload(Context ctx, Result r) {
        try {
            String sbd = (r.sbd == null || r.sbd.isEmpty()) ? "khong-sbd" : r.sbd;
            JSONObject content = new JSONObject(r.raw.toString());
            content.remove("synced");
            JSONObject body = new JSONObject();
            body.put("path", "data/bank2/" + r.examId + "__" + sbd + ".json");
            body.put("content", content);
            JSONObject resp = Api.postJson(Api.BASE + "/api/v2/teacher/upload", body);
            return resp != null && (resp.optBoolean("ok", false) || resp.has("path") || resp.has("url"));
        } catch (Exception e) {
            return false;
        }
    }

    /** CSV giong omr-grader web: STT, Ho_ten, Lop, SBD, Ma_de, Diem... + chi tiet tung cau Phan I. */
    public static String toCsv(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("STT,Ho_ten,Lop,SBD,Ma_de,Loai_phieu,So_cau_dung,Diem_10");
        int maxQ = 0;
        for (Result r : results) {
            JSONArray p1 = r.raw != null ? r.raw.optJSONArray("p1") : null;
            if (p1 != null) maxQ = Math.max(maxQ, p1.length());
        }
        for (int i = 1; i <= maxQ; i++) sb.append(",Cau_").append(i);
        sb.append("\n");
        int stt = 1;
        for (Result r : results) {
            sb.append(stt++).append(',')
              .append(csv(r.studentName)).append(',')
              .append(csv(r.className)).append(',')
              .append(csv(r.sbd)).append(',')
              .append(csv(r.code)).append(',')
              .append(csv(r.sheetTypeId)).append(',')
              .append(r.correct).append('/').append(r.totalQ).append(',')
              .append(String.format(Locale.US, "%.2f", r.totalScore));
            JSONArray p1 = r.raw != null ? r.raw.optJSONArray("p1") : null;
            if (p1 != null) {
                for (int i = 0; i < p1.length(); i++) sb.append(',').append(csv(p1.optString(i, "")));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    public static String fmtTime(long t) {
        return new SimpleDateFormat("HH:mm dd/MM", Locale.US).format(new Date(t));
    }
}
