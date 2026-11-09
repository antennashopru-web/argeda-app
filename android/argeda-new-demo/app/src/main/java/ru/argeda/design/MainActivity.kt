package ru.argeda.design

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Новое приложение ARGEDA по макету v1. Экраны лежат в assets и открываются
 * с адреса https://appassets.androidplatform.net, чтобы у страницы было
 * обычное https-хранилище для корзины и заказов.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var density = 1f
    private var topInset = 0
    private var fileCallback: ValueCallback<Array<Uri>?>? = null
    private var pendingJs: String? = null
    private var pageReady = false
    private var net: Net? = null

    /** Элемент экрана из разметки main.xml (он там всегда есть). */
    private fun <T : View> view(id: Int): T = findViewById<T>(id)!!

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        setContentView(R.layout.main)
        density = resources.displayMetrics.density
        web = view(R.id.web)
        drawBehindSystemBars()
        setupWebView()
        // Свои экраны (app.js, app.css) отдаются с запретом кэша (см. shouldInterceptRequest), поэтому после обновления
        // старый вид не покажется, а кэш картинок товаров для работы без интернета сохраняется. На новой версии
        // только не восстанавливаем прошлое окно — открываем приложение заново.
        var state = savedState
        val vp = getSharedPreferences("argeda_ver", MODE_PRIVATE)
        if (vp.getInt("code", 0) != BuildConfig.VERSION_CODE) {
            vp.edit().putInt("code", BuildConfig.VERSION_CODE).apply()
            state = null
        }
        if (state == null || web.restoreState(state) == null) web.loadUrl(START)
        handleDeepLink(intent)
        checkUpdate(false)
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) Updater.checkNow(this, VERSIONS, "argeda", BuildConfig.VERSION_CODE)
        else Updater.checkQuietly(this, VERSIONS, "argeda", BuildConfig.VERSION_CODE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    // Ссылки, которые открывают приложение: ru.argeda.design://order/A-номер (возврат после оплаты)
    // и https://argeda.ru/… (App Links с сайта) → тот же экран внутри приложения
    private fun handleDeepLink(intent: Intent?) {
        val d = intent?.data ?: return
        val scheme = d.scheme?.lowercase(Locale.ROOT) ?: ""
        val host = d.host?.lowercase(Locale.ROOT) ?: ""
        pendingJs = if (scheme == "ru.argeda.design" && host == "order") {
            val num = d.lastPathSegment
            if (num.isNullOrEmpty()) return
            "window.appOpenOrder && window.appOpenOrder(" + jsString(num) + ")"
        } else if ((scheme == "https" || scheme == "http") && (host == "argeda.ru" || host.endsWith(".argeda.ru"))) {
            "window.appOpenUrl && window.appOpenUrl(" + jsString(d.toString()) + ")"
        } else {
            return
        }
        if (pageReady) runPending()
    }

    private fun runPending() {
        val js = pendingJs ?: return
        pendingJs = null
        web.post { web.evaluateJavascript(js, null) }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        // Мост открыт только своим страницам из assets: чужие адреса в это окно не загружаются
        web.addJavascriptInterface(Bridge(), "Android")
        // Без интернета картинки берутся из кэша WebView, а страница видит navigator.onLine = false
        net = Net(this, web, null)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (HOST != uri.host) return null
                val p = uri.path
                val path = if (p == null || p == "/") "index.html" else p.substring(1)
                return try {
                    val headers = hashMapOf("Cache-Control" to "no-store") // свои файлы всегда свежие из APK
                    val type = mime(path)
                    WebResourceResponse(
                        type, if (type.startsWith("text") || path.endsWith(".js")) "utf-8" else null,
                        200, "OK", headers, assets.open(path)
                    )
                } catch (e: IOException) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (HOST == uri.host) return false
                openExternal(uri)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pushInsets()
                pageReady = true
                runPending()
            }
        }

        // Поиск по фото: снимок или файл из галереи
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>?>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
    }

    private fun openExternal(uri: Uri) {
        when (uri.scheme?.lowercase(Locale.ROOT) ?: "") {
            "tel" -> startSafe(Intent(Intent.ACTION_DIAL, uri))
            "mailto" -> startSafe(Intent(Intent.ACTION_SENDTO, uri))
            else -> {
                // Оплата и внешние ссылки — пробуем Custom Tab: страница открывается «поверх» приложения
                // и возвращает обратно. Браузер без поддержки Custom Tab просто откроет её как обычную ссылку.
                val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                val extras = Bundle()
                extras.putBinder("android.support.customtabs.extra.SESSION", null) // метка «это Custom Tab»
                intent.putExtras(extras)
                intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF1668D9.toInt()) // синий ARGEDA
                startSafe(intent)
            }
        }
    }

    private fun startSafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Методы, которые вызывает страница через window.Android.
     * Строки от страницы могут прийти пустыми (null), поэтому они String?.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun deviceModel(): String {
            var maker = Build.MANUFACTURER ?: ""
            val model = Build.MODEL ?: ""
            if (maker.isNotEmpty()) maker = maker.substring(0, 1).uppercase(Locale.ROOT) + maker.substring(1)
            return if (model.lowercase(Locale.ROOT).startsWith(maker.lowercase(Locale.ROOT))) model else "$maker $model".trim()
        }

        // Название телефона из настроек («Galaxy A01»), если оно похоже на модель, а не на имя, которое дал владелец
        // («Телефон Маши»). Пусто — страница покажет просто «телефон», а не заводской код вроде SM-A015F.
        @JavascriptInterface
        fun marketName(): String {
            val name = try {
                Settings.Global.getString(contentResolver, "device_name")
            } catch (ignored: RuntimeException) {
                null // нет доступа к настройкам — вернём пусто
            } ?: return ""
            val n = name.trim()
            return if (MARKET_NAME.matches(n)) n else ""
        }

        // Светлые значки статус-бара нужны там, где сверху тёмная шапка
        @JavascriptInterface
        fun setLight(light: Boolean) {
            runOnUiThread { setLightBars(light) }
        }

        // Тёмная ли тема в телефоне — страница по ней выбирает оформление
        @JavascriptInterface
        fun isNight(): Boolean = phoneIsNight()

        // Страница сообщает, какое оформление выбрано: под него красим фон окна и значки нижней панели
        @JavascriptInterface
        fun setNight(night: Boolean) {
            runOnUiThread { applyNight(night) }
        }

        // Кнопка «Проверить обновления» в настройках
        @JavascriptInterface
        fun checkUpdate() {
            runOnUiThread { this@MainActivity.checkUpdate(true) }
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        // Есть ли сейчас интернет — страница показывает плашку «Нет связи» и живёт сохранённым
        @JavascriptInterface
        fun isOnline(): Boolean = net?.isOnline ?: true

        @JavascriptInterface
        fun share(text: String?) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, null))
            }
        }
    }

    private fun phoneIsNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun applyNight(night: Boolean) {
        val bg = if (night) 0xFF0B1220.toInt() else 0xFFF2F5FA.toInt()
        view<View>(R.id.root).setBackgroundColor(bg)
        web.setBackgroundColor(bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val nav = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else nav, nav)
        } else {
            val decor = window.decorView
            val flags = decor.systemUiVisibility
            decor.systemUiVisibility = if (night) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    // Тему телефона переключили на ходу — активность не пересоздаётся (uiMode в configChanges), сообщаем странице
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        if (::web.isInitialized) web.evaluateJavascript("window.appTheme && appTheme()", null)
    }

    @Suppress("DEPRECATION")
    private fun setLightBars(lightIcons: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightIcons) 0 else dark, dark)
        } else {
            val decor = window.decorView
            val flags = decor.systemUiVisibility
            decor.systemUiVisibility = if (lightIcons) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    // Рисуем под статус-баром (тёмная шапка уходит под него), снизу отступаем от меню и клавиатуры
    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        val root = view<View>(R.id.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root.setOnApplyWindowInsetsListener { v, insets ->
            val left: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                left = bars.left
                right = bars.right
                bottom = maxOf(bars.bottom, ime.bottom)
                topInset = bars.top
            } else {
                left = insets.systemWindowInsetLeft
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
                topInset = insets.systemWindowInsetTop
            }
            v.setPadding(left, 0, right, bottom)
            pushInsets()
            insets
        }
    }

    private fun pushInsets() {
        if (!::web.isInitialized) return
        val topCss = (topInset / density).roundToInt()
        web.evaluateJavascript("document.documentElement.style.setProperty('--top','" + topCss + "px')", null)
    }

    @Deprecated("Старый способ выбора файла: WebView отдаёт файлы только через него")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val cb = fileCallback
        if (requestCode == REQ_FILE && cb != null) {
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    // «Назад» сначала обрабатывает приложение: закрывает шторку или возвращает на прошлый экран
    @Deprecated("Кнопка «Назад» по-старому: работает на всех Android с 8-го")
    override fun onBackPressed() {
        web.evaluateJavascript("window.appBack ? appBack() : false") { handled ->
            if ("true" != handled) systemBack()
        }
    }

    /** Обычное «Назад» Android: закрыть приложение. */
    @Suppress("DEPRECATION")
    private fun systemBack() {
        super.onBackPressed()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    override fun onStart() {
        super.onStart()
        net?.start()
    }

    override fun onStop() {
        net?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        Updater.resume(this) // вернулись из настроек разрешения или установщик ждал приложение на экране
        checkUpdate(false)
        // Вернулись в приложение (в т.ч. после оплаты в браузере) — обновим открытый заказ
        if (pageReady) web.evaluateJavascript("window.appResumed && window.appResumed()", null)
    }

    override fun onPause() {
        Updater.pause(this)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START = "https://$HOST/index.html"
        private const val REQ_FILE = 1

        // Файл версий — в последнем релизе GitHub (github.com/antennashopru-web/argeda-app/releases); раз в 4 часа при запуске, по кнопке в настройках — сразу
        private const val VERSIONS = "https://github.com/antennashopru-web/argeda-app/releases/latest/download/version.json"

        /** Название из настроек телефона похоже на модель, а не на имя, которое дал владелец. */
        private val MARKET_NAME = Regex(
            "(?i)^(Samsung |Xiaomi )?(Galaxy|Redmi|POCO|Mi|HONOR|HUAWEI|realme|OPPO|vivo|TECNO|Infinix|Pixel|moto|Nokia|OnePlus|ZTE|Blade) .{1,24}$"
        )

        private fun jsString(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

        private fun mime(path: String): String {
            val p = path.lowercase(Locale.ROOT)
            return when {
                p.endsWith(".html") -> "text/html"
                p.endsWith(".css") -> "text/css"
                p.endsWith(".js") -> "application/javascript"
                p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
                p.endsWith(".png") -> "image/png"
                p.endsWith(".svg") -> "image/svg+xml"
                p.endsWith(".woff2") -> "font/woff2"
                else -> "application/octet-stream"
            }
        }
    }
}
