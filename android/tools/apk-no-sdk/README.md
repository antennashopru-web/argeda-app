# Сборка APK без Android SDK

> **Устарело с ARGEDA 1.24 / кабинетов 1.10:** приложения переписаны на Kotlin, а этот скрипт умеет только Java.
> Настоящая сборка — GitHub Actions (`docs/sborka-github.md`). Скрипт оставлен для истории.

Запасной способ для облака, где закрыт dl.google.com (там живут Android SDK и maven.google.com).
Все части берутся с GitHub и Maven Central, подпись по схеме v2 сделана штатными средствами JDK.

| Шаг | Чем |
|---|---|
| Ресурсы и манифест | `aapt2` из `apktool.jar` (github.com/iBotPeaches/Apktool), framework-ресурсы оттуда же |
| Java → class | `javac --release 8` с `org.robolectric:android-all` (классы Android) с Maven Central |
| class → dex | `dx` из `com.jakewharton.android.repackaged:dalvik-dx`. **Лямбды и ссылки на методы запрещены:** dx превращает их в invoke-custom, а Android их не исполняет — приложение падает при запуске (так было в 1.0–1.1). Пишем анонимные классы; `build.sh` останавливается, если найдёт invokedynamic |
| Выравнивание | `ZipAlign.java`: несжатые файлы на 4 байта, `resources.arsc` на 4096 |
| Подпись | `ApkSignV2.java`: APK Signature Scheme v2, RSA/SHA-256 |
| Проверка | `verify_v2.py`: независимо проверяет дайджест и подпись по описанию схемы |

Запуск: положить три jar в `jars/` и выполнить

```
./build.sh seller live      # ARGEDA Продавец, seller.argeda.ru
./build.sh pvz kopiya       # ARGEDA Пункт (копия), kopiya.argeda.ru/pvz
./build.sh argeda           # покупательское приложение (argeda-new-demo), файл argeda-<версия>.apk
```

Приложения: `argeda` (покупательское), кабинеты `seller`, `pvz`, `courier`, `admin`; сайт: `live` или `kopiya`.
Версия берётся из `build.gradle` приложения. `PROJ_DIR=/путь/argeda-new-demo` — собрать покупательское из другой папки (ветки).
Если Maven Central отвечает 429, тот же файл есть на зеркале `https://maven-central.storage-download.googleapis.com/maven2/…`. Настройки кабинетов (хост, путь на копии,
название) — в начале `build.sh`, они повторяют `productFlavors` из `argeda-cabinets/app/build.gradle`.

Это сборка для проверки с отладочным ключом (`debug.p12`, стандартный пароль Android, в репозиторий не кладётся).
Для RuStore собирать обычным `./gradlew assembleSellerLiveRelease` и т. п. с настоящим ключом.
Обновлять приложение поверх можно только тем же ключом, иначе сначала удалить старое.
