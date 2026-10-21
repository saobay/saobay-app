package com.saobay.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Phien dang nhap SAOBAY cua GV dang dung app (dung tai khoan web truong). */
public class Session {
    private static final String PREFS = "saobay_session";

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn(Context ctx) {
        return !uid(ctx).isEmpty();
    }

    public static String uid(Context ctx)   { return prefs(ctx).getString("uid", ""); }
    public static String name(Context ctx)  { return prefs(ctx).getString("name", ""); }
    public static String role(Context ctx)  { return prefs(ctx).getString("role", ""); }
    public static String token(Context ctx) { return prefs(ctx).getString("token", ""); }
    public static String phone(Context ctx) { return prefs(ctx).getString("phone", ""); }

    public static boolean isTeacher(Context ctx) {
        String r = role(ctx);
        return "teacher".equals(r) || "admin".equals(r) || "bgh".equals(r);
    }

    public static void save(Context ctx, String uid, String name, String role,
                            String token, String phone) {
        prefs(ctx).edit()
                .putString("uid", uid)
                .putString("name", name)
                .putString("role", role)
                .putString("token", token)
                .putString("phone", phone)
                .apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
