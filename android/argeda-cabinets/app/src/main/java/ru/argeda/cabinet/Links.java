package ru.argeda.cabinet;

import java.util.Locale;

/**
 * Адреса кабинета. Какой кабинет — решает сборка (BuildConfig): seller, pvz, courier, admin или sandbox
 * (Песочница на копии сайта kopiya.argeda.ru). Пути разделов для ярлыков лежат в res/xml/shortcuts.xml
 * своей разновидности.
 */
final class Links {
    static final String SITE = "argeda.ru";
    static final String KOPIYA_HOST = "kopiya.argeda.ru";
    static final String HOST = BuildConfig.HOST;
    static final String BASE = "https://" + HOST;
    /** Корень кабинета на живом сайте. В Песочнице корень зависит от выбранной роли — см. MainActivity.home(). */
    static final String ROOT = BASE + BuildConfig.START_PATH;
    static final String HOME = ROOT + (ROOT.endsWith("/") ? "" : "/") + "?utm_source=" + BuildConfig.CABINET + "_app";
    /** Что нового в кабинете: счётчики для уведомлений. Описание в docs/cabinet-apps.md. */
    static final String BADGE = BASE + "/api/app/badge?cabinet=" + BuildConfig.CABINET;
    /** Выход из аккаунта: нужен, если вошли не тем аккаунтом и сайт увёл на /account. */
    static final String SIGNOUT = BASE + "/api/auth/signout";

    /**
     * Сайты, которые открываются внутри кабинета. Только свои: магазин и кабинеты. Остальные поддомены argeda.ru
     * (crm., ai., world., estate. и любые новые) — чужие проекты, они уходят в браузер и не видят мост приложения.
     * В Песочнице — только копия сайта.
     */
    private static final String[] OURS = {
            "argeda.ru", "www.argeda.ru", "seller.argeda.ru", "pvz.argeda.ru",
            "courier.argeda.ru", "admin.argeda.ru"
    };

    static boolean isOurs(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (BuildConfig.SANDBOX) return h.equals(KOPIYA_HOST);
        for (String o : OURS) if (o.equals(h)) return true;
        return false;
    }

    /** Живой сайт ARGEDA (любой *.argeda.ru, кроме копии): в Песочнице его открывать нельзя. */
    static boolean isLive(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return !h.equals(KOPIYA_HOST) && (h.equals(SITE) || h.endsWith("." + SITE));
    }

    private Links() {
    }
}
