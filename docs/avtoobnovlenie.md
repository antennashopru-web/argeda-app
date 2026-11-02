# Автообновление приложений ARGEDA

С покупательского 1.13 (код 14) и кабинетов 1.3 (код 4) приложения сами проверяют, не вышла ли новая версия,
и предлагают её поставить. Магазин приложений и сервер не нужны: APK и файл версий лежат в GitHub Releases этого
репозитория, ссылка `releases/latest/download/<файл>` всегда ведёт на последний релиз.

## Как это работает в приложении

1. При запуске (и при возврате в приложение, но не чаще раза в 4 часа) приложение читает
   `https://github.com/antennashopru-web/argeda-app/releases/latest/download/version.json` (копии кабинетов — `https://kopiya.argeda.ru/app/version.json`).
2. Если в файле `code` больше установленного `versionCode`, показывается окно «Новая версия 1.13»
   с кнопками «Обновить» и «Позже». После «Позже» сутки не напоминаем.
   Если `min` больше установленного кода — окно без «Позже» и не закрывается (обязательное обновление).
3. «Обновить» — DownloadManager скачивает APK в «Загрузки» (уведомление с ходом загрузки),
   затем открывается штатный установщик Android. При первой установке Android спросит разрешение
   «Устанавливать неизвестные приложения» для ARGEDA — один раз.
4. Корзина, заказы, вход сохраняются: это обычное обновление поверх.
5. В покупательском в «Настройках» есть кнопка «Проверить обновления» — проверяет сразу и говорит
   «У вас последняя версия», если новее нет.

Код: `Updater.java` (одинаковый в `argeda-new-demo` и `argeda-cabinets`).

## Файл версий `version.json`

Один файл на все приложения. Ключ — какое приложение: `argeda` (покупательское), `seller`, `pvz`, `courier`, `admin`;
у копий кабинетов ключ с хвостом `-kopiya` (`seller-kopiya` и т. д.) в файле на kopiya.argeda.ru.

```json
{
  "argeda":  { "code": 14, "name": "1.13", "url": "https://github.com/antennashopru-web/argeda-app/releases/latest/download/argeda.apk", "min": 11,
               "notes": "Приложение теперь обновляется само. Исправлен возврат после оплаты." },
  "seller":  { "code": 4, "name": "1.3", "url": "https://github.com/antennashopru-web/argeda-app/releases/latest/download/argeda-seller.apk" },
  "pvz":     { "code": 4, "name": "1.3", "url": "https://github.com/antennashopru-web/argeda-app/releases/latest/download/argeda-pvz.apk" },
  "courier": { "code": 4, "name": "1.3", "url": "https://github.com/antennashopru-web/argeda-app/releases/latest/download/argeda-courier.apk" },
  "admin":   { "code": 4, "name": "1.3", "url": "https://github.com/antennashopru-web/argeda-app/releases/latest/download/argeda-admin.apk" }
}
```

| Поле | Обязательно | Что значит |
|---|---|---|
| `code` | да | `versionCode` выложенного APK. Предлагаем обновиться, только если он больше установленного |
| `name` | да | Название версии для окна: «Новая версия 1.13» |
| `url` | да | Прямая https-ссылка на APK (без пароля, без редиректа на страницу) |
| `min` | нет | Минимальный код, с которым можно работать. Ниже него окно не закрыть. Пусто/0 — обновление по желанию |
| `notes` | нет | Что нового, покажется в окне над текстом |

`version.json` собирает GitHub Actions сам из версий в `build.gradle` (поля `min` и `notes` можно добавить руками,
отредактировав файл в релизе). Откат: удалить последний релиз — ссылка `latest` снова укажет на предыдущий.

Если понадобится раздавать с сайта (argeda.ru/app), достаточно скопировать туда файлы релиза и поменять адрес
`VERSIONS` в `MainActivity` обоих приложений. Старое поле `min_version`/`apk_url` в `/api/app/v1/config` читают
только покупательские ≤ 1.12.

## Сборка релиза на ПК (с Android SDK)

1. Рядом с `app/build.gradle` положить `app/keystore.properties` (в репозиторий не попадает):
   ```
   storeFile=C:/Users/admin/Downloads/argeda-release.jks
   storePassword=…
   keyAlias=…
   keyPassword=…
   ```
2. Кабинеты: в `android/argeda-cabinets` выполнить `gradlew assembleSellerLiveRelease` (и `PvzLive`, `CourierLive`, `AdminLive`;
   для копии — `…KopiyaRelease`). Готовые файлы: `app/build/outputs/apk/<кабинет><сайт>/release/`.
3. Покупательское: в `android/argeda-new-demo` — `gradlew assembleRelease`, файл `app/build/outputs/apk/release/app-release.apk`.
4. Это запасной путь; обычно всё делает GitHub Actions (`docs/sborka-github.md`).

Без `keystore.properties` release соберётся неподписанным — такой APK Android не поставит.

## Копия с HTTP-паролем

Копии кабинетов запоминают пароль сайта-копии, который ввели при входе, и с ним же читают `version.json` и качают APK.
Поэтому `/app/` на копии можно оставить под паролем. Покупательское приложение копии не имеет.

## Подпись — главное

Android ставит обновление поверх только с **тем же ключом подписи**. В релизе должны лежать APK,
подписанные релизным ключом `argeda-release.jks` (он на ПК Артака; переподписывает серверная ветка).
APK из облачной сборки без SDK подписаны отладочным ключом — поверх релизных они не встанут,
а релизные поверх них — тоже. Для проверки на телефоне: удалить старое → поставить debug 1.13 → потом
выложить debug-подписанную «1.14» на копию и убедиться, что окно появляется и установка проходит.
