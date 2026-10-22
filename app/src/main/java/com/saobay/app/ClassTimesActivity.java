package com.saobay.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/** Man hinh "Nhac gio len lop": xem tiet hom nay + cai dat gio tiet + tai lich moi. */
public class ClassTimesActivity extends Activity {
    private TextView tvToday, tvNext, tvWeek;
    private EditText etStartSang, etStartChieu, etLen, etBreakShort, etBreakLong, etBefore;
    private CheckBox cbPre, cbStart, cbSpeak;
    // Dai chon ngay Thu 2-Thu 7: mac dinh la hom nay; chon ngay khac chi de
    // XEM TAM (bien thuong, khong luu) -> thoat ra vao lai van ve hom nay.
    private final Button[] dayBtns = new Button[6];
    private int selectedDow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reminders);
        NavStrip.build(this, findViewById(R.id.navStripRemind), "remind");

        tvToday = findViewById(R.id.tvToday);
        tvNext = findViewById(R.id.tvNext);
        tvWeek = findViewById(R.id.tvWeek);
        etStartSang = findViewById(R.id.etStartSang);
        etStartChieu = findViewById(R.id.etStartChieu);
        etLen = findViewById(R.id.etLen);
        etBreakShort = findViewById(R.id.etBreakShort);
        etBreakLong = findViewById(R.id.etBreakLong);
        etBefore = findViewById(R.id.etBefore);
        cbPre = findViewById(R.id.cbPre);
        cbStart = findViewById(R.id.cbStart);
        cbSpeak = findViewById(R.id.cbSpeak);

        loadSettingsToForm();

        // Phan Cai dat: mac dinh thu gon, bam tieu de moi xo ra
        LinearLayout settingsBox = findViewById(R.id.settingsBox);
        TextView settingsToggle = findViewById(R.id.settingsToggle);
        settingsToggle.setOnClickListener(v -> {
            boolean open = settingsBox.getVisibility() == View.VISIBLE;
            settingsBox.setVisibility(open ? View.GONE : View.VISIBLE);
            settingsToggle.setText(open ? "⚙️ Cài đặt giờ ▸ (bấm để mở)"
                                        : "⚙️ Cài đặt giờ ▾ (bấm để thu gọn)");
        });

        // Dai chon ngay: mac dinh hom nay (CN thi ve Thu 2)
        int[] dayIds = {R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6, R.id.day7};
        int td = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        selectedDow = (td >= Calendar.MONDAY && td <= Calendar.SATURDAY) ? td : Calendar.MONDAY;
        for (int i = 0; i < dayIds.length; i++) {
            dayBtns[i] = findViewById(dayIds[i]);
            final int dow = Calendar.MONDAY + i;
            dayBtns[i].setOnClickListener(v -> { selectedDow = dow; render(); });
        }

        render();

        findViewById(R.id.btnSaveRemind).setOnClickListener(v -> saveAndReschedule());
        findViewById(R.id.btnRefreshSched).setOnClickListener(v -> refreshSchedule());
        findViewById(R.id.btnBattery).setOnClickListener(v -> askBatteryExemption());

        // Quyen thong bao (Android 13+)
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    private void loadSettingsToForm() {
        etStartSang.setText(ReminderStore.getStartStr(this, "sang"));
        etStartChieu.setText(ReminderStore.getStartStr(this, "chieu"));
        etLen.setText(String.valueOf(ReminderStore.periodLen(this)));
        etBreakShort.setText(String.valueOf(ReminderStore.breakShort(this)));
        etBreakLong.setText(String.valueOf(ReminderStore.breakLong(this)));
        etBefore.setText(String.valueOf(ReminderStore.alertBefore(this)));
        cbPre.setChecked(ReminderStore.optPre(this));
        cbStart.setChecked(ReminderStore.optStart(this));
        cbSpeak.setChecked(ReminderStore.optSpeak(this));
    }

    private int parseInt(EditText et, int def) {
        try { return Integer.parseInt(et.getText().toString().trim()); }
        catch (Exception e) { return def; }
    }

    private boolean validTime(EditText et) {
        return ReminderStore.parseHHmm(et.getText().toString(), -1) >= 0;
    }

    private void saveAndReschedule() {
        if (!validTime(etStartSang) || !validTime(etStartChieu)) {
            Toast.makeText(this, "Giờ bắt đầu phải dạng 7:15 hoặc 07:15.", Toast.LENGTH_LONG).show();
            return;
        }
        ReminderStore.saveSettings(this,
                etStartSang.getText().toString().trim(),
                etStartChieu.getText().toString().trim(),
                parseInt(etLen, 45), parseInt(etBreakShort, 5),
                parseInt(etBreakLong, 15), parseInt(etBefore, 25),
                cbPre.isChecked(), cbStart.isChecked(), cbSpeak.isChecked());
        int n = ReminderScheduler.scheduleAll(this);
        render();
        Toast.makeText(this, "Đã lưu. Đang đặt " + n + " báo thức nhắc giờ.", Toast.LENGTH_LONG).show();
    }

    private void refreshSchedule() {
        Toast.makeText(this, "Đang tải lịch báo giảng mới…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = ReminderStore.fetchSync(getApplicationContext());
            int n = ReminderScheduler.scheduleAll(getApplicationContext());
            runOnUiThread(() -> {
                render();
                Toast.makeText(this, ok
                        ? "Đã tải lịch mới và đặt " + n + " báo thức."
                        : "Không tải được (mất mạng?) — vẫn dùng lịch đã lưu trong máy (" + n + " báo thức).",
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void askBatteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 23
                    && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "App đã được bỏ tối ưu pin — báo thức chạy nền tốt.", Toast.LENGTH_LONG).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Hãy vào Cài đặt > Pin > Sao Bay > Không hạn chế.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String dowLabel(int dow) {
        switch (dow) {
            case Calendar.MONDAY: return "Thứ 2";
            case Calendar.TUESDAY: return "Thứ 3";
            case Calendar.WEDNESDAY: return "Thứ 4";
            case Calendar.THURSDAY: return "Thứ 5";
            case Calendar.FRIDAY: return "Thứ 6";
            case Calendar.SATURDAY: return "Thứ 7";
            default: return "Chủ nhật";
        }
    }

    private void render() {
        // Ngay dang xem = ngay thuoc tuan nay tuong ung voi thu da chon
        Calendar cal = new GregorianCalendar();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.set(Calendar.DAY_OF_WEEK, selectedDow);
        String selDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        boolean isToday = selDate.equals(todayStr);

        // To sang nut ngay dang chon
        for (int i = 0; i < dayBtns.length; i++) {
            if (dayBtns[i] == null) continue;
            boolean sel = (Calendar.MONDAY + i) == selectedDow;
            dayBtns[i].setBackgroundColor(Color.parseColor(sel ? "#7C3AED" : "#E5E7EB"));
            dayBtns[i].setTextColor(sel ? Color.WHITE : Color.parseColor("#374151"));
        }

        List<ReminderStore.ClassItem> items = ReminderStore.classesOn(this, selDate);
        StringBuilder sb = new StringBuilder();
        sb.append("📅 ").append(dowLabel(selectedDow)).append(" (").append(selDate).append(")");
        if (isToday) sb.append(" — hôm nay");
        sb.append(": ");
        if (items.isEmpty()) {
            sb.append("không có tiết dạy trong lịch đã nạp, hoặc chưa tải lịch.");
        } else {
            sb.append(items.size()).append(" tiết\n");
            int before = ReminderStore.alertBefore(this);
            String seenSession = "";
            for (ReminderStore.ClassItem ci : items) {
                if (!ci.session.equals(seenSession)) {
                    seenSession = ci.session;
                    sb.append("• Báo đi làm: ").append(ReminderStore.fmtMin(ci.startMin - before))
                      .append(" (trước ").append(before).append(" phút của tiết đầu buổi)\n");
                }
                sb.append("• ").append(ReminderStore.fmtMin(ci.startMin))
                  .append(" — Tiết ").append(ci.period).append(": ")
                  .append(ci.subject).append(" lớp ").append(ci.clazz);
                // Ten bai day xuong dong rieng, thut vao 1 o cho dep (thay yeu cau)
                if (!ci.lesson.isEmpty()) sb.append("\n    ").append(ci.lesson);
                sb.append("\n");
            }
        }
        tvToday.setText(sb.toString().trim());

        List<ReminderStore.Alert> alerts = ReminderStore.buildAlerts(this, 7);
        if (alerts.isEmpty()) {
            tvNext.setText("Chưa có báo thức nào được đặt (lịch trống hoặc đã qua hết).");
        } else {
            ReminderStore.Alert a = alerts.get(0);
            String when = new SimpleDateFormat("HH:mm dd/MM", Locale.US).format(new Date(a.triggerMillis));
            tvNext.setText("🔔 Báo thức gần nhất: " + when + " — " + a.title + "\nTổng số báo thức đã đặt (14 ngày tới): " + alerts.size());
        }

        renderWeek();
    }

    /** Thong ke ca tuan: moi thu chiem bao nhieu tiet / tong tiet ca tuan,
     *  moi thu 1 dong rieng cho de doc (thay yeu cau). */
    private void renderWeek() {
        Calendar c = new GregorianCalendar();
        c.setFirstDayOfWeek(Calendar.MONDAY);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        int[] counts = new int[6];
        String[] dates = new String[6];
        int total = 0;
        for (int i = 0; i < 6; i++) {
            c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY + i);
            dates[i] = df.format(c.getTime());
            counts[i] = ReminderStore.classesOn(this, dates[i]).size();
            total += counts[i];
        }
        String todayStr = df.format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Cả tuần: ").append(total).append(" tiết");
        if (total == 0) {
            sb.append("\nChưa có lịch tuần này trong máy — mở ⚙️ Cài đặt giờ ở dưới, bấm 🔄 Tải lịch báo giảng mới.");
        } else {
            for (int i = 0; i < 6; i++) {
                sb.append("\n• ").append(dowLabel(Calendar.MONDAY + i)).append(": ")
                  .append(counts[i]).append(" tiết");
                if (dates[i].equals(todayStr)) sb.append(" ← hôm nay");
            }
        }
        tvWeek.setText(sb.toString());
    }
}
