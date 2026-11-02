package ru.argeda.design;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Следит за интернетом. Без связи WebView берёт страницы и картинки из своего кэша
 * (LOAD_CACHE_ELSE_NETWORK), а страница узнаёт о состоянии через navigator.onLine и события online/offline.
 * Без лямбд — сборка без Android SDK их не переваривает.
 */
final class Net {
    interface Listener {
        void onNetChanged(boolean online);
    }

    private final ConnectivityManager cm;
    private final WebView web;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private boolean online;

    Net(Context ctx, WebView web, Listener listener) {
        this.cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.web = web;
        this.listener = listener;
        online = isOnlineNow();
        apply(online);
    }

    boolean isOnline() {
        return online;
    }

    /** Есть ли сейчас сеть с выходом в интернет (проверенная системой). */
    boolean isOnlineNow() {
        if (cm == null) return true;
        try {
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return true; // не смогли узнать — считаем, что сеть есть
        }
    }

    void start() {
        if (cm == null || callback != null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) { post(); }

            @Override
            public void onLost(Network network) { post(); }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { post(); }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback);
        } catch (RuntimeException e) {
            callback = null;
        }
        post();
    }

    void stop() {
        if (cm == null || callback == null) return;
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException e) { /* уже снят */ }
        callback = null;
    }

    private void post() {
        main.post(new Runnable() {
            @Override
            public void run() {
                boolean now = isOnlineNow();
                if (now == online) return;
                online = now;
                apply(now);
                if (listener != null) listener.onNetChanged(now);
            }
        });
    }

    private void apply(boolean on) {
        WebSettings s = web.getSettings();
        s.setCacheMode(on ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        web.setNetworkAvailable(on);
    }
}
