package dev.sam3vision.app

/**
 * Logique pure de l'intro vocale (hors Compose) : libellé de la bascule, visibilité
 * de la bulle du chat et phrase de coupe « Disable iHear intro ». Testée en JVM
 * (`app.UiLogicTest`).
 */

/** Libellé de la bascule « intro » des Réglages. */
fun introToggleLabel(enabled: Boolean): String =
    if (enabled) "Intro à la 1re ouverture : on" else "Intro à la 1re ouverture : off"

/**
 * Le texte exact de l'intro, mot pour mot celui du MP3 embarqué (`intro_ihear.mp3`,
 * voix Jade US) : la bulle du chat affiche la même phrase que la voix dit.
 */
val IHEAR_INTRO_TEXT = "Welcome to iHear. To record your surroundings, tap anywhere on " +
    "the screen to start the video, and tap again to stop and analyze it. To ask a " +
    "question or make a request, tap and hold while you speak, then release. To stop " +
    "hearing this intro, press and hold the screen long enough to say, " +
    "\"Disable iHear intro.\""

/**
 * La bulle d'intro (premier message du chat) est posée seulement quand les réglages
 * sont lus, que l'intro est autorisée et qu'elle n'a jamais été entendue.
 */
fun introBubbleVisible(settingsReady: Boolean, introEnabled: Boolean, introPlayed: Boolean): Boolean =
    settingsReady && introEnabled && !introPlayed

/** Vrai si la dictée demande la coupe de l'intro (« Disable iHear intro » sans casse). */
fun isIntroDisablePhrase(text: String): Boolean =
    text.trim().lowercase().contains("disable ihear intro")
