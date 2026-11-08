package ru.argeda.design;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Принимает от Android ответ об установке обновления (сессия PackageInstaller) и передаёт его в Updater. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Updater.onStatus(context, intent);
    }
}
