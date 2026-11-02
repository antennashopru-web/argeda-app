#!/usr/bin/env bash
# Сборка debug-APK приложений ARGEDA без Android SDK (когда dl.google.com недоступен).
# Нужны: JDK 17+, python3 с пакетом cryptography, zip, и три файла в папке jars/:
#   apktool.jar  — github.com/iBotPeaches/Apktool (внутри aapt2 и framework-ресурсы)
#   android-all.jar — repo1.maven.org/maven2/org/robolectric/android-all/<версия>/ (классы Android для javac)
#   dx.jar — repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/<версия>/ (dex)
# Запуск: ./build.sh <argeda|seller|pvz|courier|admin> [live|kopiya] [папка вывода]
#   argeda — покупательское приложение (argeda-new-demo), остальное — кабинеты (argeda-cabinets).
# Версия (versionCode/versionName) берётся из build.gradle приложения.
# PROJ_DIR — другая папка проекта покупательского приложения (например, из другой ветки).
# Ключ: переменные KEYSTORE (PKCS12), KEYSTORE_PASS, KEY_ALIAS; без них создаётся отладочный ключ debug.p12.
set -euo pipefail
CAB=$1; SITE=${2:-live}; OUT=${3:-out/$CAB-$SITE}
HERE=$(cd "$(dirname "$0")" && pwd); JARS=$HERE/jars
if [ "$CAB" = argeda ]; then
  PROJ=${PROJ_DIR:-$HERE/../../argeda-new-demo}/app; NS=ru.argeda.design; PKG=ru.argeda.design; NAME=""; FLAVOR=""; SITE=live
else
  PROJ=$HERE/../../argeda-cabinets/app; NS=ru.argeda.cabinet; FLAVOR=$PROJ/src/$CAB
  case $CAB in
    seller)  HOST=seller.argeda.ru;  KPATH=/seller;  SCPREFIX="";     NAME="ARGEDA Продавец" ;;
    pvz)     HOST=pvz.argeda.ru;     KPATH=/pvz;     SCPREFIX=""; NAME="ARGEDA Пункт" ;;
    courier) HOST=courier.argeda.ru; KPATH=/courier; SCPREFIX=""; NAME="ARGEDA Курьер" ;;
    admin)   HOST=admin.argeda.ru;   KPATH=/admin;   SCPREFIX=""; NAME="ARGEDA Админ" ;;
    *) echo "неизвестное приложение: $CAB"; exit 1 ;;
  esac
  PKG=ru.argeda.$CAB
fi
APP=$PROJ/src/main
VCODE=$(grep -oE 'versionCode +[0-9]+' "$PROJ/build.gradle" | grep -oE '[0-9]+')
VNAME=$(grep -oE "versionName +'[^']+'" "$PROJ/build.gradle" | cut -d"'" -f2)
KOPIYA=false
if [ "$SITE" = kopiya ]; then PKG=$PKG.kopiya; VNAME=$VNAME-kopiya; KOPIYA=true; NAME="$NAME (копия)"; fi

rm -rf "$OUT"; mkdir -p "$OUT"/{tools,compiled,gen,classes,name/values}
OUT=$(cd "$OUT" && pwd); cd "$OUT"
unzip -q -o "$JARS/apktool.jar" prebuilt/linux/aapt2_64 prebuilt/android-framework.jar -d tools
chmod +x tools/prebuilt/linux/aapt2_64
AAPT2=tools/prebuilt/linux/aapt2_64; FW=tools/prebuilt/android-framework.jar

# Ресурсы: общие, затем наложение кабинета, затем название приложения
$AAPT2 compile --dir "$APP/res" -o compiled/res.zip
EXTRA_RES=(); ASSETS=()
[ -d "$APP/assets" ] && ASSETS=(-A "$APP/assets")
if [ -n "$FLAVOR" ]; then
FLAVOR_RES=$FLAVOR/res
if [ "$KOPIYA" = true ]; then
  # Ярлыки копии ведут на kopiya.argeda.ru и запускают пакет копии
  rm -rf flavor-res; cp -r "$FLAVOR/res" flavor-res; FLAVOR_RES=$PWD/flavor-res
  sed -i "s|https://$HOST/|https://kopiya.argeda.ru$SCPREFIX/|g; s|android:targetPackage=\"ru.argeda.$CAB\"|android:targetPackage=\"$PKG\"|g" flavor-res/xml/shortcuts.xml
