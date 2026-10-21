package com.saobay.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Lich bao giang + cai dat gio tiet cho chuc nang "Nhac gio len lop".
 * Lich tai tu web truong ve luu trong may (mat mang van nhac duoc).
 * Gio tiet TU TINH: tiet 1 bat dau theo cai dat; moi tiet dai period_len;
 * nghi giua cac tiet break_short, rieng sau tiet 2 nghi break_long.
 */
public class ReminderStore {
    /** Lich cua GV dang dang nhap: data/reminders/<uid>.json tren web truong
     *  (GV day len tu muc "Day du lieu sang app Saobay" trong teacher hub). */
    public static String scheduleUrl(Context ctx) {
        return "https://www.saobayhighschool.workers.dev/data/reminders/"
                + Session.uid(ctx) + ".json";
    }
    private static final String PREFS = "reminders";
    private static final String CACHE_FILE = "reminders.json";

    // ---------------------------------------------------------- cai dat
    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getStartStr(Context ctx, String session) {
        return prefs(ctx).getString("sang".equals(session) ? "start_sang" : "start_chieu",
                "sang".equals(session) ? "07:15" : "13:00");
    }

    public static int periodLen(Context ctx)   { return prefs(ctx).getInt("period_len", 45); }
    public static int breakShort(Context ctx)  { return prefs(ctx).getInt("break_short", 5); }
    public static int breakLong(Context ctx)   { return prefs(ctx).getInt("break_long", 15); }
    public static int alertBefore(Context ctx) { return prefs(ctx).getInt("alert_before", 25); }
    public static boolean optPre(Context ctx)   { return prefs(ctx).getBoolean("opt_pre", true); }
    public static boolean optStart(Context ctx) { return prefs(ctx).getBoolean("opt_start", true); }
    public static boolean optSpeak(Context ctx) { return prefs(ctx).getBoolean("opt_speak", true); }

    public static void saveSettings(Context ctx, String startSang, String startChieu,
                                    int len, int bShort, int bLong, int before,
                                    boolean pre, boolean start, boolean speak) {
        prefs(ctx).edit()
                .putString("start_sang", startSang)
                .putString("start_chieu", startChieu)
                .putInt("period_len", len)
                .putInt("break_short", bShort)
                .putInt("break_long", bLong)
                .putInt("alert_before", before)
                .putBoolean("opt_pre", pre)
                .putBoolean("opt_start", start)
                .putBoolean("opt_speak", speak)
                .apply();
    }

    /** "07:15" -> 435 phut. Sai dinh dang -> mac dinh. */
    public static int parseHHmm(String s, int defMin) {
        try {
            String[] p = s.trim().split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            if (h >= 0 && h < 24 && m >= 0 && m < 60) return h * 60 + m;
        } catch (Exception ignored) {}
        return defMin;
    }

