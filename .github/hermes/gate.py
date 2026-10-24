#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Gate Hermes Lite — fraîcheur exigée AU MOMENT DU MERGE ([HERMES-REFRESH], porté du gate `duello`).

Règles :
- retard sans conflit  → rebase serveur (PUT /pulls/N/update-branch) ;
  le rebase déclenche un événement `synchronize` qui re-run ce workflow → cycle auto.
- vrai conflit         → check rouge + label `hermes/conflit` (escalade humaine).
- frais + checks requis verts → merge (rebase), une PR à la fois.
- run resté en `action_required` (push d'API) → relancé, puis verdict au
  passage suivant : sans ça la PR est immobile pour toujours.
"""

API = 'https://api.github.com'
CHECK_NAME = 'hermes-lite/verdict'
LABEL_OK = 'hermes/approuvee'
LABEL_CONFLIT = 'hermes/conflit'
LABEL_HUMAN = 'hermes/intervention-humaine'

import json
import os
import subprocess
import sys
import time


def api(method, url, body=None, token=None):
    import json as _json
    import urllib.request as _ur
    import urllib.error as _ue
    data = _json.dumps(body).encode() if body is not None else None
    req = _ur.Request(API + url, data=data, method=method)
    req.add_header('Authorization', 'Bearer ' + token)
    req.add_header('Accept', 'application/vnd.github+json')
    try:
        with _ur.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (_json.loads(raw) if raw else None)
    except _ue.HTTPError as e:
        payload = e.read()
        try:
            return e.code, _json.loads(payload)
        except Exception:
            return e.code, payload.decode('utf-8', errors='replace')


def ensure_label(repo, token, name, color, desc):
    api('POST', '/repos/%s/labels' % repo, token=token,
        body={'name': name, 'color': color, 'description': desc})


def set_label(pr, repo, token, name, add):
    num = pr['number']
    has = name in [l['name'] for l in pr.get('labels') or []]
    if add and not has:
        api('POST', '/repos/%s/issues/%d/labels' % (repo, num),
            token=token, body={'labels': [name]})
    elif not add and has:
        api('DELETE', '/repos/%s/issues/%d/labels/%s' % (repo, num, name),
            token=token)


def spawn_conflict_agent(pr, repo):
    """[HERMES-AGENT] Conflit -> chat ZeroClaw branché sur InferHub, sur la machine du runner.

    Relance détachée : le job CI reste sous le plafond de 5 min (`ci-budget`), c'est
    l'agent qui travaille ensuite hors du job et commente la PR. Jamais bloquant,
    jamais en échec : un agent indisponible ne doit pas faire rougir la file.
    """
    if env_flag('HERMES_AGENT_RESOLVE', '1') == '0':
        return 'désactivé'
    labels = [l['name'] for l in pr.get('labels') or []]
    if LABEL_HUMAN in labels or 'hold' in labels:
        return 'escalade humaine'
    if pr['head'].get('repo', {}).get('full_name') != repo:
        return 'fork'
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'conflict_agent.py')
    child = dict(os.environ, HERMES_PR_HEAD_SHA=pr['head']['sha'])
    try:
        subprocess.Popen([sys.executable, script, '--spawn', '--repo', repo,
                          '--pr', str(pr['number'])], env=child,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
        return 'agent saisi'
    except OSError as exc:
        print('[gate] #%d agent : %r' % (pr['number'], exc), flush=True)
        return 'erreur'


def escalate(pr, repo, token, sha, base_ref, note):
    """Check rouge + étiquettes de conflit, puis saisine de l'agent ZeroClaw."""
    agent = spawn_conflict_agent(pr, repo)
    detail = ("Agent ZeroClaw `%s` (InferHub) saisi — %s. Il publie son travail en "
              "commentaire de la PR. Pour rendre la main à un humain : label `%s`."
              % (os.environ.get('HERMES_ZC_AGENT', 'hermes'), agent, LABEL_HUMAN))
    create_check(repo, token, sha, False,
                 'Conflits avec %s — %s' % (base_ref, 'agent saisi' if agent == 'agent saisi'
                                            else 'intervention humaine'),
                 '%s %s' % (note, detail))
    set_label(pr, repo, token, LABEL_CONFLIT, True)
    set_label(pr, repo, token, LABEL_OK, False)
    return agent


def env_flag(name, default):
    value = os.environ.get(name)
    return value if value not in (None, '') else default


def refresh_branch(repo, token, pr):
    """Rebase serveur d'une PR en retard.

    Renvoie 'refreshed' / 'conflict' / 'behind' / 'error'.
    """
    num = pr['number']
    expected = pr['head']['sha']
    sc, resp = api('PUT', '/repos/%s/pulls/%d/update-branch' % (repo, num),
                   token=token,
                   body={'update_method': 'rebase', 'expected_head_sha': expected})
    if sc == 202:
        return 'refreshed'
    if sc in (409, 422):
        # GitHub refuse parfois le rebase alors qu'il calcule encore la
        # fusionnabilite : un 422 transitoire ne veut PAS dire conflit. On
        # relit la PR avant de conclure — sinon le gate publie un check ROUGE
        # sur une PR parfaitement fusionnable, et Mergify refuse ensuite de
        # l'embarquer (constat 24/10 : #30 verte, « unstable », verdict
        # rouge jamais leve → PR bloquee jusqu'au prochain commit).
        sc2, pr2 = api('GET', '/repos/%s/pulls/%d' % (repo, num), token=token)
        mergeable = (pr2 or {}).get('mergeable')
        state = (pr2 or {}).get('mergeable_state') or ''
        if mergeable is False and state == 'dirty':
            return 'conflict'
        print('[gate] #%d : rebase refuse (HTTP %s) mais PR fusionnable '
              '(mergeable=%s, etat=%s) — la file la prendra en charge.' %
              (num, sc, mergeable, state), flush=True)
        return 'behind'
    print('[gate] #%d update-branch %s : %s' % (num, sc, resp), flush=True)
    return 'error'


def rerun_blocked_runs(repo, token, sha, max_reruns=20):
    """Relance les workflow runs restés en `action_required`.

    Un push fait par l'API GitHub (`PUT /pulls/N/update-branch`, rebase
    serveur, commit de `github-actions[bot]`) crée des runs que GitHub met en
    attente d'approbation : conclusion `action_required`. Aucun check n'est
    publié — ni vert ni rouge — et la PR reste immobile indéfiniment, la file
    Mergify attendant un `check-success` qui n'arrivera jamais
    (constat 24/10 : #30 et #87, 15 runs bloqués).

    `POST /actions/runs/{id}/rerun` les exécute normalement (essai 2).
    Idempotent : un run relancé n'est plus `action_required`.
    Renvoie le nombre de runs relancés.
    """
    sc, data = api('GET', '/repos/%s/actions/runs?head_sha=%s&per_page=50'
                   % (repo, sha), token=token)
    if sc != 200 or not isinstance(data, dict):
        return 0
    reruns = 0
    for run in data.get('workflow_runs') or []:
        if reruns >= max_reruns:
            break
        if run.get('conclusion') != 'action_required':
            continue
        sc2, _ = api('POST', '/repos/%s/actions/runs/%s/rerun'
                     % (repo, run.get('id')), token=token)
        if sc2 in (200, 201, 202):
            reruns += 1
    return reruns


def create_check(repo, token, sha, ok, title, summary):
    api('POST', '/repos/%s/check-runs' % repo, token=token, body={
        'name': CHECK_NAME,
        'head_sha': sha,
        'status': 'completed',
        'conclusion': 'success' if ok else 'failure',
        'output': {'title': title, 'summary': summary},
    })


def required_checks_green(repo, token, pr, required):
    """Statuts de commit requis verts. Liste vide => considéré vert."""
    if not required:
        return True
    sha = pr['head']['sha']
    sc, data = api('GET', '/repos/%s/commits/%s/status' % (repo, sha), token=token)
    if sc != 200 or not data:
        return False
    states = {s['context']: s['state'] for s in data.get('statuses') or []}
    for ctx in required:
        if states.get(ctx) != 'success':
            return False
    return True


def settle_after_refresh(repo, token, num, required, attempts=10, pause=6, dry=False):
    """[HERMES-REFRESH] Ré-évalue la PR après le rebase serveur, DANS CE RUN.

    Le push effectué par `update-branch` est un push de bot : il ne déclenche
    AUCUN workflow (anti-récursion GitHub), et hermes-lite n'a ni cron ni
    dispatch possible avec GITHUB_TOKEN. Sans cette boucle, une PR rebasée
    resterait ouverte pour toujours : personne ne la re-évaluerait.
    Pollue borné (~60 s), puis rend la main (la veille événementielle
    rattrapera au prochain check_run de ce head).
    """
    for i in range(attempts):
        time.sleep(pause if i else 2)
        sc, pr = api('GET', '/repos/%s/pulls/%d' % (repo, num), token=token)
        if sc != 200 or not isinstance(pr, dict):
            continue
        if pr.get('merged'):
            return 'merged'
        sha = pr['head']['sha']
        base_ref = pr['base']['ref']
        sc, cmp_ = api('GET', '/repos/%s/compare/%s...%s' % (repo, base_ref, sha),
                       token=token)
        behind = cmp_.get('behind_by') if sc == 200 else -1
        behind = behind if isinstance(behind, int) else -1
        if behind > 0:
            continue  # rebase pas encore appliqué (ou main a bougé entre-temps)
        conflicts = (pr.get('mergeable') is False
                     and (pr.get('mergeable_state') or '') == 'dirty')
        if conflicts:
            escalate(pr, repo, token, sha, base_ref, 'Le rebase automatique a révélé un conflit.')
            return 'conflict'
        set_label(pr, repo, token, LABEL_OK, True)
        create_check(repo, token, sha, True, 'PR à jour — merge par le gate',
                     'Fraîche au moment du merge (settle après rebase serveur).')
        if dry:
            return 'dry'
        m = try_merge(repo, token, pr, required)
        print('[gate] #%d : settle merge → %s' % (num, m), flush=True)
        if m != 'waiting':
            return m
    return 'timeout'


def try_merge(repo, token, pr, required):
    """Merge la PR (méthode rebase) si fraîche et checks requis verts."""
    num = pr['number']
    sha = pr['head']['sha']
    # Re-vérification de fraîcheur au dernier moment (défense anti-course)
    sc, cmp_ = api('GET', '/repos/%s/compare/%s...%s'
                   % (repo, pr['base']['ref'], sha), token=token)
    if sc == 200 and cmp_.get('behind_by') not in (0, None):
        return 'behind'
    if not required_checks_green(repo, token, pr, required):
        return 'waiting'
    sc, resp = api('PUT', '/repos/%s/pulls/%d/merge' % (repo, num), token=token,
                   body={'sha': sha, 'merge_method': 'rebase'})
    if sc == 200:
        return 'merged'
    if sc == 409:
        return 'behind'
    print('[gate] #%d merge %s : %s' % (num, sc, resp), flush=True)
    return 'error'


def handle_pr(pr, repo, token, required, dry=False, refresh=True):
    num = pr['number']
    sha = pr['head']['sha']
    base_ref = pr['base']['ref']
    mergeable = pr.get('mergeable')           # None = calcul en cours
    mergeable_state = pr.get('mergeable_state') or ''

    # Débloquer d'abord : un run en attente d'approbation ne produit aucun
    # check, donc juger maintenant donnerait un verdict sur du vide.
    blocked = rerun_blocked_runs(repo, token, sha)
    if blocked:
        print('[gate] #%d : %d run(s) en action_required relancé(s) — '
              'verdict au prochain passage' % (num, blocked), flush=True)
        return 'rerun'

    sc, cmp_ = api('GET', '/repos/%s/compare/%s...%s' % (repo, base_ref, sha),
                   token=token)
    behind = cmp_.get('behind_by') if sc == 200 else -1
    behind = behind if isinstance(behind, int) else -1
    fresh = behind == 0
    conflicts = (mergeable is False and mergeable_state == 'dirty')

    if conflicts:
        escalate(pr, repo, token, sha, base_ref,
                 'Le gate détecte un conflit dès l ouverture de la PR.')
        return 'conflict'
    set_label(pr, repo, token, LABEL_CONFLIT, False)

    if not fresh:
        # Le rebase serveur n'est PAS un geste de merge : il remet la PR à
        # niveau pour que la file Mergify puisse l'embarquer. Le dry-run
        # (un seul pilote de merge) ne doit donc pas le bloquer — sinon une
        # PR devenue en retard sans nouveau push reste en rade pour toujours
        # (constat 24/10 : #30 et #63 déquées, vertes, immobiles 1 h).
        if dry and not refresh:
            print('[gate] #%d : en retard de %s commit(s) — rebase serveur (dry-run, refresh coupé)' % (num, behind))
            return 'dry'
        r = refresh_branch(repo, token, pr)
        print('[gate] #%d : en retard de %s → refresh %s' % (num, behind, r), flush=True)
        if r == 'refreshed':
            # Le push du rebase est un push de bot : aucun workflow ne se
            # déclenche. Ré-évaluation et merge DANS CE RUN.
            return settle_after_refresh(repo, token, num, required, dry=dry)
        if r == 'conflict':
            # Le rebase serveur a été refusé : conflit réel après tout.
            escalate(pr, repo, token, sha, base_ref, 'Le rebase serveur a été refusé.')
        return r

    set_label(pr, repo, token, LABEL_OK, True)
    create_check(repo, token, sha, True,
                 'PR à jour — merge par le gate',
                 'Fraîche au moment du merge ; %s' %
                 ('checks requis verts' if required else 'aucun check requis configuré'))
    if dry:
        print('[gate] #%d : fraîche — merge possible (dry-run)' % num)
        return 'dry'
    m = try_merge(repo, token, pr, required)
    print('[gate] #%d : merge → %s' % (num, m), flush=True)
    return m


def list_open_prs(repo, token):
    prs, page = [], 1
    while True:
        sc, plist = api('GET', '/repos/%s/pulls?state=open&per_page=100&page=%d'
                        % (repo, page), token=token)
        if sc != 200 or not isinstance(plist, list):
            break
        prs += plist
        if len(plist) < 100:
            break
        page += 1
    return prs


def main():
    token = os.environ['GH_TOKEN']
    repo = os.environ['GITHUB_REPOSITORY']
    event_name = os.environ['GITHUB_EVENT_NAME']
    required = [s.strip() for s in
                os.environ.get('HERMES_REQUIRED_CHECKS', '').split(',') if s.strip()]
    dry = os.environ.get('HERMES_LITE_DRY_RUN') == '1'
    # HERMES_LITE_REFRESH=0 : coupe le rebase serveur (défaut : actif,
    # y compris en dry-run — cf. commentaire de handle_pr).
    refresh = os.environ.get('HERMES_LITE_REFRESH', '1') != '0'

    for lbl in (LABEL_OK, LABEL_CONFLIT):
        ensure_label(repo, token, lbl, '0e8a16' if lbl == LABEL_OK else 'd73a4a',
                     'Posé par hermes-lite')

    with open(os.environ['GITHUB_EVENT_PATH']) as fh:
        event = json.load(fh)

    if event_name == 'workflow_dispatch':
        only = (event.get('inputs') or {}).get('pr_number') or None
        prs = list_open_prs(repo, token)
        if only:
            prs = [p for p in prs if p['number'] == int(only)]
    elif event_name == 'pull_request':
        prs = [event['pull_request']]
    elif event_name == 'check_run':
        # Un check vient de finir : ré-évalue les PR dont le head porte ce check.
        sha = event['check_run']['head_sha']
        prs = [p for p in list_open_prs(repo, token)
               if p['head']['sha'] == sha]
    else:
        prs = []

    merged = 0
    for pr in prs:
        if pr.get('draft') or pr.get('state') != 'open':
            continue
        try:
            r = handle_pr(pr, repo, token, required, dry, refresh)
            if r == 'merged':
                merged += 1
        except Exception as e:
            print('[gate] #%s exception : %r' % (pr.get('number'), e), flush=True)
    print('[gate] terminé : %d PR traitée(s), %d merge(s)' % (len(prs), merged),
          flush=True)


if __name__ == '__main__':
    main()
