package ru.argeda.cabinet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.Locale

/**
 * Приложения кабинетов ARGEDA (продавец, пункт выдачи, курьер, админка): внутри живой кабинет
 * с сайта, поэтому разделы и поведение всегда такие же, как на сайте. Приложение добавляет
 * уведомления, сохранение файлов в «Загрузки», камеру, геолокацию и ярлыки. Какой кабинет — решает сборка.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offline: View
    private lateinit var offlineTitle: TextView
    private lateinit var offlineText: TextView
    private lateinit var netbar: TextView
    private var net: Net? = null
    private var lastUrl: String? = null
    private var fileCallback: ValueCallback<Array<Uri>?>? = null
    private var cameraRequest: PermissionRequest? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var signingOut = false
    private var notMemberShown = false
    private var sandbar: TextView? = null
    private var baseUa: String? = null
    private val handler = Handler(Looper.getMainLooper())

    // Сервер не отвечает, а интернет есть: пробуем открыть кабинет снова раз в 30 секунд
    private val autoRetry = Runnable {
        if (offline.visibility == View.VISIBLE && net?.isOnline == true && !isFinishing) reload()
    }

    /** Элемент экрана из разметки main.xml (он там всегда есть). */
    private fun <T : View> view(id: Int): T = findViewById<T>(id)!!

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.main)
        web = view(R.id.web)
        progress = view(R.id.progress)
        offline = view(R.id.offline)
        offlineTitle = view(R.id.offline_title)
        offlineText = view(R.id.offline_text)
        netbar = view(R.id.netbar)
        view<View>(R.id.retry).setOnClickListener { reload() }
        drawBehindSystemBars()
        setupWebView()
        // Без интернета кабинет открывается из кэша WebView с плашкой «Нет связи»; связь вернулась — плашка уходит,
        // а если страница так и не открылась, пробуем снова сами
        val n = Net(this, web) { online ->
            netbar.visibility = if (online) View.GONE else View.VISIBLE
            if (online && offline.visibility == View.VISIBLE) reload()
        }
        net = n
        netbar.visibility = if (n.isOnline) View.GONE else View.VISIBLE
        if (BuildConfig.SANDBOX) setupSandbox()
        if (state == null || web.restoreState(state) == null) {
            val link = siteLink(intent)
            if (link != null) {
                if (BuildConfig.SANDBOX) rememberRoleOf(link)
                web.loadUrl(link)
            } else if (BuildConfig.SANDBOX && role() == null) {
                pickRole() // первый запуск Песочницы: сначала выбираем, кем зайти
            } else {
                web.loadUrl(home())
            }
        }
        // Раньше копии хранили пароль сайта-копии для обновлений — теперь он не нужен, стираем
        getSharedPreferences("site", MODE_PRIVATE).edit().remove("auth").apply()
        Notifier.ensureChannels(this)
        askNotifications()
        BadgeJobService.schedule(this)
        checkUpdate()
    }

    /** Прячет экран «Нет связи» и открывает снова последнюю страницу (или стартовую). */
    private fun reload() {
        offline.visibility = View.GONE
        web.loadUrl(lastUrl ?: home())
    }

    // Кабинеты и Песочница обновляются из последнего релиза GitHub; ключ в version.json — seller, pvz, courier, admin, sandbox
    private fun checkUpdate() {
        val url = "https://github.com/antennashopru-web/argeda-app/releases/latest/download/version.json"
        Updater.checkQuietly(this, url, BuildConfig.CABINET, BuildConfig.VERSION_CODE)
    }

    /** Стартовая страница: кабинет сборки, а в Песочнице — выбранная роль на копии сайта. */
    private fun home(): String {
        if (!BuildConfig.SANDBOX) return Links.HOME
        return "https://" + Links.KOPIYA_HOST + ROLE_PATHS[roleIndex(role())] + "?utm_source=sandbox_app"
    }

    // ---------- Песочница ----------

    private fun sandboxPrefs(): SharedPreferences = getSharedPreferences("sandbox", MODE_PRIVATE)

    private fun role(): String? = sandboxPrefs().getString("role", null)

    private fun setupSandbox() {
        val bar = view<TextView>(R.id.sandbar)
        sandbar = bar
        bar.visibility = View.VISIBLE
        bar.setOnClickListener { pickRole() }
        applyRole()
    }

    /** Надпись на полосе и метка в адресе браузера: кабинет на копии видит, какую роль проверяют. */
    private fun applyRole() {
        val i = roleIndex(role())
        sandbar?.text = getString(R.string.sandbox_bar, getString(ROLE_NAMES[i]))
        val key = if (role() == null) "sandbox" else ROLE_KEYS[i]
        web.settings.userAgentString = "$baseUa ArgedaApp/$key/" + BuildConfig.VERSION_NAME + " ArgedaSandbox"
    }

    private fun setRole(key: String) {
        sandboxPrefs().edit().putString("role", key).apply()
        applyRole()
    }

    // Ссылка (ярлык, другое приложение) ведёт в раздел копии — запоминаем роль по пути: /seller…, /pvz…
    private fun rememberRoleOf(url: String) {
        val path = Uri.parse(url).path ?: "/"
        var key = "buyer"
        for (i in 1 until ROLE_KEYS.size) {
            if (path == ROLE_PATHS[i] || path.startsWith(ROLE_PATHS[i] + "/")) key = ROLE_KEYS[i]
        }
        setRole(key)
    }

    private fun pickRole() {
        if (isFinishing) return
        val items = Array(ROLE_KEYS.size + 1) { i ->
            if (i < ROLE_KEYS.size) getString(ROLE_NAMES[i]) else getString(R.string.sandbox_signout)
        }
        val first = role() == null
        AlertDialog.Builder(this)
            .setTitle(R.string.sandbox_pick)
            .setItems(items) { _, which ->
                if (which == ROLE_KEYS.size) {
                    // Вход на копии один на все роли: чтобы зайти другим аккаунтом, сначала выходим
                    if (first) setRole("buyer")
                    signingOut = true
                    web.loadUrl(Links.SIGNOUT)
                } else {
                    setRole(ROLE_KEYS[which])
                    offline.visibility = View.GONE
                    web.loadUrl(home())
                }
            }
            .setOnCancelListener {
                // Закрыли окно при первом запуске — открываем как покупатель, сменить можно по полосе сверху
                if (role() == null) {
                    setRole("buyer")
                    web.loadUrl(home())
                }
            }
            .show()
        Toast.makeText(this, R.string.sandbox_note, Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = siteLink(intent) ?: return
        if (BuildConfig.SANDBOX) rememberRoleOf(link)
        web.loadUrl(link)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false // сканер штрихкодов включает камеру сам
        s.setGeolocationEnabled(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // По этой метке кабинет понимает, что открыт в приложении
        baseUa = s.userAgentString
        s.userAgentString = "$baseUa ArgedaApp/" + BuildConfig.CABINET + "/" + BuildConfig.VERSION_NAME
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val bridge = Bridge()
        web.addJavascriptInterface(bridge, "ArgedaApp")
        web.addJavascriptInterface(bridge, "ArgedaSeller") // старое имя, чтобы кабинет продавца не переписывать

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        // Сторонние cookie кабинету не нужны: внутри открываются только страницы *.argeda.ru
        cookies.setAcceptThirdPartyCookies(web, false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                route(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                lastUrl = url
                // В Песочнице кабинет покупателя (/account) — обычная роль, предупреждать не о чем
                if (!BuildConfig.SANDBOX && isAccountPage(url)) showNotMember()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (signingOut) {
                    // Вышли из чужого аккаунта: возвращаемся на вход в кабинет
                    signingOut = false
                    notMemberShown = false
                    BadgeJobService.reset(this@MainActivity)
                    view.loadUrl(home())
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
                if (request.isForMainFrame) showOffline()
            }

            // Хостинг отдаёт свою страницу-заглушку (сервер лежит, 502/503/504) — вместо неё свой понятный экран
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse?) {
                if (!request.isForMainFrame || response == null) return
                if (response.statusCode in SERVER_DOWN) {
                    view.stopLoading()
                    showOffline()
                }
            }

            // Копия сайта закрыта паролем сервера: спрашиваем логин и пароль, как браузер
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                if (!Links.isOurs(host)) {
                    handler.cancel()
                    return
                }
                val saved = view.getHttpAuthUsernamePassword(host, realm)
                if (saved != null && saved.size == 2 && saved[0] != null && handler.useHttpAuthUsernamePassword()) {
                    handler.proceed(saved[0], saved[1])
                    return
                }
                askHttpAuth(view, handler, host, realm)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                progress.progress = value
                progress.visibility = if (value < 100) View.VISIBLE else View.GONE
            }

            // Фото товаров, документы для карточки компании
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

            // Камера для сканера штрихкодов на страницах кабинета
            override fun onPermissionRequest(request: PermissionRequest) {
                val host = request.origin?.host
                val camera = request.resources?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true
                if (!Links.isOurs(host) || !camera) {
                    request.deny()
                    return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    cameraRequest = request
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (cameraRequest === request) cameraRequest = null
            }

            // Геолокация для карты курьера: только страницам *.argeda.ru и только если сборка её объявила
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                val host = origin?.let { Uri.parse(it).host }
                val declared = hasManifestPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                if (!Links.isOurs(host) || !declared) {
                    callback.invoke(origin, false, false)
                    return
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, true)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQ_LOCATION
                    )
                }
            }

            override fun onGeolocationPermissionsHidePrompt() {
                geoCallback = null
            }
        }

        // Стикеры PDF, лист подбора, прайс Excel, отчёт агента
        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (url == null) return@setDownloadListener
            if (url.startsWith("blob:")) {
                web.evaluateJavascript(Downloads.blobScript(url), null)
            } else if (url.startsWith("data:")) {
                val comma = url.indexOf(',')
                val head = if (comma > 0) url.substring(5, comma) else ""
                val type = if (head.contains(";")) head.substring(0, head.indexOf(';')) else head
                val ok = comma > 0 && head.contains("base64") &&
                    Downloads.saveBase64(this, url.substring(comma + 1), type, web.title)
                Toast.makeText(this, if (ok) R.string.download_saved else R.string.download_failed, Toast.LENGTH_SHORT).show()
            } else {
                Downloads.fromUrl(this, url, userAgent, disposition, mime)
            }
        }
    }

    /**
     * Что кабинет может сказать приложению со страницы: window.ArgedaSeller.…
     * Параметры от страницы могут прийти пустыми (null), поэтому все они String?.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun saveBlob(base64: String?, mime: String?, title: String?) {
            val ok = Downloads.saveBase64(this@MainActivity, base64, mime, title)
            runOnUiThread {
                Toast.makeText(this@MainActivity, if (ok) R.string.download_saved else R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
        }

        /** Кабинет отдаёт свежие счётчики, пока открыт: уведомлений о том, что уже видно, не будет. */
        @JavascriptInterface
        fun badge(json: String?) {
            if (json == null) return
            try {
                BadgeJobService.remember(this@MainActivity, JSONObject(json))
            } catch (ignored: JSONException) {
                // неверный формат: просто пропускаем
            }
        }

        @JavascriptInterface
        fun loggedOut() {
            BadgeJobService.reset(this@MainActivity)
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }

    // true: ссылку открыли сами (другое приложение), false: пусть грузит WebView
    private fun route(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: ""
        val host = uri.host?.lowercase(Locale.ROOT) ?: ""
        if (BuildConfig.SANDBOX && Links.isLive(host)) {
            // Песочница не пускает на живой сайт ни внутри, ни через браузер: проверки идут только на копии
            Toast.makeText(this, R.string.sandbox_live_blocked, Toast.LENGTH_LONG).show()
            return true
        }
        if (scheme == "http" && Links.isOurs(host)) {
            web.loadUrl(uri.buildUpon().scheme("https").build().toString())
            return true
        }
        if (scheme == "http" || scheme == "https") {
            // Внутри кабинета — только свои сайты (*.argeda.ru). Любая чужая ссылка (из чата, письма, карточки)
            // открывается в браузере телефона или своём приложении: чужая страница не должна выглядеть как кабинет
            // и не получает доступ к мосту приложения.
            if (Links.isOurs(host)) return false
            openExternal(uri)
            return true
        }
        if (scheme == "intent") {
            openIntentUri(uri.toString())
            return true
        }
        openExternal(uri)
        return true
    }

    private fun openExternal(uri: Uri) {
        val intent = when (uri.scheme?.lowercase(Locale.ROOT) ?: "") {
            "tel" -> Intent(Intent.ACTION_DIAL, uri)
            "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
            else -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // Ссылки вида intent://: так сайты открывают банковские приложения
    private fun openIntentUri(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            try {
                startActivity(intent)
                return
            } catch (ignored: ActivityNotFoundException) {
                // ниже пробуем запасной адрес
            }
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && fallback.startsWith("https://")) {
                if (!route(Uri.parse(fallback))) web.loadUrl(fallback)
                return
            }
        } catch (ignored: URISyntaxException) {
            // покажем сообщение ниже
        }
        Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
    }

    /** Страницы нет ни в сети, ни в кэше: объясняем, чего ждём — интернета у телефона или сервера ARGEDA. */
    private fun showOffline() {
        val noNet = net?.isOnline == false
        offlineTitle.setText(if (noNet) R.string.offline_title_net else R.string.offline_title)
        offlineText.setText(if (noNet) R.string.offline_text_net else R.string.offline_text)
        offline.visibility = View.VISIBLE
        handler.removeCallbacks(autoRetry)
        if (!noNet) handler.postDelayed(autoRetry, 30000)
    }

    private fun showNotMember() {
        if (notMemberShown || isFinishing) return
        notMemberShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.not_member_title)
            .setMessage(R.string.not_member_text)
            .setPositiveButton(R.string.not_member_signout) { _, _ ->
                signingOut = true
                web.loadUrl(Links.SIGNOUT)
            }
            .setNegativeButton(R.string.not_member_stay) { _, _ -> notMemberShown = false }
            .show()
    }

    private fun askHttpAuth(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        val user = EditText(this)
        user.setHint(R.string.http_auth_login)
        user.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val pass = EditText(this)
        pass.setHint(R.string.http_auth_password)
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(user)
        box.addView(pass)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.http_auth_title, host))
            .setMessage(R.string.http_auth_text)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.http_auth_ok) { _, _ ->
                val u = user.text.toString()
                val p = pass.text.toString()
                view.setHttpAuthUsernamePassword(host, realm, u, p)
                handler.proceed(u, p)
            }
            .setNegativeButton(R.string.http_auth_cancel) { _, _ -> handler.cancel() }
            .show()
    }

    private fun hasManifestPermission(permission: String): Boolean = try {
        val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        declared?.contains(permission) == true
    } catch (ignored: PackageManager.NameNotFoundException) {
        false // своё приложение всегда найдётся
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        val geo = geoCallback
        if (requestCode == REQ_LOCATION && geo != null) {
            val granted = results.any { it == PackageManager.PERMISSION_GRANTED }
            geo.invoke(geoOrigin, granted, granted)
            if (!granted) Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
            geoCallback = null
            return
        }
        val cam = cameraRequest
        if (requestCode == REQ_CAMERA && cam != null) {
            val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                cam.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                cam.deny()
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
            }
            cameraRequest = null
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, results)
    }

    // Рисуем под системными панелями и сами отступаем от них и от клавиатуры
    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        val root = view<View>(R.id.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                it.setSystemBarsAppearance(light, light)
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
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

    @Deprecated("Кнопка «Назад» по-старому: работает на всех Android с 10-го")
    override fun onBackPressed() {
        if (web.canGoBack()) {
            offline.visibility = View.GONE
            web.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
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
        checkUpdate() // не чаще раза в 4 часа — Updater сам следит
    }

    override fun onPause() {
        Updater.pause(this)
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoRetry)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_CAMERA = 2
        private const val REQ_NOTIFY = 3
        private const val REQ_LOCATION = 4

        /** Коды «сервер лежит»: хостинг показывает свою заглушку, а мы — свой экран. */
        private val SERVER_DOWN = setOf(502, 503, 504, 521, 522, 523)

        /** Роли Песочницы: ключ, подпись и путь на копии сайта kopiya.argeda.ru. */
        private val ROLE_KEYS = arrayOf("buyer", "seller", "pvz", "courier", "admin")
        private val ROLE_PATHS = arrayOf("/", "/seller", "/pvz", "/courier", "/admin")
        private val ROLE_NAMES = intArrayOf(
            R.string.sandbox_role_buyer, R.string.sandbox_role_seller,
            R.string.sandbox_role_pvz, R.string.sandbox_role_courier, R.string.sandbox_role_admin
        )

        private fun roleIndex(key: String?): Int = ROLE_KEYS.indexOf(key).coerceAtLeast(0)

        // Ссылка на кабинет из уведомления, ярлыка или другого приложения
        private fun siteLink(intent: Intent?): String? {
            val data = intent?.data ?: return null
            if (Intent.ACTION_VIEW != intent.action) return null
            return if (Links.isOurs(data.host)) data.buildUpon().scheme("https").build().toString() else null
        }

        // Сайт уводит на страницу покупателя /account, если вошли не продавцом
        private fun isAccountPage(url: String?): Boolean {
            if (url == null) return false
            val u = Uri.parse(url)
            val host = u.host?.lowercase(Locale.ROOT) ?: ""
            val path = u.path ?: ""
            return Links.isOurs(host) && (path == "/account" || path.startsWith("/account/"))
        }
    }
}