    public static String fmtMin(int minutes) {
        return String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60);
    }

    /** Gio bat dau (phut trong ngay) cua tiet `period` trong buoi `session`. */
    public static int periodStartMinutes(Context ctx, String session, int period) {
        int t = parseHHmm(getStartStr(ctx, session), "sang".equals(session) ? 435 : 780);
        int len = periodLen(ctx), bs = breakShort(ctx), bl = breakLong(ctx);
        for (int k = 1; k < period; k++) {
            t += len + (k == 2 ? bl : bs);
        }
        return t;
    }

    // ---------------------------------------------------------- lich (cache trong may)
    public static void saveCache(Context ctx, String json) {
        try {
            FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), CACHE_FILE));
            out.write(json.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {}
    }

    public static JSONObject loadCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;
            InputStream is = new FileInputStream(f);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Tai lich moi tu web truong (chay tren thread nen). Thanh cong -> true. */
    public static boolean fetchSync(Context ctx) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(scheduleUrl(ctx)).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "SaoBayApp");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String json = sb.toString();
            new JSONObject(json); // kiem tra hop le truoc khi ghi de
            saveCache(ctx, json);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------- tiet hoc / bao thuc
    public static class ClassItem {
        public String date, session, subject, clazz, lesson;
        public int period, startMin;
        public ClassItem(String date, String session, int period, String subject,
                         String clazz, String lesson, int startMin) {
            this.date = date; this.session = session; this.period = period;
            this.subject = subject; this.clazz = clazz; this.lesson = lesson;
            this.startMin = startMin;
        }
    }

    public static List<ClassItem> classesOn(Context ctx, String dateStr) {
        List<ClassItem> out = new ArrayList<>();
        JSONObject root = loadCache(ctx);
        if (root == null) return out;
        try {
            JSONObject days = root.optJSONObject("days");
            if (days == null) return out;
            JSONArray arr = days.optJSONArray(dateStr);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String session = o.optString("session", "sang");
                int period = o.optInt("period", 0);
                if (period <= 0) continue;
                out.add(new ClassItem(dateStr, session, period,
                        o.optString("subject", ""), o.optString("class", ""),
                        o.optString("lesson", ""),
                        periodStartMinutes(ctx, session, period)));
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> a.startMin - b.startMin);
        return out;
    }

    public static class Alert {
        public long triggerMillis;
        public int id;
        public String title, text, speak;
    }

    private static long millisOf(String dateStr, int minutes) {
        try {
            String[] p = dateStr.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]),
                    minutes / 60, minutes % 60, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String datePlus(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    /**
     * Danh sach bao thuc trong `daysAhead` ngay toi:
     * - Bao "di lam": truoc alert_before phut cua tiet DAU TIEN thay thuc day
     *   trong moi buoi (tiet dau trong lich cua buoi do, ke ca tiet 3).
     * - Bao "len lop": dung gio bat dau cua TUNG tiet thay day.
     */
    public static List<Alert> buildAlerts(Context ctx, int daysAhead) {
        List<Alert> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        int before = alertBefore(ctx);
        boolean wantPre = optPre(ctx), wantStart = optStart(ctx);
        for (int d = 0; d <= daysAhead; d++) {
            String date = datePlus(d);
            List<ClassItem> items = classesOn(ctx, date);
            // tiet dau cua tung buoi
            java.util.HashSet<String> seenSession = new java.util.HashSet<>();
            for (ClassItem ci : items) {
                long startMs = millisOf(date, ci.startMin);
                if (wantPre && !seenSession.contains(ci.session)) {
                    seenSession.add(ci.session);
                    long trig = startMs - before * 60000L;
                    if (trig > now) {
                        Alert a = new Alert();
                        a.triggerMillis = trig;
                        a.id = (date + ci.session + "pre").hashCode() & 0x7fffffff;
                        a.title = "⏰ Sắp đến giờ đi làm";
                        a.text = before + " phút nữa đến tiết " + ci.period + " — "
                                + ci.subject + " lớp " + ci.clazz + " (" + fmtMin(ci.startMin) + "). "
                                + ci.lesson;
                        a.speak = before + " phút nữa đến tiết " + ci.period
                                + ", " + ci.subject + " lớp " + ci.clazz + ".";
                        out.add(a);
                    }
                }
                if (wantStart && startMs > now) {
                    Alert a = new Alert();
                    a.triggerMillis = startMs;
                    a.id = (date + ci.session + ci.period + "start").hashCode() & 0x7fffffff;
                    a.title = "🔔 Đến giờ lên lớp";
                    a.text = "Tiết " + ci.period + " — " + ci.subject + " lớp " + ci.clazz
                            + ". " + ci.lesson;
                    a.speak = "Đến giờ lên lớp. Tiết " + ci.period + ", "
                            + ci.subject + " lớp " + ci.clazz + ".";
                    out.add(a);
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.triggerMillis, b.triggerMillis));
        return out;
    }
}
