# Правила для всех, кто работает с приложениями ARGEDA

Этот файл читают все сессии Claude, работающие с репозиторием. Владелец — Артак, не программист: объяснять коротко и по-русски.

## Обновления приложений (обязательно для каждого приложения ARGEDA)

Любое Android-приложение ARGEDA (покупательское, кабинеты, Песочница, ARGEDA TV и будущие) обновляется по одной схеме.
Код-образец: `android/argeda-new-demo/app/src/main/java/ru/argeda/design/Updater.kt` + `UpdateReceiver.kt`,
описание — `docs/avtoobnovlenie.md`.

1. **Один ключ подписи навсегда.** Только НОВЫЙ ключ (секрет `SIGNING_PASSWORD`, `signing/argeda-release.jks.enc`).
   Старый ключ (до 1.12) не использовать. Другим ключом Android не поставит обновление поверх.
2. **Каждая новая версия — `versionCode` больше прежнего** (и новый `versionName`). Иначе телефоны не увидят обновление,
   а сборка в Actions остановится (релиз с таким тегом уже есть).
3. **Проверка**: `version.json` из последнего релиза GitHub, не чаще раза в 4 часа при запуске/возврате, плюс кнопка «Проверить».
4. **Скачивание**: своё, во внутреннюю папку `cache/updates` через `.part`; 3 круга по адресам —
   ссылка из `version.json` → ассет через API GitHub (`Accept: application/octet-stream`) → `browser_download_url`.
   Проверять размер, начало `PK`, имя пакета и `versionCode` внутри APK.
5. **Разрешение** «Установка неизвестных приложений»: окно с объяснением → настройки → установка продолжается
   сама из `onResume` (`Updater.resume`, `Updater.pause` в `onPause`).
6. **Установка — сессией `PackageInstaller`**, на Android 12+ с `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`
   и разрешением `UPDATE_PACKAGES_WITHOUT_USER_ACTION` в манифесте: обновление самого себя проходит без экрана
   «Обновить?». Если система всё же просит подтверждения (Android 9–11, решение системы) — показать её экран.
   Нужен `UpdateReceiver` в манифесте (`exported=false`), `PendingIntent` с `FLAG_MUTABLE`.
7. Не для Google Play: там самообновление в обход магазина запрещено. RuStore — публиковать на НОВОМ ключе.

## Код

- Язык — **Kotlin** (с ARGEDA 1.24 и кабинетов 1.10), новые файлы только `.kt`.
  Лямбды можно: в `build.gradle` стоят `-Xlambdas=class` и `-Xsam-conversions=class`, они собираются обычными
  классами. Эти настройки не убирать — сборка проверяет, что в APK нет `LambdaMetafactory`.
- Всё, что приходит из JavaScript в мост (`@JavascriptInterface`), объявлять `String?`: страница может прислать null.
- Сборка и релиз: push в `main` → GitHub Actions (`.github/workflows/android.yml`) → Releases. PR сливает только Артак.
- Пароли и ключи в репозиторий и в чат не писать.
