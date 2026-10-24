#!/usr/bin/env bash
# Installe le debug APK sur l'émulateur, accorde CAMERA, lance l'app, screenshot /tmp.
# Idempotent : relançable ; reconstruit l'APK seulement s'il manque.
# Env : EMULATOR_SERIAL (défaut emulator-5554), SKIP_BUILD=1 pour ne pas builder.
set -euo pipefail

EMULATOR_SERIAL="${EMULATOR_SERIAL:-emulator-5554}"
PKG="dev.sam3vision.app"
ACT="$PKG/.MainActivity"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
SAFE_SERIAL="$(printf '%s' "$EMULATOR_SERIAL" | tr -cs 'a-zA-Z0-9' '_')"
SHOT="/tmp/samvision_screen_${SAFE_SERIAL}.png"

command -v adb >/dev/null || { echo "ERREUR : adb introuvable (ajoutez \$ANDROID_HOME/platform-tools au PATH)." >&2; exit 1; }
[[ -f "$APK" || "${SKIP_BUILD:-0}" = "1" ]] || bash "$ROOT/scripts/build.sh"
[[ -f "$APK" ]] || { echo "ERREUR : APK absent : $APK" >&2; exit 1; }

if ! timeout 30 adb devices | awk -v s="$EMULATOR_SERIAL" '$1==s && $2=="device"{ok=1} END{exit !ok}'; then
  echo "ERREUR : '$EMULATOR_SERIAL' n'est pas en ligne (adb devices)." >&2
  timeout 30 adb devices -l >&2
  exit 1
fi

echo "→ install -r $APK"
if ! timeout 180 adb -s "$EMULATOR_SERIAL" install -r "$APK"; then
  echo "INDICE : conflit de signature ? Faire : adb -s $EMULATOR_SERIAL uninstall $PKG puis relancer." >&2
  exit 1
fi

echo "→ pm grant CAMERA (sans effet si déjà accordée)"
timeout 30 adb -s "$EMULATOR_SERIAL" shell pm grant "$PKG" android.permission.CAMERA || \
  echo "  (grant ignoré : permission déjà accordée ou refusée par l'OS)"

echo "→ force-stop + am start $ACT"
timeout 30 adb -s "$EMULATOR_SERIAL" shell am force-stop "$PKG"
timeout 30 adb -s "$EMULATOR_SERIAL" shell am start -n "$ACT" >/dev/null
sleep 3   # le temps que Compose se dessine (sous la limite des 30 s)

echo "→ screencap $SHOT"
timeout 60 adb -s "$EMULATOR_SERIAL" shell screencap /sdcard/samvision_install.png
timeout 60 adb -s "$EMULATOR_SERIAL" pull /sdcard/samvision_install.png "$SHOT" >/dev/null
timeout 30 adb -s "$EMULATOR_SERIAL" shell rm -f /sdcard/samvision_install.png

echo "OK : $PKG installé et lancé sur $EMULATOR_SERIAL — capture : $SHOT"
