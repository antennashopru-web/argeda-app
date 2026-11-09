package ru.argeda.cabinet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Следит за интернетом. Без связи WebView берёт страницы и картинки из своего кэша
 * (LOAD_CACHE_ELSE_NETWORK), а страница узнаёт о состоянии через navigator.onLine и события online/offline.
 * listener — что сделать, когда связь пропала или вернулась (вызывается на главном потоке).
 */
internal class Net(ctx: Context, private val web: WebView, private val listener: ((Boolean) -> Unit)?) {
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Есть ли связь по последним сведениям. */
    var isOnline: Boolean = isOnlineNow()
        private set

    init {
        apply(isOnline)
    }

    /** Есть ли сейчас сеть с выходом в интернет (проверенная системой). */
    fun isOnlineNow(): Boolean {
        val cm = cm ?: return true
        return try {
            val n = cm.activeNetwork ?: return false
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: RuntimeException) {
            true // не смогли узнать — считаем, что сеть есть
        }
    }

    fun start() {
        val cm = cm ?: return
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post()
            override fun onLost(network: Network) = post()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()
        }
        callback = cb
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb
            )
        } catch (e: RuntimeException) {
            callback = null
        }
        post()
    }

    fun stop() {
        val cm = cm ?: return
        val cb = callback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (e: RuntimeException) { /* уже снят */ }
        callback = null
    }

    private fun post() {
        main.post {
            val now = isOnlineNow()
            if (now != isOnline) {
                isOnline = now
                apply(now)
                listener?.invoke(now)
            }
        }
    }

    private fun apply(on: Boolean) {
        web.settings.cacheMode = if (on) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
        web.setNetworkAvailable(on)
    }
}
