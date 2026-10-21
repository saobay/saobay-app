package com.saobay.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Kho de cham cua GV — doc thang tu web truong dung cac duong omr-grader
 * web dang dung (khong can token, chi can uid cua GV dang dang nhap):
 *   data/bank3/<uid>/omr-exams/index.json  -> {exams:[{examId, examName, versions}]}
 *   data/bank3/<uid>/omr-exams/<examId>.json -> {examName, versions:[{code, p1|answers, p2, p3, points}]}
 * Khong co trong bank3 cua GV thi thu kho chung cua truong: data/omr-exams/.
 * Tai ve luu trong may -> mat mang van cham duoc.
 */
public class ExamStore {
    public static class ExamMeta {
        public String id, name;
        public int versions;
        public boolean schoolWide;
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "exams");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File indexFile(Context ctx) { return new File(dir(ctx), "index_cache.json"); }
    private static File examFile(Context ctx, String id) { return new File(dir(ctx), "exam_" + id + ".json"); }

    static String readFile(File f) {
        try {
            java.io.FileInputStream is = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static void writeFile(File f, String s) {
        try {
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(s.getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {}
    }

    /** Tai danh sach de + tung file de ve may. Tra ve so de tai duoc (am neu loi mang o buoc dau). */
    public static int fetchSync(Context ctx) {
        String uid = Session.uid(ctx);
        List<ExamMeta> metas = new ArrayList<>();
        // 1) Kho rieng cua GV
        try {
            String idx = Api.getText(Api.BASE + "/data/bank3/" + URLEncoder.encode(uid, "UTF-8")
                    + "/omr-exams/index.json");
            parseIndex(idx, false, metas);
        } catch (Exception ignored) {}
        // 2) Kho chung cua truong (de giao bai duoc day thang vao day)
        try {
            String idx = Api.getText(Api.BASE + "/data/omr-exams/index.json");
            parseIndex(idx, true, metas);
        } catch (Exception ignored) {}
        if (metas.isEmpty()) return -1;
        // Luu index gop
        try {
            JSONArray arr = new JSONArray();
            for (ExamMeta m : metas) {
                JSONObject o = new JSONObject();
                o.put("id", m.id); o.put("name", m.name); o.put("versions", m.versions);
                o.put("school", m.schoolWide);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("exams", arr);
            writeFile(indexFile(ctx), root.toString());
        } catch (Exception ignored) {}
        // Tai tung file de
        int ok = 0;
        for (ExamMeta m : metas) {
            try {
                String url = m.schoolWide
                        ? Api.BASE + "/data/omr-exams/" + URLEncoder.encode(m.id, "UTF-8") + ".json"
                        : Api.BASE + "/data/bank3/" + URLEncoder.encode(uid, "UTF-8")
                                + "/omr-exams/" + URLEncoder.encode(m.id, "UTF-8") + ".json";
                String detail = Api.getText(url);
                new JSONObject(detail); // kiem tra hop le
                writeFile(examFile(ctx, m.id), detail);
                ok++;
            } catch (Exception ignored) {}
        }
        return ok;
    }

    private static void parseIndex(String idxJson, boolean school, List<ExamMeta> out) {
        try {
            // index.json tren web co 2 dang: mang [...] (ban cu) hoac {exams:[...]}
            JSONArray exams;
            String trimmed = idxJson.trim();
            if (trimmed.startsWith("[")) {
                exams = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                exams = root.optJSONArray("exams");
            }
            if (exams == null) return;
            for (int i = 0; i < exams.length(); i++) {
                JSONObject e = exams.getJSONObject(i);
                String id = e.optString("examId", e.optString("id", ""));
                if (id.isEmpty()) continue;
                boolean dup = false;
                for (ExamMeta m : out) if (m.id.equals(id)) { dup = true; break; }
                if (dup) continue;
                ExamMeta m = new ExamMeta();
                m.id = id;
                m.name = e.optString("examName", e.optString("title", id));
                Object v = e.opt("versions");
                m.versions = v instanceof Number ? ((Number) v).intValue()
                        : v instanceof JSONArray ? ((JSONArray) v).length() : 0;
                m.schoolWide = school;
                out.add(m);
            }
        } catch (Exception ignored) {}
    }

    public static List<ExamMeta> listCached(Context ctx) {
        List<ExamMeta> out = new ArrayList<>();
        String s = readFile(indexFile(ctx));
        if (s == null) return out;
        try {
            JSONObject root = new JSONObject(s);
            JSONArray exams = root.optJSONArray("exams");
            if (exams == null) return out;
            for (int i = 0; i < exams.length(); i++) {
                JSONObject e = exams.getJSONObject(i);
                ExamMeta m = new ExamMeta();
                m.id = e.optString("id");
                m.name = e.optString("name", m.id);
                m.versions = e.optInt("versions", 0);
                m.schoolWide = e.optBoolean("school", false);
                out.add(m);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** File de chi tiet da tai ve (chua co -> null). */
    public static JSONObject loadDetail(Context ctx, String examId) {
        String s = readFile(examFile(ctx, examId));
        if (s == null) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    /** Danh sach ma de (versions) cua 1 de. */
    public static List<String> versionCodes(JSONObject detail) {
        List<String> out = new ArrayList<>();
        if (detail == null) return out;
        JSONArray vs = detail.optJSONArray("versions");
        if (vs == null) return out;
        for (int i = 0; i < vs.length(); i++) {
            JSONObject v = vs.optJSONObject(i);
            if (v != null) out.add(v.optString("code", ""));
        }
        return out;
    }

    public static JSONObject versionOf(JSONObject detail, String code) {
        if (detail == null) return null;
        JSONArray vs = detail.optJSONArray("versions");
        if (vs == null) return null;
        for (int i = 0; i < vs.length(); i++) {
            JSONObject v = vs.optJSONObject(i);
            if (v != null && v.optString("code", "").equals(code)) return v;
        }
        return vs.length() > 0 ? vs.optJSONObject(0) : null;
    }
}
