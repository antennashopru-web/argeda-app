# ARGEDA — правила для релизной сборки (R8).
# Главное: мост со страницей вызывается по именам методов из JavaScript,
# поэтому классы и методы моста нельзя переименовывать и вырезать.

# Мост window.Android: все публичные методы, помеченные @JavascriptInterface
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Класс моста целиком (его имя задаётся в addJavascriptInterface)
-keep class ru.argeda.design.MainActivity$Bridge { *; }

# Имена классов приложения в стектрейсах читаемы
-keepnames class ru.argeda.design.** { *; }
