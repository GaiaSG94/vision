#!/usr/bin/env bash
# Recette : tap sur l'accueil = mode vidéo (auto-démarré) ; appui long = voix (pendant la vidéo).
export LC_ALL=C.UTF-8 LANG=C.UTF-8
set -uo pipefail
S="${EMULATOR_SERIAL:-emulator-5554}"
PKG=dev.sam3vision.app
APK="${APK:-/home/baptiste/tmp/sam-wt-tap-video-hold-voix/app/build/outputs/apk/debug/app-debug.apk}"
ADB=$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")
U=/tmp/tap-ui.xml
call() { timeout 40 "$ADB" -s "$S" shell "$@" 2>&1; }
dump() { for _ in 1 2 3; do call "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" > "$U"; grep -q '</hierarchy>' "$U" && return 0; done; return 1; }
ui() { dump; grep -o 'text="[^"]*"' "$U" | sed 's/text=//' | grep -v '^""$' | tr '\n' '|' | cut -c1-240; echo; }
vib() { call "dumpsys vibrator_manager" | grep -m1 isVibrating | tr -d ' '; }

echo "### 0. install"
timeout 240 "$ADB" -s "$S" install -r "$APK" | tail -1
call "pm grant $PKG android.permission.CAMERA"
call "pm grant $PKG android.permission.RECORD_AUDIO"
call "am force-stop $PKG" >/dev/null; call "am start -n $PKG/.MainActivity" >/dev/null; sleep 9
# l'app reprend l'écran où elle était : on revient à l'accueil avant de tester
for _ in 1 2 3; do dump; grep -q 'Photographier' "$U" && break
  call "input keyevent KEYCODE_BACK" >/dev/null; sleep 2; done

echo "### 1. Accueil : les deux gestes annoncés"
ui

echo "### 2. Un tap sur l'aperçu -> mode vidéo + démarrage"
call "input tap 540 1500" >/dev/null; sleep 6
echo "  écran : $(ui)"
echo "  vibreur : $(vib)"

echo "### 3. Appui long 4 s pendant la vidéo -> voix"
(call "input swipe 540 1500 540 1500 4000" >/dev/null &)
sleep 2
echo "  pendant : $(ui | grep -o "J'ÉCOUTE[^|]*\|Parle[^|]*\|DICTÉE[^|]*\|Recording[^|]*\|RECORDING[^|]*" | head -2 | tr '\n' ' ')"
sleep 3
echo "  après le lever : $(ui | cut -c1-160)"

echo "### 4. Second tap : le bandeau de dictée se ferme, puis le tap suivant arrête et envoie"
call "input tap 540 1500" >/dev/null; sleep 2   # ferme le bandeau de dictée
call "input tap 540 1500" >/dev/null; sleep 4
echo "  écran : $(ui | cut -c1-200)"
sleep 8
echo "  plus tard : $(ui | cut -c1-200)"

echo "### 5. crash ?"
echo "  buffer crash : $(call "logcat -d -b crash" | grep -c "$PKG")"
echo "  pid : $(call "pidof $PKG")"