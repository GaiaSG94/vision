#!/usr/bin/env bash
# Build offline du debug APK (sam-vision-kotlin). Idempotent : relançable tel quel.
# Env : JAVA_HOME / ANDROID_HOME surchargables ; GRADLE_TIMEOUT (défaut 600 s).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_JDK="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2"

# JDK 17 Temurin : le JRE système 21 n'a pas de javac -> on ne garde JAVA_HOME
# que s'il fournit réellement un compilateur.
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
  if [[ -x "$DEFAULT_JDK/bin/javac" ]]; then
    export JAVA_HOME="$DEFAULT_JDK"
  else
    echo "ERREUR : aucun JDK avec javac (essayez $DEFAULT_JDK)." >&2
    exit 1
  fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[[ -d "$ANDROID_HOME" ]] || { echo "ERREUR : ANDROID_HOME absent : $ANDROID_HOME" >&2; exit 1; }

cd "$ROOT"
[[ -x gradlew ]] || chmod +x gradlew

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
timeout "${GRADLE_TIMEOUT:-600}" ./gradlew assembleDebug --offline

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "ERREUR : assembleDebug terminé sans APK attendu : $APK" >&2
  exit 1
fi
echo "APK : $APK ($(du -h "$APK" | cut -f1))"
