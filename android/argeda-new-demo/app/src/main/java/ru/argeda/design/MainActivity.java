package ru.argeda.design;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/**
 * Новое приложение ARGEDA по макету v1. Экраны лежат в assets и открываются
 * с адреса https://appassets.androidplatform.net, чтобы у страницы было
 * обычное https-хранилище для корзины и заказов.
 */
public class MainActivity extends Activity {
    // Без лямбд: сборка без Android SDK (dx) превращает их в invoke-custom, которого нет на Android, и приложение падает при запуске.

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/index.html";
    private static final int REQ_FILE = 1;

    private WebView web;
    private float density;
    private int topInset;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingJs;
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        density = getResources().getDisplayMetrics().density;
        web = findViewById(R.id.web);
        drawBehindSystemBars();
        setupWebView();
        if (state == null || web.restoreState(state) == null) web.loadUrl(START);
        handleDeepLink(getIntent());
        checkUpdate(false);
    }

    // Файл версий — в последнем релизе GitHub (github.com/antennashopru-web/argeda-app/releases); раз в 4 часа при запуске, по кнопке в настройках — сразу
    private static final String VERSIONS = "https://github.com/antennashopru-web/argeda-app/releases/latest/download/version.json";

    private void checkUpdate(boolean manual) {
        if (manual) Updater.checkNow(this, VERSIONS, "argeda", BuildConfig.VERSION_CODE);
        else Updater.checkQuietly(this, VERSIONS, "argeda", BuildConfig.VERSION_CODE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    // Ссылки, которые открывают приложение: ru.argeda.design://order/A-номер (возврат после оплаты)
    // и https://argeda.ru/… (App Links с сайта) → тот же экран внутри приложения
    private void handleDeepLink(Intent intent) {
        if (intent == null) return;
        Uri d = intent.getData();
        if (d == null) return;
        String scheme = d.getScheme() == null ? "" : d.getScheme().toLowerCase(Locale.ROOT);
        String host = d.getHost() == null ? "" : d.getHost().toLowerCase(Locale.ROOT);
        if ("ru.argeda.design".equals(scheme) && "order".equals(host)) {
            String num = d.getLastPathSegment();
            if (num == null || num.isEmpty()) return;
            pendingJs = "window.appOpenOrder && window.appOpenOrder(" + jsString(num) + ")";
        } else if (("https".equals(scheme) || "http".equals(scheme)) && (host.equals("argeda.ru") || host.endsWith(".argeda.ru"))) {
            pendingJs = "window.appOpenUrl && window.appOpenUrl(" + jsString(d.toString()) + ")";
        } else {
            return;
        }
        if (pageReady) runPending();
    }

    private static String jsString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private void runPending() {
        if (pendingJs == null || web == null) return;
        final String js = pendingJs;
        pendingJs = null;
        web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(js, null); } });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        // Мост открыт только своим страницам из assets: чужие адреса в это окно не загружаются
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!HOST.equals(uri.getHost())) return null;
                String path = uri.getPath() == null || uri.getPath().equals("/") ? "index.html" : uri.getPath().substring(1);
                try {
                    return new WebResourceResponse(mime(path), mime(path).startsWith("text") || path.endsWith(".js") ? "utf-8" : null,
                            getAssets().open(path));
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pushInsets();
                pageReady = true;
                runPending();
            }
        });

        // Поиск по фото: снимок или файл из галереи
        web.setWebChromeClient(new WebChromeClient() {
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
        });
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("tel")) {
            startSafe(new Intent(Intent.ACTION_DIAL, uri));
        } else if (scheme.equals("mailto")) {
            startSafe(new Intent(Intent.ACTION_SENDTO, uri));
        } else {
            // Оплата и внешние ссылки — пробуем Custom Tab: страница открывается «поверх» приложения
            // и возвращает обратно. Браузер без поддержки Custom Tab просто откроет её как обычную ссылку.
            Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            Bundle extras = new Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", null); // метка «это Custom Tab»
            intent.putExtras(extras);
            intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF1668D9); // синий ARGEDA
            startSafe(intent);
        }
    }

    private void startSafe(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** Методы, которые вызывает страница через window.Android. */
    private class Bridge {
        @JavascriptInterface
        public String deviceModel() {
            String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
            String model = Build.MODEL == null ? "" : Build.MODEL;
            if (!maker.isEmpty()) maker = maker.substring(0, 1).toUpperCase(Locale.ROOT) + maker.substring(1);
            return model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT)) ? model : (maker + " " + model).trim();
        }

        // Светлые значки статус-бара нужны там, где сверху тёмная шапка
        @JavascriptInterface
        public void setLight(boolean light) {
            runOnUiThread(new Runnable() { @Override public void run() { setLightBars(light); } });
        }

        // Тёмная ли тема в телефоне — страница по ней выбирает оформление
        @JavascriptInterface
        public boolean isNight() {
            return phoneIsNight();
        }

        // Страница сообщает, какое оформление выбрано: под него красим фон окна и значки нижней панели
        @JavascriptInterface
        public void setNight(boolean night) {
            runOnUiThread(new Runnable() { @Override public void run() { applyNight(night); } });
        }

        // Кнопка «Проверить обновления» в настройках
        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(new Runnable() { @Override public void run() { MainActivity.this.checkUpdate(true); } });
        }

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, null));
            } });
        }
    }

    private boolean phoneIsNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applyNight(boolean night) {
        int bg = night ? 0xFF0B1220 : 0xFFF2F5FA;
        findViewById(R.id.root).setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int nav = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : nav, nav);
            }
        } else {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags = night ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    // Тему телефона переключили на ходу — активность не пересоздаётся (uiMode в configChanges), сообщаем странице
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (web != null) web.evaluateJavascript("window.appTheme && appTheme()", null);
    }

    @SuppressWarnings("deprecation")
    private void setLightBars(boolean lightIcons) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            int dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
            c.setSystemBarsAppearance(lightIcons ? 0 : dark, dark);
        } else {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags = lightIcons ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    // Рисуем под статус-баром (тёмная шапка уходит под него), снизу отступаем от меню и клавиатуры
    @SuppressWarnings("deprecation")
    private void drawBehindSystemBars() {
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
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
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() { @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            int left, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                right = bars.right;
                bottom = Math.max(bars.bottom, ime.bottom);
                topInset = bars.top;
            } else {
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                topInset = insets.getSystemWindowInsetTop();
            }
            v.setPadding(left, 0, right, bottom);
            pushInsets();
            return insets;
        } });
    }

    private void pushInsets() {
        if (web == null) return;
        int topCss = Math.round(topInset / density);
        web.evaluateJavascript("document.documentElement.style.setProperty('--top','" + topCss + "px')", null);
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

    // «Назад» сначала обрабатывает приложение: закрывает шторку или возвращает на прошлый экран
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack ? appBack() : false", new ValueCallback<String>() { @Override public void onReceiveValue(String handled) {
            if (!"true".equals(handled)) MainActivity.super.onBackPressed();
        } });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        checkUpdate(false);
        // Вернулись в приложение (в т.ч. после оплаты в браузере) — обновим открытый заказ
        if (pageReady && web != null) web.evaluateJavascript("window.appResumed && window.appResumed()", null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
