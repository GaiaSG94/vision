# CHANGELOG — sam-vision-kotlin

## 2026-10-24 — hermes : un conflit ouvre un chat ZeroClaw (InferHub)

- **Le gate ne dit plus « intervention humaine » et s'arrête là.** Sur
  `mergeable_state = dirty`, `gate.py` saisit l'agent `hermes` du daemon ZeroClaw
  (provider `custom.inferhub_ci` → `https://api.inferhub.dev/v1`, modèle `combo/cheap`)
  via A2A `POST /a2a/hermes` sur la machine du runner, **en processus détaché** pour
  rester sous les 5 min de `ci-budget`.
- L'agent rebase la branche de la PR, résout en gardant les deux intentions, valide
  (`:core:test`, `:app:testDebugUnitTest`, `assembleDebug`), pousse en
  `--force-with-lease` sur la branche de la PR et commente son compte rendu. Ni merge,
  ni `master`, ni gardes. 3 tentatives puis `hermes/intervention-humaine`.
- **Garde-fous** : une saisine par tête de PR (empreinte + cooldown 20 min), plafond de
  3 saisines par période, fork ignoré, labels `hold`/`hermes/intervention-humaine`
  respectés, `vars.HERMES_AGENT_RESOLVE=0` coupe tout, tout échec d'agent se traduit par
  un commentaire et jamais par une CI rouge.
- Fichiers : `.github/hermes/conflict_agent.py` (nouveau, stdlib seule),
  `.github/hermes/gate.py` (escalade unifiée `escalate()`), `.github/workflows/hermes-lite.yml`
  (env), `docs/CI-QUEUE.md` (section « Conflit : l'agent hermes ouvre un chat ZeroClaw »).

## 2026-10-24

- **Dictée vocale Meta Muse dans le Chat** (`feat/muse-dictation`) : bouton « Dicter »,
  micro 16 kHz mono (60 s max, PCM en mémoire seulement), client `MetaAsrClient`
  (`POST https://api.meta.ai/v1/asr/transcribe`, multipart `request` + `audio`), texte inséré
  dans le champ de question. Réglages : base URL / clé / modèle ASR + « Tester la dictée Meta ».
  15 tests JVM ajoutés (79 core + 4 app), `assembleDebug` vert, recette émulateur jouée.
  **Blocage externe** : le compte Meta n'a pas de moyen de paiement → `402
  billing_not_configured` sur tout appel ; la trajectoire est validée jusqu'à cet code
  (plus de `400 Malformed multipart` — OkHttp ajoutait un `Content-Length` par part).
  Détail : `docs/META-ASR.md`.

- **L'app Expo est remplacée par une app Android native Kotlin/Compose**
  (`~/Bureau/sam-vision-kotlin`, modules `:core` pur-JVM testé + `:app` Compose/CameraX).
  L'app Expo part en `legacy-expo-app/` dans l'ancien dépôt.
- **Backend Modal fermé : 0 GPU, coût futur nul.** Plus de chez nous : le téléphone est
  client unique. `modal container list` vide, 16 apps stopped (0,97 $ metered compensés
  par crédits, facturé 0,00 $).
- **LLM branché directement sur InferHub** (`https://api.inferhub.dev/v1`, compatible
  OpenAI, `ali/qwen3.8-omni-flash`) : clés et facturation côté utilisateur, plus de proxy.
- **La segmentation SAM 3.1 redevient optionnelle** : `samBaseUrl` (Réglages) vide ⇒
  bandeau « Segmentation désactivée », la photo va au LLM sans masque. Coller l'URL de
  l'endpoint Modal redéployé (`deploy_backend.sh --skip-smoke`) la réactive ; contrat
  `/health` + `/segment` inchangé.
- `:core` : 31 tests JVM verts (clients InferHub/SAM, masques, réglages chiffrés,
  sanitizeur de texte, cas d'usage) via serveur HTTP maison, zéro réseau.
- `:app` : `assembleDebug` vert, APK ~21 Mo installé et testé sur emulator-5554
  (capture → description → chat).
- Docs et garde-fous ajoutés : `README.md`, `docs/TESTING.md`,
  `tools/check-kotlin-limits.py`, `scripts/build.sh`, `scripts/install-emulator.sh`.
- Reste ouvert : dépassement signalé `AnalysisScreen()` 64 lignes ; endpoint SAM non
  redéployé (défaut = désactivé) ; `security-crypto` API dépréciée (repli fichier privé).
