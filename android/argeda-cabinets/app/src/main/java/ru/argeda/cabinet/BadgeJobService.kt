package ru.argeda.cabinet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Раз в 15 минут спрашивает у кабинета счётчики (GET /api/app/badge с cookie входа)
 * и показывает уведомление, если какой-то счётчик вырос с прошлой проверки.
 * Формат общий для всех кабинетов: любое число в ответе — счётчик; «ключ_text» — текст уведомления;
 * labels.ключ — заголовок; links.ключ — куда открыть. Пока адреса нет, служба молчит (404 и 401 не страшны).
 */
class BadgeJobService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            try {
                check(applicationContext)
            } catch (ignored: Exception) {
                // нет сети или сервер недоступен: проверим в следующий раз
            }
            jobFinished(params, false)
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val JOB_ID = 1
        private const val PREFS = "badge"

        /** Заголовки по умолчанию для известных счётчиков; сервер может прислать свои в labels. */
        private val LABELS = mapOf(
            "new_orders" to "Новые заказы",
            "unread_chats" to "Сообщения покупателей",
            "new_reviews" to "Новые отзывы",
            "new_returns" to "Заявки на возврат",
            "new_questions" to "Вопросы о товарах",
            "to_issue" to "Готовы к выдаче",
            "to_accept" to "Ожидают приёмки",
            "arrived" to "Прибыло в пункт",
            "to_deliver" to "Доставить сегодня",
            "to_pickup" to "Забрать у продавцов",
            "new_sellers" to "Заявки продавцов",
            "new_disputes" to "Новые споры",
            "new_messages" to "Новые сообщения",
            "new_repairs" to "Записи на ремонт",
        )

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            // Песочница — для проверок на копии сайта: уведомления о «новых заказах» копии не нужны
            if (BuildConfig.SANDBOX) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, BadgeJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(JobInfo.getMinPeriodMillis())
                .setPersisted(true)
                .build()
            js.schedule(job)
        }

        @Throws(Exception::class)
        fun check(c: Context) {
            val cookie = CookieManager.getInstance().getCookie(Links.BASE)
            if (cookie.isNullOrEmpty()) return // не вошли

            val conn = URL(Links.BADGE).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "ArgedaApp/" + BuildConfig.CABINET + "/" + BuildConfig.VERSION_NAME)
            try {
                if (conn.responseCode != 200) return
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                apply(c, JSONObject(text))
            } finally {
                conn.disconnect()
            }
        }

        /** Сравнивает счётчики с прошлыми и показывает уведомления. */
        fun apply(c: Context, badge: JSONObject) {
            Notifier.ensureChannels(c)
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val e = p.edit()
            val links = badge.optJSONObject("links")
            val labels = badge.optJSONObject("labels")

            for (key in badge.keys()) {
                if (badge.opt(key) !is Number || key.endsWith("_text")) continue
                val now = badge.optInt(key, 0)
                val was = p.getInt(key, -1)
                e.putInt(key, now)
                if (was < 0 || now <= was) continue // первая проверка или ничего нового
                val fresh = now - was
                var label: String? = labels?.optString(key, null)
                if (label.isNullOrEmpty()) label = LABELS[key]
                if (label == null) label = key.replace('_', ' ')
                val title = if (fresh == 1) label else "$label: $fresh"
                val body = badge.optString(key + "_text", c.getString(R.string.n_default_text))
                val url = links?.optString(key, Links.HOME) ?: Links.HOME
                Notifier.show(c, Notifier.channelFor(key), 100 + abs(key.hashCode() % 900), title, body, url)
            }
            e.apply()
        }

        /** Кабинет открыт и сам сообщил счётчики: запоминаем их, чтобы не слать уведомление о том, что уже видно. */
        fun remember(c: Context, badge: JSONObject) {
            val e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            for (key in badge.keys()) {
                if (badge.opt(key) is Number) e.putInt(key, badge.optInt(key, 0))
            }
            e.apply()
        }

        /** При выходе из кабинета забываем счётчики. */
        fun reset(c: Context) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
