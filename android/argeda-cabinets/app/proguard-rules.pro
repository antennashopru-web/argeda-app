# Кабинеты ARGEDA — правила для релизной сборки (R8).
# Сжимаем только библиотеку Kotlin (лишнее из неё выбрасывается). Свой код не трогаем совсем:
# мост со страницей вызывается из JavaScript по именам методов, а службы и приёмники Android ищет по именам классов.
-dontobfuscate
-keep class ru.argeda.cabinet.** { *; }
-keepattributes JavascriptInterface,*Annotation*,SourceFile,LineNumberTable