fi
$AAPT2 compile --dir "$FLAVOR_RES" -o compiled/flavor.zip
printf '<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">%s</string></resources>\n' "$NAME" > name/values/strings.xml
$AAPT2 compile --dir name -o compiled/name.zip
EXTRA_RES=(-R compiled/flavor.zip -R compiled/name.zip)
fi

# Манифест: пакет, версия, хост ссылок, полные имена классов, разрешения кабинета
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\" android:versionCode=\"$VCODE\" android:versionName=\"$VNAME\">|; s|\${cabinetHost}|${HOST:-}|g; s|android:name=\"\\.|android:name=\"$NS.|g" "$APP/AndroidManifest.xml" > AndroidManifest.xml
if [ -n "$FLAVOR" ] && [ -f "$FLAVOR/AndroidManifest.xml" ]; then
  grep '<uses-permission' "$FLAVOR/AndroidManifest.xml" > extra-perms.txt || true
  if [ -s extra-perms.txt ]; then
    python3 - "$PWD/AndroidManifest.xml" "$PWD/extra-perms.txt" <<'PY'
import sys
m=open(sys.argv[1],encoding='utf-8').read(); extra=open(sys.argv[2],encoding='utf-8').read()
marker='<uses-permission android:name="android.permission.INTERNET" />'
open(sys.argv[1],'w',encoding='utf-8').write(m.replace(marker, marker+'\n'+extra,1))
PY
  fi
fi
$AAPT2 link -I $FW --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 35 \
  --java gen --custom-package "$NS" --auto-add-overlay "${ASSETS[@]}" -o base.apk compiled/res.zip "${EXTRA_RES[@]}"

PKGDIR=gen/${NS//./\/}; mkdir -p "$PKGDIR"
cat > "$PKGDIR/BuildConfig.java" <<JAVA
package $NS;
public final class BuildConfig {
    public static final boolean DEBUG = true;
    public static final String APPLICATION_ID = "$PKG";
    public static final String BUILD_TYPE = "debug";
    public static final int VERSION_CODE = $VCODE;
    public static final String VERSION_NAME = "$VNAME";
JAVA
if [ "$CAB" != argeda ]; then cat >> "$PKGDIR/BuildConfig.java" <<JAVA
    public static final String CABINET = "$CAB";
    public static final String HOST = "$HOST";
    public static final String START_PATH = "/";
    public static final String KOPIYA_PATH = "$KPATH";
    public static final boolean KOPIYA = $KOPIYA;
JAVA
fi
echo "}" >> "$PKGDIR/BuildConfig.java"
javac --release 8 -nowarn -encoding UTF-8 -d classes -cp "$JARS/android-all.jar" $(find gen "$APP/java" -name '*.java')
# Лямбды и прочий invokedynamic Android не исполняет без десахаризации (D8 делает её сам, dx — нет):
# приложение падает при запуске. Поэтому в коде только анонимные классы, а сборка это проверяет.
INDY=$(for f in $(find classes -name '*.class'); do javap -c -p "$f" 2>/dev/null | grep -q invokedynamic && echo "$f"; done; true)
if [ -n "$INDY" ]; then echo "ОШИБКА: invokedynamic (лямбда или ссылка на метод) в: $INDY"; exit 1; fi
java -cp "$JARS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=29 --output=classes.dex classes
cp base.apk unsigned.apk && zip -q -j unsigned.apk classes.dex
javac -d tools "$HERE/ZipAlign.java" "$HERE/ApkSignV2.java"
java -cp tools ZipAlign unsigned.apk aligned.apk
APK=argeda-$CAB.apk; [ "$KOPIYA" = true ] && APK=argeda-$CAB-kopiya.apk; [ "$CAB" = argeda ] && APK=argeda-$VNAME.apk
KS=${KEYSTORE:-$HERE/debug.p12}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
java -cp tools ApkSignV2 aligned.apk "$APK" "$KS" "${KEYSTORE_PASS:-android}" "${KEY_ALIAS:-androiddebugkey}"
python3 -I "$HERE/verify_v2.py" "$APK"
$AAPT2 dump badging "$APK" | grep -E "^package|application-label:"
echo "Готово: $OUT/$APK"
