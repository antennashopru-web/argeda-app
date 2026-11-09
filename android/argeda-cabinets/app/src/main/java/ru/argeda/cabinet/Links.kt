package ru.argeda.cabinet

import java.util.Locale

/**
 * Адреса кабинета. Какой кабинет — решает сборка (BuildConfig): seller, pvz, courier, admin или sandbox
 * (Песочница на копии сайта kopiya.argeda.ru). Пути разделов для ярлыков лежат в res/xml/shortcuts.xml
 * своей разновидности.
 */
internal object Links {
    const val SITE = "argeda.ru"
    const val KOPIYA_HOST = "kopiya.argeda.ru"
    val HOST: String = BuildConfig.HOST
    val BASE = "https://$HOST"
    /** Корень кабинета на живом сайте. В Песочнице корень зависит от выбранной роли — см. MainActivity.home(). */
    val ROOT = BASE + BuildConfig.START_PATH
    val HOME = ROOT + (if (ROOT.endsWith("/")) "" else "/") + "?utm_source=" + BuildConfig.CABINET + "_app"
    /** Что нового в кабинете: счётчики для уведомлений. Описание в docs/cabinet-apps.md. */
    val BADGE = "$BASE/api/app/badge?cabinet=" + BuildConfig.CABINET
    /** Выход из аккаунта: нужен, если вошли не тем аккаунтом и сайт увёл на /account. */
    val SIGNOUT = "$BASE/api/auth/signout"

    /**
     * Сайты, которые открываются внутри кабинета. Только свои: магазин и кабинеты. Остальные поддомены argeda.ru
     * (crm., ai., world., estate. и любые новые) — чужие проекты, они уходят в браузер и не видят мост приложения.
     * В Песочнице — только копия сайта.
     */
    private val OURS = setOf(
        "argeda.ru", "www.argeda.ru", "seller.argeda.ru", "pvz.argeda.ru",
        "courier.argeda.ru", "admin.argeda.ru"
    )

    fun isOurs(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase(Locale.ROOT)
        if (BuildConfig.SANDBOX) return h == KOPIYA_HOST
        return h in OURS
    }

    /** Живой сайт ARGEDA (любой *.argeda.ru, кроме копии): в Песочнице его открывать нельзя. */
    fun isLive(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase(Locale.ROOT)
        return h != KOPIYA_HOST && (h == SITE || h.endsWith(".$SITE"))
    }
}
