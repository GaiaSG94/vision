package dev.sam3vision.core.settings

import dev.sam3vision.core.model.ObjectLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun `le plafond d'objets survit a un aller-retour`() {
        val saved = Settings.Default.copy(maxObjects = 9)
        assertEquals(9, SettingsCodec.decode(SettingsCodec.encode(saved)).maxObjects)
    }

    @Test
    fun `un ancien fichier sans plafond reprend le defaut`() {
        val legacy = SettingsCodec.encode(Settings.Default).lines().filterNot { it.startsWith("maxObjects=") }
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, SettingsCodec.decode(legacy.joinToString("\n")).maxObjects)
    }

    @Test
    fun `un plafond aberrant est borne a la lecture`() {
        val broken = SettingsCodec.encode(Settings.Default).replace("maxObjects=4", "maxObjects=999")
        assertEquals(ObjectLimits.MAX_ON_SCREEN, SettingsCodec.decode(broken).maxObjects)
        val text = SettingsCodec.encode(Settings.Default).replace("maxObjects=4", "maxObjects=abc")
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, SettingsCodec.decode(text).maxObjects)
    }

    @Test
    fun `l'acces objectsOnScreen borne toujours le reglage`() {
        assertEquals(ObjectLimits.MAX_ON_SCREEN, Settings.Default.copy(maxObjects = 50).objectsOnScreen)
        assertEquals(4, Settings.Default.objectsOnScreen)
    }
}
