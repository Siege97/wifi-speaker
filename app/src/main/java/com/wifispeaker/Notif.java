package com.wifispeaker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Notificação obrigatória do serviço em primeiro plano. */
final class Notif {
    private Notif() {}

    private static final String CHANNEL_ID = "wifi_speaker";

    static Notification build(Context ctx, String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Wi-Fi Speaker", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }

        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, new Intent(ctx, MainActivity.class), flags);

        return b.setContentTitle("Wi-Fi Speaker")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** startForeground com o tipo certo a partir do Android 10. */
    static void startForeground(Service s, int id, Notification n, int type) {
        if (Build.VERSION.SDK_INT >= 29) {
            s.startForeground(id, n, type);
        } else {
            s.startForeground(id, n);
        }
    }
}
