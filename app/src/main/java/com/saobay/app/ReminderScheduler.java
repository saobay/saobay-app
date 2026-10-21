package com.saobay.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.List;

/** Dat/huy toan bo bao thuc nhac gio len lop bang AlarmManager (chay nen, tat man hinh van bao). */
public class ReminderScheduler {

    public static int scheduleAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return 0;
        SharedPreferences sp = ReminderStore.prefs(ctx);

        // Huy cac bao thuc cu da dat
        String old = sp.getString("codes", "");
        if (!old.isEmpty()) {
            for (String s : old.split(",")) {
                if (s.isEmpty()) continue;
                try {
                    int code = Integer.parseInt(s);
                    PendingIntent pi = pending(ctx, code, null);
                    am.cancel(pi);
                    pi.cancel();
                } catch (Exception ignored) {}
            }
        }

        List<ReminderStore.Alert> alerts = ReminderStore.buildAlerts(ctx, 14);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        StringBuilder codes = new StringBuilder();
        for (ReminderStore.Alert a : alerts) {
            Intent i = new Intent(ctx, AlarmReceiver.class);
            i.putExtra("title", a.title);
            i.putExtra("text", a.text);
            i.putExtra("speak", a.speak);
            PendingIntent pi = pending(ctx, a.id, i);
            try {
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.triggerMillis, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.triggerMillis, pi);
                }
            } catch (SecurityException e) {
                try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.triggerMillis, pi); }
                catch (Exception ignored) {}
            }
            codes.append(a.id).append(',');
        }
        sp.edit().putString("codes", codes.toString())
                .putInt("alert_count", alerts.size()).apply();
        return alerts.size();
    }

    private static PendingIntent pending(Context ctx, int code, Intent base) {
        Intent i = (base != null) ? base : new Intent(ctx, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, code, i, flags);
    }
}
