package dev.sam3vision.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIntroTest {

    @Test
    fun `l'intro est active par defaut`() {
        assertTrue(Settings.Default.introEnabled)
    }

    @Test
    fun `le codec du fichier prive fait l'aller-retour de l'intro`() {
        val off = Settings.Default.copy(introEnabled = false)
        assertFalse(SettingsCodec.decode(SettingsCodec.encode(off)).introEnabled)
        assertTrue(SettingsCodec.decode(SettingsCodec.encode(Settings.Default)).introEnabled)
    }

    @Test
    fun `un reglage d'avant l'intro reste avec l'intro active`() {
        val decoded = SettingsCodec.decode("llmBaseUrl=x\nllmApiKey=y\nttsVoiceId=TOXPlnYC7z5SQSV2Zgc5\n")
        assertTrue(decoded.introEnabled)
    }
}