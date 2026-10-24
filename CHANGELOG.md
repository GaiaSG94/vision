# CHANGELOG — sam-vision-kotlin

## 2026-10-24

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
