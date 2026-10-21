package com.saobay.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** May khoi dong lai -> dat lai toan bo bao thuc nhac gio tu lich da luu trong may. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try { ReminderScheduler.scheduleAll(ctx.getApplicationContext()); }
            catch (Exception ignored) {}
        }
    }
}
