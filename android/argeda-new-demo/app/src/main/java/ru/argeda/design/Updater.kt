package ru.argeda.design

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Автообновление приложения без магазина.
 *
 * 1. Проверка: читает файл версий (последний релиз GitHub) и, если там версия новее установленной, предлагает обновиться.
 * 2. Скачивание: сам, во внутреннюю папку приложения (cache/updates), через .part. Адреса пробуются по очереди, 3 круга:
 *    ссылка из version.json → файл релиза через API GitHub → browser_download_url из API.
 *    Готовый файл проверяется: размер, начало «PK», имя пакета и номер версии внутри APK.
 * 3. Разрешение «Установка неизвестных приложений»: если его нет, открываем настройки, а когда человек вернётся,
 *    [resume] из onResume продолжит установку.
 * 4. Установка: сессия PackageInstaller. На Android 12+ просим систему ставить без вопроса (USER_ACTION_NOT_REQUIRED):
 *    обновление самого себя тогда обычно проходит тихо — приложение закроется и обновится.
 *    Если система всё же хочет подтверждения (Android 7–11, первая установка, решение системы) — показываем её экран.
 *
 * Файл версий — один на все приложения, ключ выбирает сборка:
 * { "argeda": { "code": 14, "name": "1.13", "url": "https://…/argeda.apk", "min": 11, "notes": "Что нового" }, "seller": {…}, … }
 * code — versionCode новой сборки; min — ниже этого кода работать нельзя, окно не закрыть.
 * Установить поверх можно только APK с тем же ключом подписи, что и установленное приложение.
 */
internal object Updater {
    private const val PREFS = "updater"
    private const val CHECK_EVERY = 4 * 60 * 60 * 1000L   // не чаще раза в 4 часа
    private const val RETRY_IN = 5 * 60 * 1000L           // не вышло — пробуем снова через 5 минут
    private const val LATER_FOR = 24 * 60 * 60 * 1000L    // «Позже» действует сутки
    private const val ROUNDS = 3                          // кругов по всем адресам при скачивании
    private const val MAX_APK = 200L * 1024 * 1024
    private const val GH_LATEST = "https://github.com/"
    private const val EXTRA_CODE = "ru.argeda.update.CODE"

    private val MAIN = Handler(Looper.getMainLooper())

    /** Экран приложения, который сейчас на виду (между onResume и onPause). */
    private var shown = WeakReference<Activity>(null)
    /** Идёт скачивание или установка — второй раз не начинаем. */
    @Volatile
    private var busy = false
    /** Скачанный файл ждёт разрешения «Установка неизвестных приложений». */
    private var waitingFile: File? = null
    private var waitingCode = 0
    private var askedPermission = false
    /** Экран подтверждения системы, который не удалось показать (приложение было свёрнуто). */
    private var waitingConfirm: Intent? = null

    /** Откуда качать: адрес, заголовок Accept (или null) и пароль копии (или null). */
    private class Source(val url: String, val accept: String?, val auth: String? = null)

    // ───────────────────────── связь с экраном ─────────────────────────

    /** Вызывать из onResume: продолжает установку после разрешения или показывает отложенное подтверждение. */
    fun resume(a: Activity) {
        shown = WeakReference(a)
        val confirm = waitingConfirm
        if (confirm != null) {
            waitingConfirm = null
            startConfirm(a, confirm)
            return
        }
        val f = waitingFile ?: return
        if (canInstall(a)) {
            waitingFile = null
            installInBackground(a.applicationContext, f, waitingCode)
        } else if (askedPermission) {
            // Вернулись из настроек без разрешения — не мучаем, предложим в следующий раз
            waitingFile = null
            askedPermission = false
            busy = false
            Toast.makeText(a, R.string.upd_no_permission, Toast.LENGTH_LONG).show()
        } else {
            askPermission(a)
        }
    }

    /** Вызывать из onPause. */
    fun pause(a: Activity) {
        if (shown.get() === a) shown = WeakReference(null)
    }

    private fun visible(): Activity? {
        val a = shown.get()
        return if (a == null || a.isFinishing || a.isDestroyed) null else a
    }

    // ───────────────────────── проверка ─────────────────────────

