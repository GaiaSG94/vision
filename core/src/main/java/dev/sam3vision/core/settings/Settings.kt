package dev.sam3vision.core.settings

import dev.sam3vision.core.model.ObjectLimits

/**
 * Réglages de l'app. [samBaseUrl] vide = segmentation désactivée (la photo part
 * directement au LLM). Aucune valeur de clé n'est jamais écrite en dur.
 */
data class Settings(
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val samBaseUrl: String,
    val ttsApiKey: String = "",
    val ttsVoiceId: String = DEFAULT_TTS_VOICE_ID,
    /** Lecture automatique des réponses du chat (désactivable dans Réglages). */
    val ttsAutoplay: Boolean = true,
    /** Dictée : base URL / clé / modèle du moteur principal (Meta Muse). */
    val asrBaseUrl: String = DEFAULT_ASR_BASE_URL,
    val asrApiKey: String = "",
    val asrModel: String = DEFAULT_ASR_MODEL,
    /**
     * Annonce vocale des **positions** dès qu'un objet est détecté (mode Direct et
     * analyse photo) : « Personne à gauche, chien à droite. » Via ElevenLabs, jamais
     * le TTS système. `true` par défaut : c'est la fonction d'assistance attendue.
     */
    val ttsAnnouncePositions: Boolean = true,
    /** Intro vocale embarquée autorisée (bascule des Réglages, `true` par défaut). */
    val introEnabled: Boolean = true,
    /** Marqueur historique « intro déjà entendue », conservé pour le format de fichier
     * (l'intro ne s'appuie plus dessus : elle est rejouée à chaque ouverture complète). */
    val introPlayed: Boolean = false,
    /** Distances affichées sur le direct (hauteurs connues + sténopé, sans GPU). */
    val distanceOverlayEnabled: Boolean = true,
    /**
     * Direct avec détecteur : les frames partent à SAM `/live` sur Modal pendant que la
     * caméra tourne (masques, objets, distances, journal), comme demandé par le user le
     * 2026-10-25. Les **questions** partent quand même directement au LLM, sans attendre
     * SAM. `false` = vision LLM seule, aucun appel Modal, aucun GPU.
     */
    val liveSamEnabled: Boolean = true,
    /**
     * Champ de vision horizontal imposé à la caméra, en degrés : 0 = lu sur le
     * capteur. Sert quand le driver ne répond pas, ou pour affiner l'étalonnage.
     */
    val cameraFovDeg: Float = 0f,
    /** Plafond d'objets affichés à l'écran, commun au direct et à la photo. */
    val maxObjects: Int = ObjectLimits.DEFAULT_ON_SCREEN,
    /**
     * Région de la GPU SAM 3.1 : Europe (défaut) ou **mode US** (carte aux États-Unis).
     * Choisit l'endpoint appelé, n'allume aucune carte. Voir [resolveSamEndpoint].
     */
    val samRegion: SamRegion = SamRegion.Eu,
) {
    companion object {
        const val DEFAULT_LLM_BASE_URL = "https://api.inferhub.dev/v1"
        const val DEFAULT_LLM_MODEL = "ali/qwen3.8-omni-flash"

        /**
         * Voix ElevenLabs de départ : Sarah, voix « premade » utilisable en compte free.
         * Les voix de bibliothèque (ex. Rachel `21m00Tcm4TlvDq8ikWAM`) répondent 402 sans
         * crédits payants — choisies exprès ici pour qu'une installation fraîche parle.
         */
        const val DEFAULT_TTS_VOICE_ID = "EXAVITQu4vr4xnSDxMaL"

        /**
         * Voix « Jade US » du compte ElevenLabs, celle de l'intro embarquée
         * (`res/raw/intro_ihear.mp3`). Proposée dans les Réglages, mais pas par défaut :
         * voix de bibliothèque, elle répond 402 sur un compte free, là où Sarah est premade.
         */
        const val JADE_US_VOICE_ID = "TOXPlnYC7z5SQSV2Zgc5"

        /** API Meta (non compatible OpenAI) : la route est `{base}/asr/transcribe`. */
        const val DEFAULT_ASR_BASE_URL = "https://api.meta.ai/v1"
        const val DEFAULT_ASR_MODEL = "muse-voice-transcribe-1.0"

        val Default = Settings(
            llmBaseUrl = DEFAULT_LLM_BASE_URL,
            llmApiKey = "",
            llmModel = DEFAULT_LLM_MODEL,
            samBaseUrl = "",
            ttsApiKey = "",
            ttsVoiceId = DEFAULT_TTS_VOICE_ID,
            ttsAutoplay = true,
            asrBaseUrl = DEFAULT_ASR_BASE_URL,
            asrApiKey = "",
            asrModel = DEFAULT_ASR_MODEL,
            ttsAnnouncePositions = true,
            introEnabled = true,
            introPlayed = false,
            distanceOverlayEnabled = true,
            liveSamEnabled = true,
            cameraFovDeg = 0f,
            maxObjects = ObjectLimits.DEFAULT_ON_SCREEN,
        )
    }

    /**
     * Intro attendue à chaque ouverture complète de l'app (relance après fermeture,
     * pas au simple retour d'arrière-plan) : seule la coupure par la voix
     * (« Disable iHear intro », [introEnabled] = false) la fait taire définitivement.
     */
    val introPending: Boolean get() = introEnabled

    /** Plafond d'objets réellement appliqué (le réglage peut être hors bornes). */
    val objectsOnScreen: Int get() = ObjectLimits.clamp(maxObjects)

    /**
     * Vrai si un endpoint SAM est disponible : une URL manuelle, ou le preset de la
     * région choisie ([resolveSamEndpoint] — donc toujours vrai, sauf preset vide).
     */
    val segmentationEnabled: Boolean get() = resolveSamEndpoint().isNotBlank()

    /** Vrai si l'app peut appeler le LLM. */
    val llmConfigured: Boolean get() = llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank()

    /** Vrai si l'app peut appeler ElevenLabs (clé + voix renseignées). */
    val ttsConfigured: Boolean get() = ttsApiKey.isNotBlank() && ttsVoiceId.isNotBlank()

    /** Vrai si une réponse du chat doit être lue dès qu'elle arrive. */
    val ttsAutoRead: Boolean get() = ttsAutoplay && ttsConfigured

    /** Vrai si les positions détectées doivent être dites à voix haute. */
    val announcePositions: Boolean get() = ttsAnnouncePositions && ttsConfigured

    /** Vrai si la dictée Meta est utilisable (endpoint + clé renseignés). */
    val asrConfigured: Boolean get() = asrBaseUrl.isNotBlank() && asrApiKey.isNotBlank()

    /** Vrai si la dictée a un moteur : Meta, à défaut le repli ElevenLabs (clé TTS). */
    val sttConfigured: Boolean get() = asrConfigured || ttsApiKey.isNotBlank()

    /** Vrai si un champ de vision de repli a été saisi (sinon : lecture capteur). */
    val cameraFovForced: Boolean get() = cameraFovDeg > 0f
}

interface SettingsStore {
    suspend fun load(): Settings
    suspend fun save(s: Settings)
}

/** Implémentation en mémoire (tests, previews Compose). */
class MemorySettingsStore(initial: Settings = Settings.Default) : SettingsStore {
    private var current: Settings = initial

    override suspend fun load(): Settings = current

    override suspend fun save(s: Settings) {
        current = s
    }
}
