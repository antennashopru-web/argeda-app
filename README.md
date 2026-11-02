# ARGEDA — приложения для Android

Открытый код мобильных приложений магазина [argeda.ru](https://argeda.ru) (Пушкино).

| Папка | Приложение |
|---|---|
| `android/argeda-new-demo` | **ARGEDA** — покупательское: каталог, заказы, самовывоз в пунктах, ремонт техники |
| `android/argeda-cabinets` | Кабинеты сотрудников и продавцов: **ARGEDA Продавец**, **ARGEDA Пункт**, **ARGEDA Курьер**, **ARGEDA Админ** (один код, четыре сборки) |
| `android/tools/apk-no-sdk` | Запасная сборка без Android SDK |

Приложения обновляются сами из Releases ([docs/avtoobnovlenie.md](docs/avtoobnovlenie.md)) и работают без интернета на сохранённых данных ([docs/oflayn.md](docs/oflayn.md)).

## Скачать

Готовые APK — в [Releases](https://github.com/antennashopru-web/argeda-app/releases/latest):
`argeda.apk` (покупателям), `argeda-seller.apk`, `argeda-pvz.apk`, `argeda-courier.apk`, `argeda-admin.apk`.

Установленные приложения сами проверяют новую версию здесь же и предлагают обновиться (`docs/avtoobnovlenie.md`).

## Как выходит новая версия

Поднять `versionCode` и `versionName` в `app/build.gradle` → push в `main` → GitHub Actions собирает, подписывает
и публикует релиз (`docs/sborka-github.md`). Ключ подписи хранится только в секретах репозитория.

Без лямбд в Java: запасная сборка без SDK их не переносит, сборка это проверяет.