    /** Проверка при запуске: тихая, не чаще раза в 4 часа. auth — для сайта с HTTP-паролем (копия), например «Basic …». */
    fun checkQuietly(a: Activity, versionsUrl: String, key: String, currentCode: Int, auth: String? = null) {
        check(a, versionsUrl, key, currentCode, false, auth)
    }

    /** Проверка по просьбе пользователя: всегда ходит на сервер и говорит результат. */
    fun checkNow(a: Activity, versionsUrl: String, key: String, currentCode: Int) {
        check(a, versionsUrl, key, currentCode, true, null)
    }

    private fun check(a: Activity, versionsUrl: String, key: String, currentCode: Int, manual: Boolean, auth: String?) {
        if (busy) {
            if (manual) Toast.makeText(a, R.string.upd_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - p.getLong("checked_at", 0) < CHECK_EVERY) return
        // Пока идёт проверка (и если она не удастся) — повтор не раньше чем через 5 минут, чтобы не открыть два окна
        p.edit().putLong("checked_at", now - CHECK_EVERY + RETRY_IN).apply()
        Thread({
            val info = fetchInfo(versionsUrl, key, auth)
            // Файл версий прочитали — следующая проверка через 4 часа; не вышло (нет интернета) — через 5 минут
            if (info != null) p.edit().putLong("checked_at", System.currentTimeMillis()).apply()
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (info == null) {
                    if (manual) Toast.makeText(a, R.string.upd_check_failed, Toast.LENGTH_SHORT).show()
                } else {
                    offer(a, p, info, currentCode, manual, auth)
                }
            }
        }, "argeda-updater").start()
    }

    /** Читает файл версий (2 круга по адресам) и отдаёт запись нужного приложения; null, если не вышло. */
    private fun fetchInfo(versionsUrl: String, key: String, auth: String?): JSONObject? {
        var spare: List<Source>? = null
        repeat(2) {
            try {
                val bust = versionsUrl + (if (versionsUrl.contains("?")) "&" else "?") + "t=" + System.currentTimeMillis()
                val info = parseInfo(getText(bust, "application/json", auth), key)
                if (info != null) return info
            } catch (ignored: IOException) {
            } catch (ignored: JSONException) {
            } catch (ignored: RuntimeException) {
            }
            val list = spare ?: mirrors(versionsUrl).also { spare = it }
            for (m in list) {
                try {
                    val info = parseInfo(getText(m.url, m.accept, null), key)
                    if (info != null) return info
                } catch (ignored: IOException) {
                } catch (ignored: JSONException) {
                } catch (ignored: RuntimeException) {
                }
            }
            pauseFor(2000)
        }
        return null
    }

    @Throws(JSONException::class)
    private fun parseInfo(json: String, key: String): JSONObject? {
        val info = JSONObject(json).optJSONObject(key)
        return if (info != null && info.optInt("code", 0) > 0 && info.optString("url", "").startsWith("https://")) info else null
    }

