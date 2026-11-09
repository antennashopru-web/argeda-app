package ru.argeda.design

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Принимает от Android ответ об установке обновления (сессия PackageInstaller) и передаёт его в Updater. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updater.onStatus(context, intent)
    }
}
