package ru.argeda.cabinet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Уведомления продавцу: три канала, чтобы продавец сам выбирал, о чём звенеть. */
internal object Notifier {
    const val CH_ORDERS = "orders"
    const val CH_CHAT = "chat"
    const val CH_OTHER = "other"

    /** Канал по смыслу счётчика: заказы и выдача — громко, чат — громко, остальное — обычно. */
    fun channelFor(key: String): String = when {
        "chat" in key || "message" in key -> CH_CHAT
        listOf("order", "issue", "deliver", "accept", "pickup", "arrived").any { it in key } -> CH_ORDERS
        else -> CH_OTHER
    }

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(channel(c, CH_ORDERS, R.string.ch_orders, NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(channel(c, CH_CHAT, R.string.ch_chat, NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(channel(c, CH_OTHER, R.string.ch_other, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun channel(c: Context, id: String, name: Int, importance: Int) =
        NotificationChannel(id, c.getString(name), importance).apply { setShowBadge(true) }

    /** id уведомления постоянный на тип: новое уведомление того же типа заменяет старое. */
    fun show(c: Context, channel: String, id: Int, title: String, text: String, url: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(url), c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_mono)
            .setColor(c.getColor(R.color.blue))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
