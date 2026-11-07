package ru.argeda.cabinet;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Раз в 15 минут спрашивает у кабинета счётчики (GET /api/app/badge с cookie входа)
 * и показывает уведомление, если какой-то счётчик вырос с прошлой проверки.
 * Формат общий для всех кабинетов: любое число в ответе — счётчик; «ключ_text» — текст уведомления;
 * labels.ключ — заголовок; links.ключ — куда открыть. Пока адреса нет, служба молчит (404 и 401 не страшны).
 */
public class BadgeJobService extends JobService {
    private static final int JOB_ID = 1;
    private static final String PREFS = "badge";
    private Thread worker;

    /** Заголовки по умолчанию для известных счётчиков; сервер может прислать свои в labels. */
    private static final Map<String, String> LABELS = new HashMap<>();
    static {
        LABELS.put("new_orders", "Новые заказы");
        LABELS.put("unread_chats", "Сообщения покупателей");
        LABELS.put("new_reviews", "Новые отзывы");
        LABELS.put("new_returns", "Заявки на возврат");
        LABELS.put("new_questions", "Вопросы о товарах");
        LABELS.put("to_issue", "Готовы к выдаче");
        LABELS.put("to_accept", "Ожидают приёмки");
        LABELS.put("arrived", "Прибыло в пункт");
        LABELS.put("to_deliver", "Доставить сегодня");
        LABELS.put("to_pickup", "Забрать у продавцов");
        LABELS.put("new_sellers", "Заявки продавцов");
        LABELS.put("new_disputes", "Новые споры");
        LABELS.put("new_messages", "Новые сообщения");
        LABELS.put("new_repairs", "Записи на ремонт");
    }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        // Песочница — для проверок на копии сайта: уведомления о «новых заказах» копии не нужны
        if (BuildConfig.SANDBOX) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, BadgeJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(JobInfo.getMinPeriodMillis())
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(getApplicationContext());
                } catch (Exception ignored) {
                    // нет сети или сервер недоступен: проверим в следующий раз
                }
                jobFinished(params, false);
            }
        });
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }

    static void check(Context c) throws Exception {
        String cookie = CookieManager.getInstance().getCookie(Links.BASE);
        if (cookie == null || cookie.isEmpty()) return; // не вошли

        HttpURLConnection conn = (HttpURLConnection) new URL(Links.BADGE).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Cookie", cookie);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "ArgedaApp/" + BuildConfig.CABINET + "/" + BuildConfig.VERSION_NAME);
        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            return;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        apply(c, new JSONObject(sb.toString()));
    }

    /** Сравнивает счётчики с прошлыми и показывает уведомления. */
    static void apply(Context c, JSONObject badge) {
        Notifier.ensureChannels(c);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        JSONObject links = badge.optJSONObject("links");
        JSONObject labels = badge.optJSONObject("labels");

        Iterator<String> keys = badge.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!(badge.opt(key) instanceof Number) || key.endsWith("_text")) continue;
            int now = badge.optInt(key, 0);
            int was = p.getInt(key, -1);
            e.putInt(key, now);
            if (was < 0 || now <= was) continue; // первая проверка или ничего нового
            int fresh = now - was;
            String label = labels != null ? labels.optString(key, null) : null;
            if (label == null || label.isEmpty()) label = LABELS.get(key);
            if (label == null) label = key.replace('_', ' ');
            String title = fresh == 1 ? label : label + ": " + fresh;
            String body = badge.optString(key + "_text", c.getString(R.string.n_default_text));
            String url = links != null ? links.optString(key, Links.HOME) : Links.HOME;
            Notifier.show(c, Notifier.channelFor(key), 100 + Math.abs(key.hashCode() % 900), title, body, url);
        }
        e.apply();
    }

    /** Кабинет открыт и сам сообщил счётчики: запоминаем их, чтобы не слать уведомление о том, что уже видно. */
    static void remember(Context c, JSONObject badge) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        Iterator<String> keys = badge.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (badge.opt(key) instanceof Number) e.putInt(key, badge.optInt(key, 0));
        }
        e.apply();
    }

    /** При выходе из кабинета забываем счётчики. */
    static void reset(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
