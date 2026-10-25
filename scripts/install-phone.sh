#!/usr/bin/env bash
# install-phone — LA seule façon d'installer SAM Vision sur le téléphone (règle user 2026-10-25).
#
#   install-phone                              # dernière version de GitHub (origin/master)
#   install-phone --tree ~/tmp/sam-wt-ma-tache # build d'un worktree précis (essai)
#   install-phone --apk chemin/app-debug.apk   # APK déjà construite
#   install-phone --status                     # installé vs GitHub, sans rien toucher
#
#   --if-free           ne rien faire si un autre agent tient le téléphone (usage cron)
#   --reinstall         réinstaller même si la version est identique
#   --allow-downgrade   autoriser une build plus ancienne que l'installée (débogage)
#   --quiet             une ligne de sortie, pour les journaux
#
# Deux verrous, dans cet ordre :
#   1. le `versionCode` est la date du commit (app/build.gradle.kts) : Android refuse
#      une build plus ancienne (INSTALL_FAILED_VERSION_DOWNGRADE), donc un APK périmé
#      ne peut plus écraser la version du téléphone ;
#   2. le verrou partagé `~/bin/phone` protège la manipulation elle-même.
#
# État : ~/.sam-phone/etat (sha + versionCode installés). La cible GitHub se lit avec
# `git -C dépôt fetch` puis `rev-parse origin/master` : c'est la référence.
set -uo pipefail

APP_ID="dev.sam3vision.app"
REPO="${SAM_REPO:-$HOME/Bureau/sam-vision-kotlin}"
WT="${SAM_PHONE_WT:-$HOME/tmp/sam-phone-master}"
STATE_DIR="$HOME/.sam-phone"
STATE="$STATE_DIR/etat"
PHONE="$HOME/bin/phone"
LOG_PREFIX="[install-phone]"

JDK_DEFAULT="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2"

MODE="master"
TREE=""
APK=""
IF_FREE=0
FORCE=0
ALLOW_DOWNGRADE=0
QUIET=0

say() { [[ $QUIET -eq 0 ]] || echo "$LOG_PREFIX $*"; }
die() { echo "$LOG_PREFIX ERREUR : $*" >&2; exit 1; }
ok() { echo "$LOG_PREFIX ✓ $*"; }

# Information sans conséquence : toujours affichée (le cron garde une trace), code 0.
note() { echo "$LOG_PREFIX $*"; }

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; }

parse_args() {
  while (($#)); do
    case "$1" in
      --master) MODE="master"; shift ;;
      --tree) MODE="tree"; TREE="${2:?chemin manquant}"; shift 2 ;;
      --apk) MODE="apk"; APK="${2:?chemin manquant}"; shift 2 ;;
      --status) MODE="status"; shift ;;
      --if-free) IF_FREE=1; shift ;;
      --reinstall) FORCE=1; shift ;;
      --allow-downgrade) ALLOW_DOWNGRADE=1; shift ;;
      --quiet) QUIET=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "argument inconnu : $1" ;;
    esac
  done
}

# ---------------------------------------------------------------- git / APK -----
master_sha() {
  git -C "$REPO" fetch -q origin master 2>/dev/null || die "fetch origin master impossible"
  git -C "$REPO" rev-parse --verify --quiet FETCH_HEAD || die "FETCH_HEAD introuvable dans $REPO"
}

# Worktree détaché sur [sha] : on bâtit exactement ce que GitHub a, pas la branche locale.
checkout_master() {
  local sha="$1"
  if [[ ! -d "$WT/.git" && ! -f "$WT/.git" ]]; then
    git -C "$REPO" worktree add --detach "$WT" "$sha" >/dev/null 2>&1 || die "worktree impossible : $WT"
  else
    git -C "$WT" checkout -q --detach "$sha" || die "checkout $sha impossible dans $WT"
    git -C "$WT" reset -q --hard "$sha"
  fi
  [[ -f "$WT/local.properties" ]] || cp "$REPO/local.properties" "$WT/local.properties" 2>/dev/null || true
}

build_apk() {
  local dir="$1"
  local jdk="${JAVA_HOME:-}"
  [[ -n "$jdk" && -x "$jdk/bin/javac" ]] || jdk="$JDK_DEFAULT"
  [[ -x "$jdk/bin/javac" ]] || die "aucun JDK avec javac (essayé $JDK_DEFAULT)"
  export JAVA_HOME="$jdk" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
  say "build $dir (JAVA_HOME=$JAVA_HOME)"
  ( cd "$dir" && timeout "${SAM_BUILD_TIMEOUT:-900}" ./gradlew :app:assembleDebug --console=plain -q ) \
    || die "gradle a échoué dans $dir"
  APK="$dir/app/build/outputs/apk/debug/app-debug.apk"
  [[ -f "$APK" ]] || die "APK absente après build : $APK"
}

