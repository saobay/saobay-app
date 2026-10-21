package com.saobay.app;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * Dich vu doc to thong bao nhac gio: chay nen ke ca khi tat man hinh,
 * phat giong noi qua kenh am thanh BAO THUC (USAGE_ALARM) de nghe ro nhu
 * chuong bao, doc 2 lan roi tu tat. Giong noi la TTS co san tren may
 * (khong can mang).
 */
public class ReminderService extends Service implements TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;
    private String speakText = "";
    private boolean inited = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) speakText = intent.getStringExtra("speak") != null
                ? intent.getStringExtra("speak") : "";
        AlarmReceiver.ensureChannel(this);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, AlarmReceiver.CHANNEL);
        else b = new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("🔊 Đang đọc nhắc giờ…")
                .setContentText(speakText);
        try { startForeground(9911, b.build()); } catch (Exception ignored) {}

        // Giu may thuc de doc het cau du man hinh tat
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musevoice:reminder");
                wakeLock.acquire(90000);
            }
        } catch (Exception ignored) {}

        if (!inited) {
            tts = new TextToSpeech(getApplicationContext(), this);
        } else {
            doSpeak();
        }
        // Phong treo: tu tat sau 60 giay du doc duoc hay khong
        new Handler(Looper.getMainLooper()).postDelayed(this::stopSafely, 60000);
        return START_NOT_STICKY;
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) { stopSafely(); return; }
        inited = true;
        try {
            int r = tts.setLanguage(new Locale("vi", "VN"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) {
                    if ("rep2".equals(id)) stopSafely();
                }
                @Override public void onError(String id) {
                    if ("rep2".equals(id)) stopSafely();
                }
            });
        } catch (Exception ignored) {}
        doSpeak();
    }

    private void doSpeak() {
        if (tts == null || speakText == null || speakText.trim().isEmpty()) { stopSafely(); return; }
        try {
            tts.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, "main");
            // Doc lai lan 2 cho chac nghe thay
            tts.speak(speakText, TextToSpeech.QUEUE_ADD, null, "rep2");
        } catch (Exception e) {
            stopSafely();
        }
    }

    private void stopSafely() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
