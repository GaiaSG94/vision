#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Conflit de PR -> chat ZeroClaw (A2A) branché sur InferHub, puis compte rendu sur la PR.

Deux modes, pour que le job GitHub Actions reste sous les 5 minutes exigées par
`ci-budget` :

  --spawn : appelé par le gate. Vérifie les garde-fous, écrit l'empreinte d'anti-boucle
            et relance CE même script en processus détaché (start_new_session), puis
            rend la main immédiatement. Ne sort jamais en erreur : un agent indisponible
            ne doit pas faire rougir la CI ni bloquer la file.
  --run   : le processus détaché. Lit le conflit via l'API, pose le message au chat
            ZeroClaw (daemon sur la machine du runner), attend la réponse et la publie
            en commentaire de la PR.

Config par variables d'environnement (toutes optionnelles, valeurs par défaut ci-dessous) :
  HERMES_ZC_URL          défaut http://127.0.0.1:42617   (passerelle du daemon zeroclaw)
  HERMES_ZC_AGENT        défaut hermes                    (alias d'agent, provider custom.inferhub)
  HERMES_ZC_TOKEN_FILE   défaut ~/.config/zeroclaw-serveur/token, repli /opt/zeroclaw/ephem/data/gateway-admin.token
  HERMES_AGENT_TIMEOUT   défaut 1500                     (secondes d'attente du chat)
  HERMES_STATE_DIR       défaut ~/.cache/hermes-agent    (empreintes anti-boucle + journaux)
  HERMES_AGENT_RESOLVE   défaut 1                        (0 = désactive toute saisine d'agent)
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = 'https://api.github.com'
LABEL_AGENT = 'hermes/agent-conflit'
LABEL_HUMAN = 'hermes/intervention-humaine'
CHECK_NAME = 'hermes-agent/conflit'
COOLDOWN_S = 20 * 60          # deux chats à moins de 20 min sur la même PR = boucle
MAX_SPAWN_PER_RUN = 3         # garde globale contre une rafale de PRs en conflit


def env(name, default):
    value = os.environ.get(name)
    return value if value not in (None, '') else default


def state_dir():
    path = os.path.expanduser(env('HERMES_STATE_DIR', '~/.cache/hermes-agent'))
    os.makedirs(path, exist_ok=True)
    return path


def stamp_path(num, sha):
    return os.path.join(state_dir(), 'pr-%s-%s.stamp' % (num, (sha or 'x')[:12]))


def already_spawned(num, sha):
    path = stamp_path(num, sha)
    if not os.path.exists(path):
        return False
    try:
        return time.time() - os.path.getmtime(path) < COOLDOWN_S
    except OSError:
        return True


def mark_spawned(num, sha):
    with open(stamp_path(num, sha), 'w') as fh:
        fh.write('%d %s\n' % (int(time.time()), sha or ''))


def gc_stamps():
    """Nettoie les empreintes de plus d'un jour (le cache ne doit pas grossir)."""
    cutoff = time.time() - 86400
    for name in os.listdir(state_dir()):
        full = os.path.join(state_dir(), name)
        try:
            if name.endswith('.stamp') and os.path.getmtime(full) < cutoff:
                os.remove(full)
        except OSError:
            pass


def zc_token():
    for candidate in (env('HERMES_ZC_TOKEN_FILE', '~/.config/zeroclaw-serveur/token'),
                      '/opt/zeroclaw/ephem/data/gateway-admin.token'):
        path = os.path.expanduser(candidate)
        try:
            with open(path, encoding='utf-8') as fh:
                token = fh.read().strip()
            if token:
                return token
        except OSError:
            continue
    return None


def build_prompt(repo, pr, files, recent):
    """Contexte complet du conflit : l'agent ne partage la mémoire de personne."""
    listing = '\n'.join('- %s' % name for name in files[:40]) or '- (liste indisponible)'
    log = '\n'.join('- %s' % line for line in recent[:12]) or '- (historique indisponible)'
    return (
        "Tu es l'agent hermes de la repo %s. La PR #%s « %s » est en conflit avec "
        "`%s`. Résous-la toi-même, sans rien demander.\n\n"
        "PR : head `%s` @ %s, base `%s` @ %s.\n"
        "Fichiers touchés par la PR :\n%s\n"
        "Commits récents sur `%s` :\n%s\n\n"
        "Méthode, dans l'ordre ; ne travaille jamais en dehors de la branche de la PR :\n"
        "1. `T=$(cat ~/.gh-token)` puis clone dans un dossier de travail hors des arbres "
        "partagés : `git clone https://x-access-token:$T@github.com/%s.git "
        "/tmp/hermes-pr-%s` (réutilise-le si présent) et `cd /tmp/hermes-pr-%s` ; "
        "`git switch %s` (la branche de la PR).\n"
        "2. `git merge origin/%s` (ou `git rebase origin/%s` s'il n'y a aucun commit local). "
        "Pour chaque fichier en conflit : garde les DEUX intentions, ne choisis jamais un "
        "côté aveuglément ; lis ce que chaque bord voulait (`git log --oneline -3 HEAD` "
        "puis `git log --oneline -3 origin/%s`).\n"
        "3. Si un garde-fou refuse (`python3 tools/check-kotlin-limits.py`, couche 0), "
        "corrige le code au lieu de contourner le garde.\n"
        "4. Valide avant de pousser : `./gradlew :core:test :app:testDebugUnitTest "
        "assembleDebug --offline` (JDK 17 et SDK Android déjà installés ; sans le "
        "`--offline` si le cache est incomplet). Vert = tests et build sans erreur.\n"
        "5. Pousse uniquement la branche de la PR : `git push "
        "https://x-access-token:$T@github.com/%s.git HEAD:%s`. Jamais sur `%s`, jamais "
        "un push forcé simple (`--force-with-lease` toléré si le dépôt a bougé).\n"
        "6. Compte rendu sur la PR #%s via l'API (gh n'est pas installé ici) : `curl -s "
        "-H \"Authorization: Bearer $T\" -H \"Accept: application/vnd.github+json\" -X "
        "POST https://api.github.com/repos/%s/issues/%s/comments -d \"{\\\"body\\\":\\\"…\\\"}\"` "
        "— dis ce qui était en conflit, comment tu l'as résolu, la commande de validation "
        "et son résultat.\n\n"
        "Après 3 tentatives ratées sur le même fichier : commente exactement le blocage et "
        "pose le label `%s` (même API, `POST /issues/%s/labels`). Ne merge pas, ne touche "
        "pas à `master`, à `.github/`, à `.mergify.yml` ni aux gardes, ne supprime aucun "
        "commit de la PR.\n"
    ) % (repo, pr['number'], (pr.get('title') or '').strip(), pr['base']['ref'],
         pr['head']['ref'], pr['head']['sha'][:10], pr['base']['ref'],
         pr['base']['sha'][:10], listing, pr['base']['ref'], log,
         repo, pr['number'], pr['number'], pr['head']['ref'],
         pr['base']['ref'], pr['base']['ref'], pr['base']['ref'],
         repo, pr['head']['ref'], pr['base']['ref'],
         pr['number'], repo, pr['number'],
         LABEL_HUMAN, pr['number'])


def send_chat(url, agent, token, prompt, timeout_s):
    """Message JSON-RPC `SendMessage` vers l'agent A2A ; rend le texte de la réponse."""
    body = {
        "jsonrpc": "2.0", "id": "hermes", "method": "SendMessage",
        "params": {"message": {
            "role": "ROLE_USER", "messageId": "hermes-conflit",
            "parts": [{"kind": "text", "text": prompt}]}},
    }
    req = urllib.request.Request(
        '%s/a2a/%s' % (url.rstrip('/'), agent),
        data=json.dumps(body).encode(), method='POST',
        headers={'Authorization': 'Bearer ' + token,
                 'Content-Type': 'application/json', 'A2A-Version': '1.0'})
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            payload = json.loads(resp.read().decode('utf-8', 'replace') or '{}')
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return None, 'passerelle injoignable : %r' % (exc,)
    if 'error' in payload:
        return None, 'erreur A2A : %s' % json.dumps(payload['error'])[:400]
    task = (payload.get('result') or {}).get('task') or {}
    texts = [part.get('text', '') for artifact in task.get('artifacts') or []
             for part in artifact.get('parts') or [] if isinstance(part, dict)]
    state = ((task.get('status') or {}).get('state')) or '?'
    if not texts:
        return None, 'réponse vide (état %s)' % state
    return '\n'.join(texts), None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--run', action='store_true', help='mode détaché (agent)')
    parser.add_argument('--spawn', action='store_true', help='garde-fous + lancement')
    parser.add_argument('--dry-run', action='store_true', help='affiche le prompt, ne l\'envoie pas')
    parser.add_argument('--malgre-etat', action='store_true',
                        help='ignore l\'état de la PR (relance manuelle après escalade)')
    parser.add_argument('--repo', default=os.environ.get('GITHUB_REPOSITORY', ''))
    parser.add_argument('--pr', type=int, default=0)
    args = parser.parse_args(argv)

    if env('HERMES_AGENT_RESOLVE', '1') == '0':
        print('[agent] HERMES_AGENT_RESOLVE=0 : saisine désactivée', flush=True)
        return 0
    if not args.repo or not args.pr:
        print('[agent] repo/PR manquants', file=sys.stderr, flush=True)
        return 0
    if args.spawn:
        return spawn(args)
    if args.run or args.dry_run or args.malgre_etat:
        return run(args)
    print('[agent] --spawn ou --run requis', file=sys.stderr, flush=True)
    return 0


def spawn(args):
    """Lance le chat en processus détaché ; retourne 0 dans tous les cas."""
    num, sha = args.pr, os.environ.get('HERMES_PR_HEAD_SHA', '')
    if already_spawned(num, sha):
        print('[agent] #%d : déjà saisi pour ce commit — rien de plus' % num, flush=True)
        return 0
    if count_recent_spawns() >= MAX_SPAWN_PER_RUN:
        print('[agent] #%d : plafond de saisines atteint sur cette période' % num, flush=True)
        return 0
    mark_spawned(num, sha)
    gc_stamps()
    log = open(os.path.join(state_dir(), 'pr-%d.log' % num), 'ab', buffering=0)
    cmd = [sys.executable, os.path.abspath(__file__), '--run',
           '--repo', args.repo, '--pr', str(num)]
    try:
        subprocess.Popen(cmd, stdout=log, stderr=log, stdin=subprocess.DEVNULL,
                         start_new_session=True, env=dict(os.environ))
        print('[agent] #%d : chat ZeroClaw lancé (agent %s sur %s)'
              % (num, env('HERMES_ZC_AGENT', 'hermes'),
                 env('HERMES_ZC_URL', 'http://127.0.0.1:42617')), flush=True)
    except OSError as exc:
        print('[agent] #%d : lancement impossible %r' % (num, exc), flush=True)
    return 0


def count_recent_spawns():
    cutoff = time.time() - COOLDOWN_S
    total = 0
    for name in os.listdir(state_dir()):
        if name.endswith('.stamp'):
            try:
                if os.path.getmtime(os.path.join(state_dir(), name)) > cutoff:
                    total += 1
            except OSError:
                pass
    return total


def gh(method, path, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header('Authorization', 'Bearer ' + token)
    req.add_header('Accept', 'application/vnd.github+json')
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as exc:
        return exc.code, None
    except (urllib.error.URLError, OSError):
        return 0, None


def conflict_facts(token, repo, num):
    """(PR, fichiers touchés, commits récents de la base) — tolérant aux pannes."""
    sc, pr = gh('GET', '/repos/%s/pulls/%d' % (repo, num), token)
    if sc != 200 or not isinstance(pr, dict):
        return None, [], []
    sc, files = gh('GET', '/repos/%s/pulls/%d/files?per_page=100' % (repo, num), token)
    names = [f['filename'] for f in files] if isinstance(files, list) else []
    sc, commits = gh('GET', '/repos/%s/commits?sha=%s&per_page=12'
                     % (repo, pr['base']['ref']), token)
    recent = [(c['commit']['message'] or '').splitlines()[0][:90]
              for c in commits] if isinstance(commits, list) else []
    return pr, names, recent


def eligible(token, repo, pr):
    if pr is None:
        return 'PR illisible'
    if pr.get('draft') or pr.get('state') != 'open':
        return 'PR fermée ou brouillon'
    if (pr.get('mergeable_state') or '') != 'dirty':
        return None  # plus de conflit : le chat n'a plus rien à faire
    if pr['head'].get('repo', {}).get('full_name') != repo:
        return 'branche depuis un fork : pas d\'agent'
    labels = [l['name'] for l in pr.get('labels') or []]
    if LABEL_HUMAN in labels or 'hold' in labels:
        return 'label d\'escalade humaine posé'
    return None


def post_back(args, repo, num, text, ok):
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if not token:
        print('[agent] GH_TOKEN absent : rien à publier', flush=True)
        return
    body = '_hermes · chat ZeroClaw `%s` (provider InferHub)_\n\n%s' % (
        env('HERMES_ZC_AGENT', 'hermes'), text)
    gh('POST', '/repos/%s/issues/%d/comments' % (repo, num), token,
       {'body': body[:65000]})
    title = 'Conflit pris en charge par l\'agent' if ok else 'Agent en échec — humain nécessaire'
    sc, pr = gh('GET', '/repos/%s/pulls/%d' % (repo, num), token)
    sha = (pr or {}).get('head', {}).get('sha') if sc == 200 else None
    if sha:
        gh('POST', '/repos/%s/check-runs' % repo, token, {
            'name': CHECK_NAME, 'head_sha': sha, 'status': 'completed',
            'conclusion': 'success' if ok else 'action_required',
            'output': {'title': title, 'summary': text[:60000]}})
        labels = [LABEL_AGENT] if ok else [LABEL_HUMAN]
        gh('POST', '/repos/%s/issues/%d/labels' % (repo, num), token, {'labels': labels})


def run(args):
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    pr, files, recent = conflict_facts(token, args.repo, args.pr)
    reason = None if args.malgre_etat else eligible(token, args.repo, pr)
    if reason:
        print('[agent] #%d : %s — saisine annulée' % (args.pr, reason), flush=True)
        return 0
    prompt = build_prompt(args.repo, pr, files, recent)
    if args.dry_run:
        print(prompt, flush=True)
        return 0
    url = env('HERMES_ZC_URL', 'http://127.0.0.1:42617')
    agent = env('HERMES_ZC_AGENT', 'hermes')
    zc = zc_token()
    if not zc:
        post_back(args, args.repo, args.pr,
                  'Jeton ZeroClaw introuvable (`HERMES_ZC_TOKEN_FILE`). Label `%s` posé.'
                  % LABEL_HUMAN, False)
        return 0
    print('[agent] #%d : chat %s -> %s (%d caractères)'
          % (args.pr, agent, url, len(prompt)), flush=True)
    answer, error = send_chat(url, agent, zc, prompt, int(env('HERMES_AGENT_TIMEOUT', '1500')))
    if error:
        post_back(args, args.repo, args.pr, 'Échec du chat : %s' % error, False)
        return 0
    post_back(args, args.repo, args.pr, answer.strip(), True)
    print('[agent] #%d : réponse publiée (%d caractères)' % (args.pr, len(answer)), flush=True)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as exc:  # jamais de stack trace dans la CI : un agent n'est pas critique
        print('[agent] exception : %r' % (exc,), flush=True)
        sys.exit(0)
