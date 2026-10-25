# Installer sur le téléphone — `scripts/install-phone.sh`

Règle user du 2026-10-25 : **plusieurs agents pilotent le même téléphone** (`2c6852ac`) ;
plus personne n'installe « son » APK à la main, et le téléphone doit rester sur la
**dernière version de GitHub** (`origin/master`).

## La commande

```bash
scripts/install-phone.sh            # build origin/master + install si plus récent
scripts/install-phone.sh --status   # installé vs GitHub, sans rien toucher
```

Options utiles :

| Option | Effet |
|---|---|
| `--tree DIR` | bâtir un worktree précis (essai d'une branche) |
| `--apk FILE` | installer une APK déjà construite |
| `--if-free` | ne rien faire si un agent tient le téléphone (usage cron) |
| `--force` | réinstaller même à version identique |
| `--allow-downgrade` | autoriser une build plus vieille (débogage assumé) |

Le script prend le verrou `~/bin/phone` le temps de l'installation, compare les
`versionCode`, installe, puis **relit la version sur l'appareil** pour confirmer. Il
écrit `~/.sam-phone/etat` (sha + version + date).

## Pourquoi un APK périmé ne peut plus écraser la version installée

`versionCode` = **date du commit HEAD** (`app/build.gradle.kts`), `versionName` =
`1.0.<nombre de commits>-<sha court>`. Android refuse par défaut une installation dont
le `versionCode` est inférieur (`INSTALL_FAILED_VERSION_DOWNGRADE`) : un agent qui a
bâti sur un master plus vieux est bloqué par le système, pas par la politesse.

Conséquence voulue : pour tester sur le téléphone, on rebâtit sur `origin/master`
(ou on passe `--allow-downgrade` en connaissance de cause, jamais pour « juste voir »).

## Garder le téléphone à jour tout seul

Une entrée de crontab (poste) relance le script toutes les 10 minutes, en n'agissant
que si le téléphone est libre et si `origin/master` a bougé :

```
*/10 * * * * bash -c 'S=$HOME/Bureau/sam-vision-kotlin; git -C $S fetch -q origin; T=$(mktemp); git -C $S show origin/master:scripts/install-phone.sh > $T; bash $T --master --if-free --quiet; rm -f $T' >> $HOME/.sam-phone/cron.log 2>&1
```

Le script vient de `origin/master` lui-même : l'outil suit GitHub comme le reste.
