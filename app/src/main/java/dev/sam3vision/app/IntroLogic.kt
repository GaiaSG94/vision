package dev.sam3vision.app

/**
 * Logique pure de l'intro vocale (hors Compose) : libellé de la bascule et
 * visibilité de l'écran de garde. Testée en JVM (`app.UiLogicTest`).
 */

/** Libellé de la bascule « intro » des Réglages. */
fun introToggleLabel(enabled: Boolean): String =
    if (enabled) "Intro à la 1re ouverture : on" else "Intro à la 1re ouverture : off"

/**
 * Faut-il poser l'écran de garde tactile ? Seulement quand les réglages sont lus,
 * que l'intro est autorisée et qu'elle n'a jamais été entendue.
 */
fun introGateVisible(settingsReady: Boolean, introEnabled: Boolean, introPlayed: Boolean): Boolean =
    settingsReady && introEnabled && !introPlayed
