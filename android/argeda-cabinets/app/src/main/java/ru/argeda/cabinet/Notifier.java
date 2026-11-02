package ru.argeda.cabinet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Уведомления продавцу: три канала, чтобы продавец сам выбирал, о чём звенеть. */
final class Notifier {
    static final String CH_ORDERS = "orders";
    static final String CH_CHAT = "chat";
    static final String CH_OTHER = "other";

    private Notifier() {
    }

    /** Канал по смыслу счётчика: заказы и выдача — громко, чат — громко, остальное — обычно. */
    static String channelFor(String key) {
        if (key.contains("chat") || key.contains("message")) return CH_CHAT;
        if (key.contains("order") || key.contains("issue") || key.contains("deliver")
                || key.contains("accept") || key.contains("pickup") || key.contains("arrived")) return CH_ORDERS;
        return CH_OTHER;
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(channel(c, CH_ORDERS, R.string.ch_orders, NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(channel(c, CH_CHAT, R.string.ch_chat, NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(channel(c, CH_OTHER, R.string.ch_other, NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static NotificationChannel channel(Context c, String id, int name, int importance) {
        NotificationChannel ch = new NotificationChannel(id, c.getString(name), importance);
        ch.setShowBadge(true);
        return ch;
    }

    /** id уведомления постоянный на тип: новое уведомление того же типа заменяет старое. */
    static void show(Context c, String channel, int id, String title, String text, String url) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url), c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_mono)
                .setColor(c.getColor(R.color.blue))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
