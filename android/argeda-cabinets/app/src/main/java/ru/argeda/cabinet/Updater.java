package ru.argeda.cabinet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Автообновление приложения без магазина.
 *
 * 1. Проверка: читает файл версий (последний релиз GitHub) и, если там версия новее установленной, предлагает обновиться.
 * 2. Скачивание: сам, во внутреннюю папку приложения (cache/updates), через .part. Адреса пробуются по очереди, 3 круга:
 *    ссылка из version.json → файл релиза через API GitHub → browser_download_url из API.
 *    Готовый файл проверяется: размер, начало «PK», имя пакета и номер версии внутри APK.
 * 3. Разрешение «Установка неизвестных приложений»: если его нет, открываем настройки, а когда человек вернётся,
 *    {@link #resume(Activity)} из onResume продолжит установку.
 * 4. Установка: сессия PackageInstaller. На Android 12+ просим систему ставить без вопроса (USER_ACTION_NOT_REQUIRED):
 *    обновление самого себя тогда обычно проходит тихо — приложение закроется и обновится.
 *    Если система всё же хочет подтверждения (Android 7–11, первая установка, решение системы) — показываем её экран.
 *
 * Файл версий — один на все приложения, ключ выбирает сборка:
 * { "argeda": { "code": 14, "name": "1.13", "url": "https://…/argeda.apk", "min": 11, "notes": "Что нового" }, "seller": {…}, … }
 * code — versionCode новой сборки; min — ниже этого кода работать нельзя, окно не закрыть.
 * Установить поверх можно только APK с тем же ключом подписи, что и установленное приложение.
 * Без лямбд: сборка без SDK их не переваривает.
 */
final class Updater {
    private static final String PREFS = "updater";
    private static final long CHECK_EVERY = 4 * 60 * 60 * 1000L;   // не чаще раза в 4 часа
    private static final long RETRY_IN = 5 * 60 * 1000L;          // не вышло — пробуем снова через 5 минут
    private static final long LATER_FOR = 24 * 60 * 60 * 1000L;    // «Позже» действует сутки
    private static final int ROUNDS = 3;                          // кругов по всем адресам при скачивании
    private static final long MAX_APK = 200L * 1024 * 1024;
    private static final String GH_LATEST = "https://github.com/";
    private static final String EXTRA_CODE = "ru.argeda.update.CODE";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Экран приложения, который сейчас на виду (между onResume и onPause). */
    private static WeakReference<Activity> shown = new WeakReference<Activity>(null);
    /** Идёт скачивание или установка — второй раз не начинаем. */
    private static volatile boolean busy;
    /** Скачанный файл ждёт разрешения «Установка неизвестных приложений». */
    private static File waitingFile;
    private static int waitingCode;
    private static boolean askedPermission;
    /** Экран подтверждения системы, который не удалось показать (приложение было свёрнуто). */
    private static Intent waitingConfirm;

    private Updater() {
    }

    // ───────────────────────── связь с экраном ─────────────────────────

    /** Вызывать из onResume: продолжает установку после разрешения или показывает отложенное подтверждение. */
    static void resume(Activity a) {
        shown = new WeakReference<Activity>(a);
        if (waitingConfirm != null) {
            Intent confirm = waitingConfirm;
            waitingConfirm = null;
            startConfirm(a, confirm);
            return;
        }
        if (waitingFile == null) return;
        if (canInstall(a)) {
            File f = waitingFile;
            int code = waitingCode;
            waitingFile = null;
            installInBackground(a.getApplicationContext(), f, code);
        } else if (askedPermission) {
            // Вернулись из настроек без разрешения — не мучаем, предложим в следующий раз
            waitingFile = null;
            askedPermission = false;
            busy = false;
            Toast.makeText(a, R.string.upd_no_permission, Toast.LENGTH_LONG).show();
        } else {
            askPermission(a);
        }
    }

    /** Вызывать из onPause. */
    static void pause(Activity a) {
        if (shown.get() == a) shown = new WeakReference<Activity>(null);
    }

    private static Activity visible() {
        Activity a = shown.get();
        return a == null || a.isFinishing() || a.isDestroyed() ? null : a;
    }

    // ───────────────────────── проверка ─────────────────────────

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
        if (busy) {
            if (manual) Toast.makeText(a, R.string.upd_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        final SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - p.getLong("checked_at", 0) < CHECK_EVERY) return;
        // Пока идёт проверка (и если она не удастся) — повтор не раньше чем через 5 минут, чтобы не открыть два окна
        p.edit().putLong("checked_at", now - CHECK_EVERY + RETRY_IN).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject info = fetchInfo(versionsUrl, key, auth);
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

    /** Читает файл версий (2 круга по адресам) и отдаёт запись нужного приложения; null, если не вышло. */
    private static JSONObject fetchInfo(String versionsUrl, String key, String auth) {
        List<String[]> spare = null;
        for (int round = 0; round < 2; round++) {
            try {
                String bust = versionsUrl + (versionsUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
                JSONObject info = parseInfo(getText(bust, "application/json", auth), key);
                if (info != null) return info;
            } catch (IOException | JSONException | RuntimeException ignored) {
            }
            if (spare == null) spare = mirrors(versionsUrl);
            for (String[] m : spare) {
                try {
                    JSONObject info = parseInfo(getText(m[0], m[1], null), key);
                    if (info != null) return info;
                } catch (IOException | JSONException | RuntimeException ignored) {
                }
            }
            pauseFor(2000);
        }
        return null;
    }

    private static JSONObject parseInfo(String json, String key) throws JSONException {
        JSONObject info = new JSONObject(json).optJSONObject(key);
        return info != null && info.optInt("code", 0) > 0 && info.optString("url", "").startsWith("https://") ? info : null;
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
                        p.edit().putString("dl_url", url).apply();
                        startDownload(a, url, code, auth);
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

    // ───────────────────────── скачивание ─────────────────────────

    /** Ход скачивания для окна; пишется из фонового потока. */
    private static final class Progress {
        volatile long done;
        volatile long total;
    }

    private static void startDownload(final Activity a, final String url, final int code, final String auth) {
        if (busy) {
            Toast.makeText(a, R.string.upd_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        final Context app = a.getApplicationContext();
        final Progress pr = new Progress();
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(R.string.upd_downloading_title)
                .setMessage(app.getString(R.string.upd_progress_start))
                .setCancelable(true)
                .setPositiveButton(R.string.upd_hide, null)
                .create();
        dlg.show();
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dlg.isShowing()) return;
                dlg.setMessage(progressText(app, pr));
                MAIN.postDelayed(this, 400);
            }
        };
        MAIN.postDelayed(tick, 400);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File apk = null;
                try {
                    apk = download(app, url, code, auth, pr);
                } catch (IOException | RuntimeException ignored) {
                }
                final File ready = apk;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        MAIN.removeCallbacks(tick);
                        try {
                            if (dlg.isShowing()) dlg.dismiss();
                        } catch (RuntimeException ignored) {
                        }
                        if (ready == null) {
                            busy = false;
                            Toast.makeText(app, R.string.upd_download_failed, Toast.LENGTH_LONG).show();
                        } else {
                            install(app, ready, code);
                        }
                    }
                });
            }
        }, "argeda-update-download").start();
    }

    private static String progressText(Context app, Progress pr) {
        double mb = 1024.0 * 1024.0;
        if (pr.total > 0) {
            return app.getString(R.string.upd_progress,
                    String.format(Locale.US, "%.1f", pr.done / mb), String.format(Locale.US, "%.1f", pr.total / mb),
                    (int) (pr.done * 100 / pr.total));
        }
        return app.getString(R.string.upd_progress_start);
    }

    /** Качает APK в cache/updates (или берёт уже скачанный той же версии). Бросает IOException, если ни один адрес не дал годный файл. */
    private static File download(Context app, String url, int code, String auth, Progress pr) throws IOException {
        File dir = new File(app.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("нет папки");
        File out = new File(dir, "update-" + code + ".apk");
        if (out.isFile() && isOurApk(app, out, code)) return out;
        // Старые версии и недокачанные куски не копим
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();

        File part = new File(dir, out.getName() + ".part");
        IOException last = new IOException("нет адресов");
        List<String[]> spare = null;
        for (int round = 0; round < ROUNDS; round++) {
            List<String[]> list = new ArrayList<String[]>();
            list.add(new String[]{url, null, auth});
            if (spare != null) list.addAll(spare);
            for (String[] src : list) {
                try {
                    pr.done = 0;
                    pr.total = 0;
                    saveTo(src[0], src[1], src.length > 2 ? src[2] : null, part, pr);
                    if (!startsWithPk(part)) throw new IOException("не APK");
                    if (!part.renameTo(out)) throw new IOException("не переименовать");
                    if (!isOurApk(app, out, code)) {
                        out.delete();
                        throw new IOException("чужой или старый APK");
                    }
                    return out;
                } catch (IOException | RuntimeException e) {
                    part.delete();
                    last = e instanceof IOException ? (IOException) e : new IOException(e);
                }
            }
            if (spare == null) spare = mirrors(url); // ссылка из version.json не сработала — берём адреса из API GitHub
            if (round < ROUNDS - 1) pauseFor(3000L * (round + 1));
        }
        throw last;
    }

    private static void saveTo(String url, String accept, String auth, File part, Progress pr) throws IOException {
        HttpURLConnection c = open(url, accept, auth, 30000);
        try {
            long len = c.getContentLengthLong();
            if (len > MAX_APK) throw new IOException("слишком большой");
            pr.total = Math.max(len, 0);
            InputStream in = c.getInputStream();
            OutputStream o = new FileOutputStream(part);
            long got = 0;
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    got += n;
                    pr.done = got;
                    if (got > MAX_APK) throw new IOException("слишком большой");
                }
            } finally {
                o.close();
                in.close();
            }
            if (len > 0 && got != len) throw new IOException("файл скачан не полностью");
            if (got < 10 * 1024) throw new IOException("слишком маленький");
        } finally {
            c.disconnect();
        }
    }

    private static boolean startsWithPk(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return in.read() == 'P' && in.read() == 'K';
        } finally {
            in.close();
        }
    }

    /** В APK наше приложение (то же имя пакета) и версия не ниже ожидаемой. */
    @SuppressWarnings("deprecation")
    private static boolean isOurApk(Context app, File f, int code) {
        try {
            PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || !app.getPackageName().equals(pi.packageName)) return false;
            long v = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return v >= code;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ───────────────────────── запасные адреса ─────────────────────────

    /**
     * Для ссылки вида https://github.com/OWNER/REPO/releases/latest/download/FILE — тот же файл через API GitHub:
     * сначала адрес ассета с Accept: application/octet-stream, затем browser_download_url. Каждый элемент — {адрес, Accept}.
     */
    private static List<String[]> mirrors(String url) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            if (!url.startsWith(GH_LATEST)) return out;
            String rest = url.substring(GH_LATEST.length());
            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);
            String[] s = rest.split("/");
            // OWNER / REPO / releases / latest / download / FILE
            if (s.length != 6 || !"releases".equals(s[2]) || !"latest".equals(s[3]) || !"download".equals(s[4])) return out;
            String api = "https://api.github.com/repos/" + s[0] + "/" + s[1] + "/releases/latest";
            JSONObject rel = new JSONObject(getText(api, "application/vnd.github+json", null));
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) return out;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject as = assets.optJSONObject(i);
                if (as == null || !s[5].equals(as.optString("name"))) continue;
                String apiUrl = as.optString("url", "");
                String direct = as.optString("browser_download_url", "");
                if (apiUrl.startsWith("https://")) out.add(new String[]{apiUrl, "application/octet-stream"});
                if (direct.startsWith("https://")) out.add(new String[]{direct, null});
                break;
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
        }
        return out;
    }

    // ───────────────────────── HTTP ─────────────────────────

    /** Открывает https-адрес с ручными переадресациями (GitHub шлёт на release-assets.githubusercontent.com). Пароль копии — только своему хосту. */
    private static HttpURLConnection open(String url, String accept, String auth, int readTimeout) throws IOException {
        String firstHost = new URL(url).getHost();
        for (int hop = 0; hop < 8; hop++) {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol())) throw new IOException("только https");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("User-Agent", "ARGEDA-Android-Updater");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (accept != null) c.setRequestProperty("Accept", accept);
            if (auth != null && firstHost.equals(u.getHost())) c.setRequestProperty("Authorization", auth);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("переадресация без адреса");
                url = new URL(u, loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("слишком много переадресаций");
    }

    private static String getText(String url, String accept, String auth) throws IOException {
        HttpURLConnection c = open(url, accept, auth, 10000);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 512 * 1024) buf.write(b, 0, n);
            in.close();
            return buf.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    private static void pauseFor(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ───────────────────────── разрешение и установка ─────────────────────────

    private static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    /** Вызывается на главном потоке, когда файл скачан и проверен. */
    private static void install(Context app, File apk, int code) {
        if (!canInstall(app)) {
            waitingFile = apk;
            waitingCode = code;
            askedPermission = false;
            Activity a = visible();
            if (a != null) askPermission(a);
            // Иначе спросим, когда человек вернётся в приложение (resume)
            return;
        }
        installInBackground(app, apk, code);
    }

    /** Объясняет и открывает настройки «Установка неизвестных приложений» именно для нашего приложения. */
    private static void askPermission(final Activity a) {
        askedPermission = true;
        new AlertDialog.Builder(a)
                .setTitle(R.string.upd_perm_title)
                .setMessage(R.string.upd_perm_text)
                .setCancelable(false)
                .setPositiveButton(R.string.upd_perm_open, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (RuntimeException e) {
                            waitingFile = null;
                            busy = false;
                            Toast.makeText(a, R.string.upd_open_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(R.string.upd_later, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        waitingFile = null;
                        askedPermission = false;
                        busy = false;
                    }
                })
                .show();
    }

    private static void installInBackground(final Context app, final File apk, final int code) {
        busy = true;
        Toast.makeText(app, R.string.upd_installing, Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = commitSession(app, apk, code);
                if (!ok) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            failed(app, R.string.upd_install_failed, true);
                        }
                    });
                }
            }
        }, "argeda-update-install").start();
    }

    /** Пишет APK в сессию PackageInstaller и отправляет её системе. Ответ придёт в {@link UpdateReceiver}. */
    private static boolean commitSession(Context app, File apk, int code) {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 26) params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        // Android 12+: обновление самого себя без экрана подтверждения (система может всё равно спросить)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = -1;
        PackageInstaller.Session s = null;
        try {
            id = installer.createSession(params);
            s = installer.openSession(id);
            OutputStream out = s.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            } finally {
                in.close();
                out.close();
            }
            Intent cb = new Intent(app, UpdateReceiver.class).setPackage(app.getPackageName()).putExtra(EXTRA_CODE, code);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(app, id, cb, flags);
            s.commit(pi.getIntentSender());
            return true;
        } catch (IOException | RuntimeException e) {
            if (s != null) {
                try {
                    s.abandon();
                } catch (RuntimeException ignored) {
                }
            } else if (id >= 0) {
                try {
                    installer.abandonSession(id);
                } catch (RuntimeException ignored) {
                }
            }
            return false;
        } finally {
            if (s != null) s.close();
        }
    }

    /** Ответ системы об установке (из UpdateReceiver, главный поток). */
    @SuppressWarnings("deprecation")
    static void onStatus(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (st) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                // Система хочет подтверждения: показываем её экран «Обновить приложение?»
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    busy = false;
                    failed(app, R.string.upd_install_failed, true);
                    return;
                }
                Activity a = visible();
                if (a != null) startConfirm(a, confirm);
                else waitingConfirm = confirm; // приложение свёрнуто — покажем, когда вернутся
                return;
            }
            case PackageInstaller.STATUS_SUCCESS:
                busy = false;
                cleanup(app);
                return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // Нажали «Отмена» — файл оставляем, следующее «Обновить» поставит без нового скачивания
                busy = false;
                return;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                busy = false;
                cleanup(app);
                failed(app, R.string.upd_wrong_key, false);
                return;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                busy = false;
                failed(app, R.string.upd_no_space, false);
                return;
            default:
                busy = false;
                cleanup(app);
                failed(app, R.string.upd_install_failed, true);
        }
    }

    private static void startConfirm(Activity a, Intent confirm) {
        try {
            a.startActivity(confirm);
        } catch (RuntimeException e) {
            busy = false;
            failed(a.getApplicationContext(), R.string.upd_install_failed, true);
        }
    }

    /** Сообщает об ошибке; при withBrowser и открытом приложении предлагает скачать APK браузером (запасной путь). */
    private static void failed(Context app, int msg, boolean withBrowser) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
        if (!withBrowser) return;
        final Activity a = visible();
        final String url = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dl_url", "");
        if (a == null || !url.startsWith("https://")) return;
        new AlertDialog.Builder(a)
                .setMessage(R.string.upd_try_browser)
                .setPositiveButton(R.string.upd_browser, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (RuntimeException e) {
                            Toast.makeText(a, R.string.no_app, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    private static void cleanup(Context app) {
        File[] old = new File(app.getCacheDir(), "updates").listFiles();
        if (old != null) for (File f : old) f.delete();
    }
}
