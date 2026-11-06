package ru.argeda.design;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Автообновление приложения без магазина. При запуске читает с сайта файл версий
 * (последний релиз GitHub или https://argeda.ru/app/version.json) и, если там версия новее установленной,
 * предлагает обновиться: APK скачивает DownloadManager в «Загрузки», после чего открывается
 * штатный установщик Android. Данные приложения при обновлении сохраняются.
 *
 * Файл версий — один на все приложения, ключ выбирает сборка:
 * {
 *   "argeda":  { "code": 14, "name": "1.13", "url": "https://argeda.ru/app/argeda.apk", "min": 11, "notes": "Что нового" },
 *   "seller":  { "code": 4, "name": "1.3", "url": "https://argeda.ru/app/argeda-seller.apk" },
 *   "pvz": {…}, "courier": {…}, "admin": {…}
 * }
 * code — versionCode новой сборки; min — ниже этого кода работать нельзя, окно не закрыть (пусто = можно «Позже»).
 * Установить поверх можно только APK с тем же ключом подписи, что и установленное приложение.
 */
final class Updater {
    private static final String PREFS = "updater";
    private static final long CHECK_EVERY = 4 * 60 * 60 * 1000L;   // не чаще раза в 4 часа
    private static final long RETRY_IN = 5 * 60 * 1000L;          // не вышло — пробуем снова через 5 минут
    private static final long LATER_FOR = 24 * 60 * 60 * 1000L;    // «Позже» действует сутки
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private Updater() {
    }

    /** Проверка при запуске: тихая, не чаще раза в 4 часа. */
    static void checkQuietly(Activity a, String versionsUrl, String key, int currentCode) {
        check(a, versionsUrl, key, currentCode, false, null);
    }

    /** То же, но сайт закрыт HTTP-паролем (копия): auth — готовый заголовок Authorization, например «Basic …». */
    static void checkQuietly(Activity a, String versionsUrl, String key, int currentCode, String auth) {
        check(a, versionsUrl, key, currentCode, false, auth);
    }

    /** Проверка по просьбе пользователя: всегда ходит на сервер и говорит результат. */
    static void checkNow(Activity a, String versionsUrl, String key, int currentCode) {
        check(a, versionsUrl, key, currentCode, true, null);
    }

    private static void check(final Activity a, final String versionsUrl, final String key, final int currentCode, final boolean manual, final String auth) {
        final SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - p.getLong("checked_at", 0) < CHECK_EVERY) return;
        // Пока идёт проверка (и если она не удастся) — повтор не раньше чем через 5 минут, чтобы не открыть два окна
        p.edit().putLong("checked_at", now - CHECK_EVERY + RETRY_IN).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject info = fetch(versionsUrl, key, auth);
                // Файл версий прочитали — следующая проверка через 4 часа; не вышло (нет интернета) — через 5 минут
                if (info != null) p.edit().putLong("checked_at", System.currentTimeMillis()).apply();
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing() || a.isDestroyed()) return;
                        if (info == null) {
                            if (manual) Toast.makeText(a, R.string.upd_check_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        offer(a, p, info, currentCode, manual, auth);
                    }
                });
            }
        }, "argeda-updater").start();
    }

    /** Читает файл версий и отдаёт запись нужного приложения; null, если не вышло. */
    private static JSONObject fetch(String versionsUrl, String key, String auth) {
        HttpURLConnection c = null;
        try {
            // Метка времени в адресе, чтобы не получить файл из кэша
            c = (HttpURLConnection) new URL(versionsUrl + (versionsUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 256 * 1024) buf.write(b, 0, n);
            JSONObject all = new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
            JSONObject info = all.optJSONObject(key);
            return info != null && info.optInt("code", 0) > 0 && info.optString("url", "").startsWith("https://") ? info : null;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void offer(final Activity a, final SharedPreferences p, JSONObject info, int currentCode, boolean manual, final String auth) {
        final int code = info.optInt("code", 0);
        final String name = info.optString("name", String.valueOf(code));
        final String url = info.optString("url", "");
        final boolean must = info.optInt("min", 0) > currentCode;
        String notes = info.optString("notes", "").trim();

        if (code <= currentCode) {
            if (manual) Toast.makeText(a, R.string.upd_latest, Toast.LENGTH_SHORT).show();
            return;
        }
        // Пользователь уже ответил «Позже» на эту версию — сутки не напоминаем (если версия не обязательная)
        if (!manual && !must && p.getInt("later_code", 0) == code
                && System.currentTimeMillis() - p.getLong("later_at", 0) < LATER_FOR) return;

        String text = a.getString(R.string.upd_text, name);
        if (!notes.isEmpty()) text = notes + "\n\n" + text;
        if (must) text = a.getString(R.string.upd_must) + "\n\n" + text;

        AlertDialog.Builder d = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.upd_title, name))
                .setMessage(text)
                .setCancelable(!must)
                .setPositiveButton(R.string.upd_install, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download(a, p, url, code, name, auth);
                    }
                });
        if (!must) {
            d.setNegativeButton(R.string.upd_later, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    p.edit().putInt("later_code", code).putLong("later_at", System.currentTimeMillis()).apply();
                }
            });
        }
        d.show();
    }

    /** Качает APK в «Загрузки» (или берёт уже скачанный той же версии) и открывает установщик. */
    private static void download(Activity a, SharedPreferences p, String url, int code, String name, String auth) {
        final Context app = a.getApplicationContext();
        final DownloadManager dm = app.getSystemService(DownloadManager.class);
        if (dm == null) {
            openInBrowser(a, url);
            return;
        }
        // Эту версию уже скачивали (например, установку отменили) — не качаем второй раз
        if (p.getInt("dl_code", 0) == code) {
            long old = p.getLong("dl_id", -1);
            if (old >= 0 && status(dm, old) == DownloadManager.STATUS_SUCCESSFUL) {
                Uri ready = dm.getUriForDownloadedFile(old);
                if (ready != null) {
                    install(app, ready);
                    return;
                }
            }
        }
        String file = app.getPackageName().replace("ru.argeda.", "argeda-") + "-" + name + ".apk";
        DownloadManager.Request r;
        try {
            r = new DownloadManager.Request(Uri.parse(url))
                    .setMimeType(APK_MIME)
                    .setTitle(app.getString(R.string.upd_downloading, name))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file);
            if (auth != null) r.addRequestHeader("Authorization", auth);
        } catch (IllegalArgumentException | IllegalStateException e) {
            openInBrowser(a, url);
            return;
        }
        final long id = dm.enqueue(r);
        p.edit().putInt("dl_code", code).putLong("dl_id", id).apply();
        Toast.makeText(app, R.string.upd_started, Toast.LENGTH_LONG).show();

        // Приёмник живёт на контексте приложения: установщик откроется, даже если экран успели закрыть
        BroadcastReceiver done = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                app.unregisterReceiver(this);
                Uri ready = status(dm, id) == DownloadManager.STATUS_SUCCESSFUL ? dm.getUriForDownloadedFile(id) : null;
                if (ready != null) install(app, ready);
                else Toast.makeText(app, R.string.upd_download_failed, Toast.LENGTH_LONG).show();
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(done, f, Context.RECEIVER_EXPORTED);
        else app.registerReceiver(done, f);
    }

    private static int status(DownloadManager dm, long id) {
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        if (c == null) return -1;
        try {
            if (!c.moveToFirst()) return -1;
            int col = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return col < 0 ? -1 : c.getInt(col);
        } finally {
            c.close();
        }
    }

    /** Штатный установщик Android: он сам спросит разрешение «устанавливать из этого источника». */
    private static void install(Context app, Uri apk) {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            app.startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(app, R.string.upd_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static void openInBrowser(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(a, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }
}
