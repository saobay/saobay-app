package com.saobay.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.os.Build;

/**
 * Nhan bao thuc den gio: hien thong bao chuong + rung (duong du phong luon
 * keo duoc), dong thoi goi ReminderService de DOC TO noi dung bang giong noi.
 * Tat ca chay ngay trong may, khong can mang.
 */
public class AlarmReceiver extends BroadcastReceiver {
    public static final String CHANNEL = "class_reminders_v1";

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Nhắc giờ lên lớp", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Chuông + đọc to nhắc giờ đi làm và giờ lên lớp");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 200, 600});
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        String speak = intent.getStringExtra("speak");
        if (title == null) title = "⏰ Nhắc giờ lên lớp";
        if (text == null) text = "";

        ensureChannel(ctx);
        int notifId = (int) (System.currentTimeMillis() % 100000);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pflags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent fsi = PendingIntent.getActivity(ctx, notifId, open, pflags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, CHANNEL);
        else b = new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setFullScreenIntent(fsi, true)
                .setContentIntent(fsi)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                .setVibrate(new long[]{0, 600, 200, 600})
                .setAutoCancel(true);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(notifId, b.build()); } catch (Exception ignored) {}
        }

        // Doc to noi dung (neu bat) qua dich vu chay nen
        if (ReminderStore.optSpeak(ctx)) {
            Intent svc = new Intent(ctx, ReminderService.class);
            svc.putExtra("speak", speak != null ? speak : text);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
            } catch (Exception ignored) {}
        }
    }
}
