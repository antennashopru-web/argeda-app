package ru.argeda.cabinet;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.webkit.DownloadListener;
import android.text.InputType;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Приложения кабинетов ARGEDA (продавец, пункт выдачи, курьер, админка): внутри живой кабинет
 * с сайта, поэтому разделы и поведение всегда такие же, как на сайте. Приложение добавляет
 * уведомления, сохранение файлов в «Загрузки», камеру, геолокацию и ярлыки. Какой кабинет — решает сборка.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_NOTIFY = 3;
    private static final int REQ_LOCATION = 4;

    private WebView web;
    private ProgressBar progress;
    private View offline;
    private TextView offlineTitle, offlineText, netbar;
    private Net net;
    private String lastUrl;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest cameraRequest;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private boolean signingOut;
    private boolean notMemberShown;
    private TextView sandbar;
    private String baseUa;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Сервер не отвечает, а интернет есть: пробуем открыть кабинет снова раз в 30 секунд
    private final Runnable autoRetry = new Runnable() {
        @Override
        public void run() {
            if (offline.getVisibility() == View.VISIBLE && net != null && net.isOnline() && !isFinishing()) {
                offline.setVisibility(View.GONE);
                web.loadUrl(lastUrl != null ? lastUrl : home());
            }
        }
    };

    /** Роли Песочницы: ключ, подпись и путь на копии сайта kopiya.argeda.ru. */
    private static final String[] ROLE_KEYS = {"buyer", "seller", "pvz", "courier", "admin"};
    private static final String[] ROLE_PATHS = {"/", "/seller", "/pvz", "/courier", "/admin"};
    private static final int[] ROLE_NAMES = {R.string.sandbox_role_buyer, R.string.sandbox_role_seller,
            R.string.sandbox_role_pvz, R.string.sandbox_role_courier, R.string.sandbox_role_admin};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        offline = findViewById(R.id.offline);
        offlineTitle = findViewById(R.id.offline_title);
        offlineText = findViewById(R.id.offline_text);
        netbar = findViewById(R.id.netbar);
        findViewById(R.id.retry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                offline.setVisibility(View.GONE);
                web.loadUrl(lastUrl != null ? lastUrl : home());
            }
        });
        drawBehindSystemBars();
        setupWebView();
        // Без интернета кабинет открывается из кэша WebView с плашкой «Нет связи»; связь вернулась — плашка уходит,
        // а если страница так и не открылась, пробуем снова сами
        net = new Net(this, web, new Net.Listener() {
            @Override
            public void onNetChanged(boolean online) {
                netbar.setVisibility(online ? View.GONE : View.VISIBLE);
                if (online && offline.getVisibility() == View.VISIBLE) {
                    offline.setVisibility(View.GONE);
                    web.loadUrl(lastUrl != null ? lastUrl : home());
                }
            }
        });
        netbar.setVisibility(net.isOnline() ? View.GONE : View.VISIBLE);
        if (BuildConfig.SANDBOX) setupSandbox();
        if (state == null || web.restoreState(state) == null) {
            String link = siteLink(getIntent());
            if (link != null) {
                if (BuildConfig.SANDBOX) rememberRoleOf(link);
                web.loadUrl(link);
            } else if (BuildConfig.SANDBOX && role() == null) {
                pickRole(); // первый запуск Песочницы: сначала выбираем, кем зайти
            } else {
                web.loadUrl(home());
            }
        }
        // Раньше копии хранили пароль сайта-копии для обновлений — теперь он не нужен, стираем
        getSharedPreferences("site", MODE_PRIVATE).edit().remove("auth").apply();
        Notifier.ensureChannels(this);
        askNotifications();
        BadgeJobService.schedule(this);
        checkUpdate();
    }

    // Кабинеты и Песочница обновляются из последнего релиза GitHub; ключ в version.json — seller, pvz, courier, admin, sandbox
    private void checkUpdate() {
        String url = "https://github.com/antennashopru-web/argeda-app/releases/latest/download/version.json";
        Updater.checkQuietly(this, url, BuildConfig.CABINET, BuildConfig.VERSION_CODE);
    }

    /** Стартовая страница: кабинет сборки, а в Песочнице — выбранная роль на копии сайта. */
    private String home() {
        if (!BuildConfig.SANDBOX) return Links.HOME;
        return "https://" + Links.KOPIYA_HOST + ROLE_PATHS[roleIndex(role())] + "?utm_source=sandbox_app";
    }

    // ---------- Песочница ----------

    private SharedPreferences sandboxPrefs() {
        return getSharedPreferences("sandbox", MODE_PRIVATE);
    }

    private String role() {
        return sandboxPrefs().getString("role", null);
    }

    private static int roleIndex(String key) {
        for (int i = 0; i < ROLE_KEYS.length; i++) if (ROLE_KEYS[i].equals(key)) return i;
        return 0;
    }

    private void setupSandbox() {
        sandbar = findViewById(R.id.sandbar);
        sandbar.setVisibility(View.VISIBLE);
        sandbar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRole();
            }
        });
        applyRole();
    }

    /** Надпись на полосе и метка в адресе браузера: кабинет на копии видит, какую роль проверяют. */
    private void applyRole() {
        int i = roleIndex(role());
        sandbar.setText(getString(R.string.sandbox_bar, getString(ROLE_NAMES[i])));
        String key = role() == null ? "sandbox" : ROLE_KEYS[i];
        web.getSettings().setUserAgentString(baseUa + " ArgedaApp/" + key + "/" + BuildConfig.VERSION_NAME + " ArgedaSandbox");
    }

    private void setRole(String key) {
        sandboxPrefs().edit().putString("role", key).apply();
        applyRole();
    }

    // Ссылка (ярлык, другое приложение) ведёт в раздел копии — запоминаем роль по пути: /seller…, /pvz…
    private void rememberRoleOf(String url) {
        String path = Uri.parse(url).getPath();
        if (path == null) path = "/";
        String key = "buyer";
        for (int i = 1; i < ROLE_KEYS.length; i++) {
            if (path.equals(ROLE_PATHS[i]) || path.startsWith(ROLE_PATHS[i] + "/")) key = ROLE_KEYS[i];
        }
        setRole(key);
    }

    private void pickRole() {
        if (isFinishing()) return;
        String[] items = new String[ROLE_KEYS.length + 1];
        for (int i = 0; i < ROLE_KEYS.length; i++) items[i] = getString(ROLE_NAMES[i]);
        items[ROLE_KEYS.length] = getString(R.string.sandbox_signout);
        final boolean first = role() == null;
        new AlertDialog.Builder(this)
                .setTitle(R.string.sandbox_pick)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == ROLE_KEYS.length) {
                            // Вход на копии один на все роли: чтобы зайти другим аккаунтом, сначала выходим
                            if (first) setRole("buyer");
                            signingOut = true;
                            web.loadUrl(Links.SIGNOUT);
                            return;
                        }
                        setRole(ROLE_KEYS[which]);
                        offline.setVisibility(View.GONE);
                        web.loadUrl(home());
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        // Закрыли окно при первом запуске — открываем как покупатель, сменить можно по полосе сверху
                        if (role() == null) {
                            setRole("buyer");
                            web.loadUrl(home());
                        }
                    }
                })
                .show();
        Toast.makeText(this, R.string.sandbox_note, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String link = siteLink(intent);
        if (link != null) {
            if (BuildConfig.SANDBOX) rememberRoleOf(link);
            web.loadUrl(link);
        }
    }

    // Ссылка на кабинет из уведомления, ярлыка или другого приложения
    private static String siteLink(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        return Links.isOurs(data.getHost()) ? data.buildUpon().scheme("https").build().toString() : null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false); // сканер штрихкодов включает камеру сам
        s.setGeolocationEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // По этой метке кабинет понимает, что открыт в приложении
        baseUa = s.getUserAgentString();
        s.setUserAgentString(baseUa + " ArgedaApp/" + BuildConfig.CABINET + "/" + BuildConfig.VERSION_NAME);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        Bridge bridge = new Bridge();
        web.addJavascriptInterface(bridge, "ArgedaApp");
        web.addJavascriptInterface(bridge, "ArgedaSeller"); // старое имя, чтобы кабинет продавца не переписывать

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        // Сторонние cookie кабинету не нужны: внутри открываются только страницы *.argeda.ru
        cookies.setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return route(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                lastUrl = url;
                // В Песочнице кабинет покупателя (/account) — обычная роль, предупреждать не о чем
                if (!BuildConfig.SANDBOX && isAccountPage(url)) showNotMember();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                if (signingOut) {
                    // Вышли из чужого аккаунта: возвращаемся на вход в кабинет
                    signingOut = false;
                    notMemberShown = false;
                    BadgeJobService.reset(MainActivity.this);
                    view.loadUrl(home());
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                showOffline();
            }

            // Хостинг отдаёт свою страницу-заглушку (сервер лежит, 502/503/504) — вместо неё свой понятный экран
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                int code = response.getStatusCode();
                if (code == 502 || code == 503 || code == 504 || code == 521 || code == 522 || code == 523) {
                    view.stopLoading();
                    showOffline();
                }
            }

            // Копия сайта закрыта паролем сервера: спрашиваем логин и пароль, как браузер
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                if (!Links.isOurs(host)) {
                    handler.cancel();
                    return;
                }
                String[] saved = view.getHttpAuthUsernamePassword(host, realm);
                if (saved != null && saved.length == 2 && saved[0] != null && handler.useHttpAuthUsernamePassword()) {
                    handler.proceed(saved[0], saved[1]);
                    return;
                }
                askHttpAuth(view, handler, host, realm);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value < 100 ? View.VISIBLE : View.GONE);
            }

            // Фото товаров, документы для карточки компании
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }

            // Камера для сканера штрихкодов на страницах кабинета
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String host = request.getOrigin().getHost();
                boolean camera = Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                if (!Links.isOurs(host) || !camera) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    cameraRequest = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (cameraRequest == request) cameraRequest = null;
            }

            // Геолокация для карты курьера: только страницам *.argeda.ru и только если сборка её объявила
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                String host = Uri.parse(origin).getHost();
                boolean declared = hasManifestPermission(Manifest.permission.ACCESS_FINE_LOCATION);
                if (!Links.isOurs(host) || !declared) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, true);
                } else {
                    geoCallback = callback;
                    geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }

            @Override
            public void onGeolocationPermissionsHidePrompt() {
                geoCallback = null;
            }
        });

        // Стикеры PDF, лист подбора, прайс Excel, отчёт агента
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String disposition, String mime, long length) {
                Activity self = MainActivity.this;
                if (url.startsWith("blob:")) {
                    web.evaluateJavascript(Downloads.blobScript(url), null);
                } else if (url.startsWith("data:")) {
                    int comma = url.indexOf(',');
                    String head = comma > 0 ? url.substring(5, comma) : "";
                    String type = head.contains(";") ? head.substring(0, head.indexOf(';')) : head;
                    boolean ok = comma > 0 && head.contains("base64")
                            && Downloads.saveBase64(self, url.substring(comma + 1), type, web.getTitle());
                    Toast.makeText(self, ok ? R.string.download_saved : R.string.download_failed, Toast.LENGTH_SHORT).show();
                } else {
                    Downloads.fromUrl(self, url, userAgent, disposition, mime);
                }
            }
        });
    }

    /** Что кабинет может сказать приложению со страницы: window.ArgedaSeller.… */
    private final class Bridge {
        @JavascriptInterface
        public void saveBlob(String base64, String mime, String title) {
            boolean ok = Downloads.saveBase64(MainActivity.this, base64, mime, title);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this,
                            ok ? R.string.download_saved : R.string.download_failed, Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** Кабинет отдаёт свежие счётчики, пока открыт: уведомлений о том, что уже видно, не будет. */
        @JavascriptInterface
        public void badge(String json) {
            try {
                BadgeJobService.remember(MainActivity.this, new JSONObject(json));
            } catch (JSONException ignored) {
                // неверный формат: просто пропускаем
            }
        }

        @JavascriptInterface
        public void loggedOut() {
            BadgeJobService.reset(MainActivity.this);
        }

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }
    }

    // true: ссылку открыли сами (другое приложение), false: пусть грузит WebView
    private boolean route(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (BuildConfig.SANDBOX && Links.isLive(host)) {
            // Песочница не пускает на живой сайт ни внутри, ни через браузер: проверки идут только на копии
            Toast.makeText(this, R.string.sandbox_live_blocked, Toast.LENGTH_LONG).show();
            return true;
        }
        if (scheme.equals("http") && Links.isOurs(host)) {
            web.loadUrl(uri.buildUpon().scheme("https").build().toString());
            return true;
        }
        if (scheme.equals("http") || scheme.equals("https")) {
            // Внутри кабинета — только свои сайты (*.argeda.ru). Любая чужая ссылка (из чата, письма, карточки)
            // открывается в браузере телефона или своём приложении: чужая страница не должна выглядеть как кабинет
            // и не получает доступ к мосту приложения.
            if (Links.isOurs(host)) return false;
            openExternal(uri);
            return true;
        }
        if (scheme.equals("intent")) {
            openIntentUri(uri.toString());
            return true;
        }
        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        Intent intent;
        if (scheme.equals("tel")) {
            intent = new Intent(Intent.ACTION_DIAL, uri);
        } else if (scheme.equals("mailto")) {
            intent = new Intent(Intent.ACTION_SENDTO, uri);
        } else {
            intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    // Ссылки вида intent://: так сайты открывают банковские приложения
    private void openIntentUri(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // ниже пробуем запасной адрес
            }
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (fallback != null && fallback.startsWith("https://")) {
                if (!route(Uri.parse(fallback))) web.loadUrl(fallback);
                return;
            }
        } catch (URISyntaxException ignored) {
            // покажем сообщение ниже
        }
        Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
    }

    // Сайт уводит на страницу покупателя /account, если вошли не продавцом
    private static boolean isAccountPage(String url) {
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getPath() == null ? "" : u.getPath();
        return Links.isOurs(host) && (path.equals("/account") || path.startsWith("/account/"));
    }

    /** Страницы нет ни в сети, ни в кэше: объясняем, чего ждём — интернета у телефона или сервера ARGEDA. */
    private void showOffline() {
        boolean noNet = net != null && !net.isOnline();
        offlineTitle.setText(noNet ? R.string.offline_title_net : R.string.offline_title);
        offlineText.setText(noNet ? R.string.offline_text_net : R.string.offline_text);
        offline.setVisibility(View.VISIBLE);
        handler.removeCallbacks(autoRetry);
        if (!noNet) handler.postDelayed(autoRetry, 30000);
    }

    private void showNotMember() {
        if (notMemberShown || isFinishing()) return;
        notMemberShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.not_member_title)
                .setMessage(R.string.not_member_text)
                .setPositiveButton(R.string.not_member_signout, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        signingOut = true;
                        web.loadUrl(Links.SIGNOUT);
                    }
                })
                .setNegativeButton(R.string.not_member_stay, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        notMemberShown = false;
                    }
                })
                .show();
    }

    private void askHttpAuth(WebView view, HttpAuthHandler handler, String host, String realm) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText user = new EditText(this);
        user.setHint(R.string.http_auth_login);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = new EditText(this);
        pass.setHint(R.string.http_auth_password);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(user);
        box.addView(pass);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.http_auth_title, host))
                .setMessage(R.string.http_auth_text)
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(R.string.http_auth_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String u = user.getText().toString(), p = pass.getText().toString();
                        view.setHttpAuthUsernamePassword(host, realm, u, p);
                        handler.proceed(u, p);
                    }
                })
                .setNegativeButton(R.string.http_auth_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        handler.cancel();
                    }
                })
                .show();
    }

    private boolean hasManifestPermission(String permission) {
        try {
            String[] declared = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (declared == null) return false;
            for (String d : declared) if (permission.equals(d)) return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            // своё приложение всегда найдётся
        }
        return false;
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION && geoCallback != null) {
            boolean granted = false;
            for (int r : results) granted |= r == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, granted, granted);
            if (!granted) Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show();
            geoCallback = null;
            return;
        }
        if (requestCode == REQ_CAMERA && cameraRequest != null) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                cameraRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                cameraRequest.deny();
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show();
            }
            cameraRequest = null;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    // Рисуем под системными панелями и сами отступаем от них и от клавиатуры
    @SuppressWarnings("deprecation")
    private void drawBehindSystemBars() {
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                } else {
                    v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                }
                return insets;
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            offline.setVisibility(View.GONE);
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (net != null) net.start();
    }

    @Override
    protected void onStop() {
        if (net != null) net.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        Updater.resume(this); // вернулись из настроек разрешения или установщик ждал приложение на экране
        checkUpdate(); // не чаще раза в 4 часа — Updater сам следит
    }

    @Override
    protected void onPause() {
        Updater.pause(this);
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoRetry);
        web.destroy();
        super.onDestroy();
    }
}
