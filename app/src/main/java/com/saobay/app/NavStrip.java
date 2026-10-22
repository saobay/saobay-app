package com.saobay.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dai cong cu CUON NGANG ghim tren cung cac man hinh (giong dai cua app
 * Muse Voice): GV luot qua lai giua Trang chinh / Cham thi / Nhac gio / Web
 * ma khong phai quay ve tung buoc. Man hinh dang o se sang mau cam.
 */
public class NavStrip {

    public static void build(Activity act, LinearLayout container, String current) {
        if (container == null) return;
        container.removeAllViews();
        HorizontalScrollView hsv = new HorizontalScrollView(act);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setBackgroundColor(Color.parseColor("#4C1D95"));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(act, 8);
        row.setPadding(pad, dp(act, 6), pad, dp(act, 6));
        hsv.addView(row);
        container.addView(hsv);
        addChip(act, row, "🏠 Trang chính", "main", current, MainActivity.class);
        addChip(act, row, "📝 Chấm thi", "grade", current, GraderActivity.class);
        addChip(act, row, "⏰ Nhắc giờ", "remind", current, ClassTimesActivity.class);
        addChip(act, row, "🌐 Web", "web", current, WebActivity.class);
    }

    private static void addChip(Activity act, LinearLayout row, String label,
                                String key, String current, Class<?> target) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(act, 18));
        bg.setColor(Color.parseColor(key.equals(current) ? "#D97706" : "#6D28D9"));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(act, 8));
        tv.setLayoutParams(lp);
        if (!key.equals(current)) {
            tv.setOnClickListener(v -> {
                Intent i = new Intent(act, target);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                act.startActivity(i);
            });
        }
        row.addView(tv);
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
