package dev.sam3vision.core.live

/**
 * Faut-il (re)lancer un réveil préventif du GPU ?
 *
 * `lastAt` = horodatage du dernier réveil tenté (0 = jamais), `windowMs` = fenêtre
 * anti-rallumage. Un réveil coûte quelques centimes et garde la carte allumée
 * [LiveSession] secondes de plus : inutile de le rejouer à chaque navigation.
 */
fun shouldPrewarm(now: Long, lastAt: Long, windowMs: Long): Boolean =
    lastAt <= 0L || now - lastAt >= windowMs
