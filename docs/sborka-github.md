# Сборка APK в GitHub Actions

Каждый push в `main` (изменения в `android/argeda-new-demo` или `android/argeda-cabinets`) запускает
`.github/workflows/android.yml`: собираются покупательское приложение и четыре кабинета (продавец, пункт, курьер, админ),
подписываются релизным ключом и выкладываются в **Releases** вместе с `version.json`.
Запустить вручную: вкладка Actions → «Сборка APK» → Run workflow.

Где смотреть: https://github.com/antennashopru-web/argeda-app/actions и https://github.com/antennashopru-web/argeda-app/releases

## Ключ подписи — один раз, без компьютера

1. Придумать пароль ключа (16+ знаков, буквы и цифры) и записать его в надёжное место: без него ключ не восстановить.
2. Settings → Secrets and variables → Actions → New repository secret: имя `SIGNING_PASSWORD`, значение — этот пароль.
3. Actions → «Создать ключ подписи» → Run workflow. GitHub создаст ключ (RSA-4096, alias `argeda`, 30 лет), зашифрует его
   этим паролем (AES-256) и сохранит в репозиторий как `signing/argeda-release.jks.enc`. Сам ключ и пароль никуда не выводятся.
4. Сразу после этого запустится сборка и выйдет первый подписанный релиз.

Зашифрованный ключ лежит в открытом репозитории, поэтому пароль должен быть длинным. Потерян пароль = потерян ключ:
установленные приложения тогда можно только удалить и поставить заново.

Запасной путь, если ключ уже есть (например, `argeda-release.jks` с ПК): секреты `KEYSTORE_BASE64` (файл .jks в base64),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. В PowerShell base64 делается так:
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\...\argeda-release.jks")) | Set-Clipboard`.
Если заданы оба пути, используется ключ из `KEYSTORE_BASE64`.

Пока ключа нет, сборка проходит, но APK неподписанные и в Releases не выкладываются (в Actions — предупреждение).

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
