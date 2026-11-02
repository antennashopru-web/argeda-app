package ru.argeda.cabinet;

/**
 * Адреса кабинета. Какой кабинет и сайт — решает сборка (BuildConfig): seller, pvz, courier, admin;
 * живой сайт или копия kopiya.argeda.ru. Пути разделов для ярлыков лежат в res/xml/shortcuts.xml
 * своей разновидности.
 */
final class Links {
    static final String SITE = "argeda.ru";
    static final String KOPIYA_HOST = "kopiya.argeda.ru";
    static final String HOST = BuildConfig.KOPIYA ? KOPIYA_HOST : BuildConfig.HOST;
    static final String BASE = "https://" + HOST;
    /** Корень кабинета: на живом сайте «/», на копии «/seller», «/pvz» и т. п. */
    static final String ROOT = BASE + (BuildConfig.KOPIYA ? BuildConfig.KOPIYA_PATH : BuildConfig.START_PATH);
    static final String HOME = ROOT + (ROOT.endsWith("/") ? "" : "/") + "?utm_source=" + BuildConfig.CABINET + "_app";
    /** Что нового в кабинете: счётчики для уведомлений. Описание в docs/cabinet-apps.md. */
    static final String BADGE = BASE + "/api/app/badge?cabinet=" + BuildConfig.CABINET;
    /** Выход из аккаунта: нужен, если вошли не тем аккаунтом и сайт увёл на /account. */
    static final String SIGNOUT = BASE + "/api/auth/signout";

    private Links() {
    }
}
