package com.musevoice.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Kho lenh tuy chinh do nguoi dung tu tao tren giao dien.
 * Luu bang SharedPreferences (JSON) — dung duoc ngay, khong can tai lai app.
 *
 * Moi lenh gom:
 *   phrase : cau noi kich hoat (vd "mo nhac thu gian")
 *   action : url | send | speak | lock | torch_on | torch_off | vol_up | vol_down
 *   param  : URL (voi url) / noi dung (voi send, speak) / rong (con lai)
 */
public class CommandsStore {

    private static final String PREFS = "muse_voice_custom_commands";
    private static final String KEY = "commands_json";

    public static class Cmd {
        public String phrase;
        public String action;
        public String param;

        public Cmd(String phrase, String action, String param) {
            this.phrase = phrase;
            this.action = action;
            this.param = param == null ? "" : param;
        }

        /** Dang khong dau de so khop voi loi noi da chuyen thanh text. */
        public String phraseNorm() {
            return stripAccents(phrase.toLowerCase(Locale.ROOT)).trim();
        }

        public String actionLabel() {
            switch (action) {
                case "url": return "Mở trang web";
                case "send": return "Gửi cho Muse";
                case "speak": return "Đọc to";
                case "lock": return "Tắt màn hình";
                case "torch_on": return "Bật đèn pin";
                case "torch_off": return "Tắt đèn pin";
                case "vol_up": return "Tăng âm lượng";
                case "vol_down": return "Giảm âm lượng";
                default: return action;
            }
        }
    }

    public static ArrayList<Cmd> load(Context ctx) {
        ArrayList<Cmd> out = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Cmd(o.optString("phrase"), o.optString("action"), o.optString("param")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static void save(Context ctx, ArrayList<Cmd> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Cmd c : list) {
                JSONObject o = new JSONObject();
                o.put("phrase", c.phrase);
                o.put("action", c.action);
                o.put("param", c.param);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static String stripAccents(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }
}