    private fun offer(a: Activity, p: SharedPreferences, info: JSONObject, currentCode: Int, manual: Boolean, auth: String?) {
        val code = info.optInt("code", 0)
        val name = info.optString("name", code.toString())
        val url = info.optString("url", "")
        val must = info.optInt("min", 0) > currentCode
        val notes = info.optString("notes", "").trim()

        if (code <= currentCode) {
            if (manual) Toast.makeText(a, R.string.upd_latest, Toast.LENGTH_SHORT).show()
            return
        }
        // Пользователь уже ответил «Позже» на эту версию — сутки не напоминаем (если версия не обязательная)
        if (!manual && !must && p.getInt("later_code", 0) == code &&
            System.currentTimeMillis() - p.getLong("later_at", 0) < LATER_FOR
        ) return

        var text = a.getString(R.string.upd_text, name)
        if (notes.isNotEmpty()) text = notes + "\n\n" + text
        if (must) text = a.getString(R.string.upd_must) + "\n\n" + text

        val d = AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.upd_title, name))
            .setMessage(text)
            .setCancelable(!must)
            .setPositiveButton(R.string.upd_install) { _, _ ->
                p.edit().putString("dl_url", url).apply()
                startDownload(a, url, code, auth)
            }
        if (!must) {
            d.setNegativeButton(R.string.upd_later) { _, _ ->
                p.edit().putInt("later_code", code).putLong("later_at", System.currentTimeMillis()).apply()
            }
        }
        d.show()
    }

    // ───────────────────────── скачивание ─────────────────────────

    /** Ход скачивания для окна; пишется из фонового потока. */
    private class Progress {
        @Volatile
        var done = 0L
        @Volatile
        var total = 0L
    }

    private fun startDownload(a: Activity, url: String, code: Int, auth: String?) {
        if (busy) {
            Toast.makeText(a, R.string.upd_busy, Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        val app = a.applicationContext
        val pr = Progress()
        val dlg = AlertDialog.Builder(a)
            .setTitle(R.string.upd_downloading_title)
            .setMessage(app.getString(R.string.upd_progress_start))
            .setCancelable(true)
            .setPositiveButton(R.string.upd_hide, null)
            .create()
        dlg.show()
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                dlg.setMessage(progressText(app, pr))
                MAIN.postDelayed(this, 400)
            }
        }
        MAIN.postDelayed(tick, 400)

        Thread({
            val ready: File? = try {
                download(app, url, code, auth, pr)
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            MAIN.post {
                MAIN.removeCallbacks(tick)
                try {
                    if (dlg.isShowing) dlg.dismiss()
                } catch (ignored: RuntimeException) {
                }
                if (ready == null) {
                    busy = false
                    Toast.makeText(app, R.string.upd_download_failed, Toast.LENGTH_LONG).show()
                } else {
                    install(app, ready, code)
                }
            }
        }, "argeda-update-download").start()
    }

    private fun progressText(app: Context, pr: Progress): String {
        val mb = 1024.0 * 1024.0
        val total = pr.total
        val done = pr.done
        if (total > 0) {
            return app.getString(
                R.string.upd_progress,
                String.format(Locale.US, "%.1f", done / mb), String.format(Locale.US, "%.1f", total / mb),
                (done * 100 / total).toInt()
            )
        }
        return app.getString(R.string.upd_progress_start)
    }

    /** Качает APK в cache/updates (или берёт уже скачанный той же версии). Бросает IOException, если ни один адрес не дал годный файл. */
    @Throws(IOException::class)
    private fun download(app: Context, url: String, code: Int, auth: String?, pr: Progress): File {
        val dir = File(app.cacheDir, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("нет папки")
        val out = File(dir, "update-$code.apk")
        if (out.isFile && isOurApk(app, out, code)) return out
        // Старые версии и недокачанные куски не копим
        dir.listFiles()?.forEach { it.delete() }

        val part = File(dir, out.name + ".part")
        var last = IOException("нет адресов")
        var spare: List<Source>? = null
        for (round in 0 until ROUNDS) {
            val list = ArrayList<Source>()
            list.add(Source(url, null, auth))
            spare?.let { list.addAll(it) }
            for (src in list) {
                try {
                    pr.done = 0
                    pr.total = 0
                    saveTo(src.url, src.accept, src.auth, part, pr)
                    if (!startsWithPk(part)) throw IOException("не APK")
                    if (!part.renameTo(out)) throw IOException("не переименовать")
                    if (!isOurApk(app, out, code)) {
                        out.delete()
                        throw IOException("чужой или старый APK")
                    }
                    return out
                } catch (e: IOException) {
                    part.delete()
                    last = e
                } catch (e: RuntimeException) {
                    part.delete()
                    last = IOException(e)
                }
            }
            if (spare == null) spare = mirrors(url) // ссылка из version.json не сработала — берём адреса из API GitHub
            if (round < ROUNDS - 1) pauseFor(3000L * (round + 1))
        }
        throw last
    }

    @Throws(IOException::class)
    private fun saveTo(url: String, accept: String?, auth: String?, part: File, pr: Progress) {
        val c = open(url, accept, auth, 30000)
        try {
            val len = c.contentLengthLong
            if (len > MAX_APK) throw IOException("слишком большой")
            pr.total = maxOf(len, 0L)
            var got = 0L
            c.inputStream.use { input ->
                FileOutputStream(part).use { o ->
                    val b = ByteArray(65536)
                    while (true) {
                        val n = input.read(b)
                        if (n <= 0) break
                        o.write(b, 0, n)
                        got += n
                        pr.done = got
                        if (got > MAX_APK) throw IOException("слишком большой")
                    }
                }
            }
            if (len > 0 && got != len) throw IOException("файл скачан не полностью")
            if (got < 10 * 1024) throw IOException("слишком маленький")
        } finally {
            c.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun startsWithPk(f: File): Boolean =
        FileInputStream(f).use { it.read() == 'P'.code && it.read() == 'K'.code }

    /** В APK наше приложение (то же имя пакета) и версия не ниже ожидаемой. */
    @Suppress("DEPRECATION")
    private fun isOurApk(app: Context, f: File, code: Int): Boolean = try {
        val pi = app.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
        if (pi == null || app.packageName != pi.packageName) {
            false
        } else {
            val v = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            v >= code
        }
    } catch (e: RuntimeException) {
        false
    }

    // ───────────────────────── запасные адреса ─────────────────────────

    /**
     * Для ссылки вида https://github.com/OWNER/REPO/releases/latest/download/FILE — тот же файл через API GitHub:
     * сначала адрес ассета с Accept: application/octet-stream, затем browser_download_url.
     */
    private fun mirrors(url: String): List<Source> {
        val out = ArrayList<Source>()
        try {
            if (!url.startsWith(GH_LATEST)) return out
            val s = url.substring(GH_LATEST.length).substringBefore('?').split("/")
            // OWNER / REPO / releases / latest / download / FILE
            if (s.size != 6 || s[2] != "releases" || s[3] != "latest" || s[4] != "download") return out
            val api = "https://api.github.com/repos/" + s[0] + "/" + s[1] + "/releases/latest"
            val rel = JSONObject(getText(api, "application/vnd.github+json", null))
            val assets = rel.optJSONArray("assets") ?: return out
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i)
                if (asset == null || s[5] != asset.optString("name")) continue
                val apiUrl = asset.optString("url", "")
                val direct = asset.optString("browser_download_url", "")
                if (apiUrl.startsWith("https://")) out.add(Source(apiUrl, "application/octet-stream"))
                if (direct.startsWith("https://")) out.add(Source(direct, null))
                break
            }
        } catch (ignored: IOException) {
        } catch (ignored: JSONException) {
        } catch (ignored: RuntimeException) {
        }
        return out
    }

    // ───────────────────────── HTTP ─────────────────────────

    /** Открывает https-адрес с ручными переадресациями (GitHub шлёт на release-assets.githubusercontent.com). Пароль копии — только своему хосту. */
    @Throws(IOException::class)
    private fun open(startUrl: String, accept: String?, auth: String?, readTimeout: Int): HttpURLConnection {
        var url = startUrl
        val firstHost = URL(url).host
        repeat(8) {
            val u = URL(url)
            if ("https" != u.protocol) throw IOException("только https")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = readTimeout
            c.setRequestProperty("User-Agent", "ARGEDA-Android-Updater")
            c.setRequestProperty("Cache-Control", "no-cache")
            if (accept != null) c.setRequestProperty("Accept", accept)
            if (auth != null && firstHost == u.host) c.setRequestProperty("Authorization", auth)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("переадресация без адреса")
                url = URL(u, loc).toString()
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            return c
        }
        throw IOException("слишком много переадресаций")
    }

    @Throws(IOException::class)
    private fun getText(url: String, accept: String?, auth: String?): String {
        val c = open(url, accept, auth, 10000)
        try {
            val buf = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val b = ByteArray(4096)
                while (buf.size() < 512 * 1024) {
                    val n = input.read(b)
                    if (n <= 0) break
                    buf.write(b, 0, n)
                }
            }
            return buf.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    private fun pauseFor(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ───────────────────────── разрешение и установка ─────────────────────────

    private fun canInstall(c: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Вызывается на главном потоке, когда файл скачан и проверен. */
    private fun install(app: Context, apk: File, code: Int) {
        if (!canInstall(app)) {
            waitingFile = apk
            waitingCode = code
            askedPermission = false
            visible()?.let { askPermission(it) }
            // Иначе спросим, когда человек вернётся в приложение (resume)
            return
        }
        installInBackground(app, apk, code)
    }

    /** Объясняет и открывает настройки «Установка неизвестных приложений» именно для нашего приложения. */
    private fun askPermission(a: Activity) {
        askedPermission = true
        AlertDialog.Builder(a)
            .setTitle(R.string.upd_perm_title)
            .setMessage(R.string.upd_perm_text)
            .setCancelable(false)
            .setPositiveButton(R.string.upd_perm_open) { _, _ ->
                try {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                } catch (e: RuntimeException) {
                    waitingFile = null
                    busy = false
                    Toast.makeText(a, R.string.upd_open_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.upd_later) { _, _ ->
                waitingFile = null
                askedPermission = false
                busy = false
            }
            .show()
    }

    private fun installInBackground(app: Context, apk: File, code: Int) {
        busy = true
        Toast.makeText(app, R.string.upd_installing, Toast.LENGTH_LONG).show()
        Thread({
            val ok = commitSession(app, apk, code)
            if (!ok) {
                MAIN.post {
                    busy = false
                    failed(app, R.string.upd_install_failed, true)
                }
            }
        }, "argeda-update-install").start()
    }

    /** Пишет APK в сессию PackageInstaller и отправляет её системе. Ответ придёт в [UpdateReceiver]. */
    private fun commitSession(app: Context, apk: File, code: Int): Boolean {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 26) params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        // Android 12+: обновление самого себя без экрана подтверждения (система может всё равно спросить)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        var id = -1
        var s: PackageInstaller.Session? = null
        try {
            id = installer.createSession(params)
            val session = installer.openSession(id)
            s = session
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                FileInputStream(apk).use { input ->
                    val b = ByteArray(65536)
                    while (true) {
                        val n = input.read(b)
                        if (n <= 0) break
                        out.write(b, 0, n)
                    }
                }
                session.fsync(out)
            }
            val cb = Intent(app, UpdateReceiver::class.java).setPackage(app.packageName).putExtra(EXTRA_CODE, code)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, id, cb, flags)
            session.commit(pi.intentSender)
            return true
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            if (s != null) {
                try {
                    s.abandon()
                } catch (ignored: RuntimeException) {
                }
            } else if (id >= 0) {
                try {
                    installer.abandonSession(id)
                } catch (ignored: RuntimeException) {
                }
            }
            return false
        } finally {
            s?.close()
        }
    }

    /** Ответ системы об установке (из UpdateReceiver, главный поток). */
    @Suppress("DEPRECATION")
    fun onStatus(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Система хочет подтверждения: показываем её экран «Обновить приложение?»
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    busy = false
                    failed(app, R.string.upd_install_failed, true)
                    return
                }
                val a = visible()
                if (a != null) startConfirm(a, confirm) else waitingConfirm = confirm // приложение свёрнуто — покажем, когда вернутся
            }
            PackageInstaller.STATUS_SUCCESS -> {
                busy = false
                cleanup(app)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // Нажали «Отмена» — файл оставляем, следующее «Обновить» поставит без нового скачивания
                busy = false
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                busy = false
                cleanup(app)
                failed(app, R.string.upd_wrong_key, false)
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                busy = false
                failed(app, R.string.upd_no_space, false)
            }
            else -> {
                busy = false
                cleanup(app)
                failed(app, R.string.upd_install_failed, true)
            }
        }
    }

    private fun startConfirm(a: Activity, confirm: Intent) {
        try {
            a.startActivity(confirm)
        } catch (e: RuntimeException) {
            busy = false
            failed(a.applicationContext, R.string.upd_install_failed, true)
        }
    }

    /** Сообщает об ошибке; при withBrowser и открытом приложении предлагает скачать APK браузером (запасной путь). */
    private fun failed(app: Context, msg: Int, withBrowser: Boolean) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
        if (!withBrowser) return
        val a = visible() ?: return
        val url = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dl_url", "") ?: ""
        if (!url.startsWith("https://")) return
        AlertDialog.Builder(a)
            .setMessage(R.string.upd_try_browser)
            .setPositiveButton(R.string.upd_browser) { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: RuntimeException) {
                    Toast.makeText(a, R.string.no_app, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.upd_later, null)
            .show()
    }

    private fun cleanup(app: Context) {
        File(app.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }
}
