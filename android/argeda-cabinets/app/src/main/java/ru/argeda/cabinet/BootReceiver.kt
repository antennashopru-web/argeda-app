package ru.argeda.cabinet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки телефона или обновления приложения снова включает проверку новостей. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BadgeJobService.schedule(context)
    }
}