# versionCode / versionName gravés dans l'APK (écrits par Gradle à côté d'elle).
apk_meta() {
  local meta
  meta="$(dirname "$1")/output-metadata.json"
  [[ -f "$meta" ]] || die "output-metadata.json absent à côté de $1 (rebuild propre)"
  python3 - "$meta" <<'PY'
import json, sys
el = json.load(open(sys.argv[1]))["elements"][0]
print(el["versionCode"], el["versionName"])
PY
}

# ---------------------------------------------------------------- téléphone -----
adb_() { adb -s "$SERIAL" "$@"; }

installed_meta() {  # "versionCode versionName" ou vide si l'app n'est pas installée
  local line
  line="$(adb_ shell "dumpsys package $APP_ID 2>/dev/null" | grep -m1 -oE 'versionCode=[0-9]+ .*versionName=[^ ]+' || true)"
  [[ -n "$line" ]] || return 0
  local code name
  code="${line#versionCode=}"; code="${code%% *}"
  name="$(printf '%s' "$line" | sed -E 's/.*versionName=([^ ]+).*/\1/')"
  echo "$code $name"
}

phone_busy() { "$PHONE" status 2>&1 | grep -q "OCCUPÉ" ; }

# Le verrou n'est pas décoratif : sans lui, deux agents installent en même temps et
# `adb install` peut laisser le téléphone sur une build interrompue.
take_lock() {
  "$PHONE" take --task "install-phone $1" --ttl 10m >/dev/null 2>&1     || die "téléphone occupé par un autre agent — réessaie quand il est libre"
}
release_lock() { "$PHONE" release >/dev/null 2>&1 || true; }

write_state() {  # sha, versionCode, versionName
  mkdir -p "$STATE_DIR"
  printf 'sha=%s\nversionCode=%s\nversionName=%s\nat=%s\n' \
    "$1" "$2" "$3" "$(date -Iseconds)" > "$STATE"
}

read_state() { grep -E "^$1=" "$STATE" 2>/dev/null | cut -d= -f2- ; }

cmd_status() {
  local target_meta installed
  target_meta="$(apk_meta "$1")"
  installed="$(installed_meta)"
  if [[ -n "$installed" ]]; then echo "téléphone : $installed"; else echo "téléphone : rien"; fi
  echo "github    : $target_meta  (${2:-origin/master})"
  echo "état local: sha=$(read_state sha) au $(read_state at)"
}

main() {
  parse_args "$@"
  SERIAL="${PHONE_SERIAL:-2c6852ac}"
  command -v adb >/dev/null || die "adb introuvable"

  sha=""
  prepare_source
  local built_code built_name
  read -r built_code built_name <<<"$(apk_meta "$APK")" || die "métadonnées illisibles"
  [[ "$MODE" == "status" ]] && { cmd_status "$APK" "$sha"; return 0; }

  if [[ $IF_FREE -eq 1 ]] && phone_busy; then
    note "téléphone occupé — rien installé (--if-free)"
    return 0
  fi
  install_if_needed "$sha" "$built_code" "$built_name"
}

# Construit (ou localise) l'APK à installer selon le mode, et remplit [sha].
prepare_source() {
  case "$MODE" in
    master|status)
      sha="$(master_sha)"
      checkout_master "$sha"
      build_apk "$WT"
      ;;
    tree)
      sha="$(git -C "$TREE" rev-parse --short=7 HEAD 2>/dev/null || true)"
      build_apk "$TREE"
      ;;
    apk) : ;;
  esac
  [[ -f "$APK" ]] || die "APK introuvable : $APK"
}

# Compare à ce qui est installé puis installe : plus vieux = refusé, identique = rien.
install_if_needed() {
  local sha="$1" code="$2" name="$3" installed inst_code
  installed="$(installed_meta)"
  inst_code="${installed%% *}"
  if [[ -n "$installed" && "$inst_code" == "$code" ]] && [[ $FORCE -eq 0 ]]; then
    note "déjà à jour : $name ($code)"
    return 0
  fi
  if [[ -n "$inst_code" && "$code" -lt "$inst_code" ]] && [[ $ALLOW_DOWNGRADE -eq 0 ]]; then
    die "refus : build plus ancienne ($code < $inst_code installée). Rebuild sur origin/master, ou --allow-downgrade."
  fi
  take_lock "$name"
  local flags=(-r)
  [[ $ALLOW_DOWNGRADE -eq 1 ]] && flags+=(-d)
  if ! adb_ install "${flags[@]}" "$APK" >/dev/null 2>&1; then
    release_lock
    die "adb install a échoué (signature différente ? désinstaller à la main)"
  fi
  local after; after="$(installed_meta)"
  release_lock
  [[ "${after%% *}" == "$code" ]] || die "installée, mais versionCode inattendu ($after)"
  write_state "${sha:-local}" "$code" "$name"
  ok "installé $name ($code) sur $SERIAL"
}

main "$@"
