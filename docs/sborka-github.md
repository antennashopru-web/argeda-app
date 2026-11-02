# Сборка APK в GitHub Actions

Каждый push в `main` (изменения в `android/argeda-new-demo` или `android/argeda-cabinets`) запускает
`.github/workflows/android.yml`: собираются покупательское приложение и четыре кабинета (продавец, пункт, курьер, админ),
подписываются релизным ключом и выкладываются в **Releases** вместе с `version.json`.
Запустить вручную: вкладка Actions → «Сборка APK» → Run workflow.

Где смотреть: https://github.com/antennashopru-web/argeda-app/actions и https://github.com/antennashopru-web/argeda-app/releases

## Ключ подписи — один раз

Ключ `argeda-release.jks` хранится только в секретах GitHub (Settings → Secrets and variables → Actions → New repository secret):

| Секрет | Что это |
|---|---|
| `KEYSTORE_BASE64` | Сам файл ключа, закодированный в base64 (одна длинная строка) |
| `KEYSTORE_PASSWORD` | Пароль хранилища |
| `KEY_ALIAS` | Имя ключа внутри хранилища |
| `KEY_PASSWORD` | Пароль ключа (часто такой же, как у хранилища) |

Как получить base64 на ПК с Windows — в PowerShell (строка попадёт в буфер обмена, вставить в секрет):

```
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\admin\Downloads\argeda-release.jks")) | Set-Clipboard
```

Пароли и файл ключа в чат и в репозиторий не кладём. Пока секретов нет, сборка проходит, но APK неподписанные
и в Releases не выкладываются (в Actions — предупреждение).

## Новая версия

1. Поднять `versionCode`/`versionName` в `app/build.gradle` нужного приложения (покупательское и кабинеты независимы).
2. Push в `main` (или слить PR). Через ~5 минут в Releases появится `v<покупательское>-c<кабинеты>` с файлами
   `argeda.apk`, `argeda-seller.apk`, `argeda-pvz.apk`, `argeda-courier.apk`, `argeda-admin.apk`, `version.json`.
3. Больше ничего: приложения у людей читают `version.json` из последнего релиза и предлагают обновиться
   (см. `docs/avtoobnovlenie.md`).

Если версии не менялись, повторная сборка обновляет файлы в том же релизе.

## Проверки в сборке

- В `classes.dex` нет `LambdaMetafactory` (правило проекта после падений сборок без SDK).
- Подпись: APK с ключом из секретов; обновление поверх ставится только с тем же ключом, что у установленного приложения.
