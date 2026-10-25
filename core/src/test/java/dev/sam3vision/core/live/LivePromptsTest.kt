package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePromptsTest {

    @Test
    fun `les prompts par defaut sont deux concepts de guidage`() {
        assertEquals(2, LivePrompts.Default.size)
        assertTrue(LivePrompts.Default.containsAll(listOf("person", "door")))
        assertEquals(8, LivePrompts.MAX)
    }

    @Test
    fun `une liste vide retombe sur les defauts`() {
        assertEquals(LivePrompts.Default, LivePrompts.normalize(emptyList()))
    }

    @Test
    fun `une liste uniquement blanche retombe sur les defauts`() {
        assertEquals(LivePrompts.Default, LivePrompts.normalize(listOf("", "   ", "\t")))
    }

    @Test
    fun `les espaces sont retires et les doublons elimines`() {
        assertEquals(listOf("person", "car"), LivePrompts.normalize(listOf(" person ", "car", "person")))
    }

    @Test
    fun `la liste est bornee a huit prompts`() {
        val many = (1..12).map { "objet$it" }
        val normalized = LivePrompts.normalize(many)
        assertEquals(LivePrompts.MAX, normalized.size)
        assertEquals("objet1", normalized.first())
        assertEquals("objet8", normalized.last())
    }

    @Test
    fun `un prompt trop long est rejete`() {
        val tooLong = "x".repeat(LivePrompts.MAX_LENGTH + 1)
        assertEquals(listOf("cat"), LivePrompts.normalize(listOf(tooLong, "cat")))
        assertTrue(LivePrompts.normalize(listOf(tooLong)).isNotEmpty())
    }

    @Test
    fun `une liste entierement rejetee retombe sur les defauts`() {
        val tooLong = "y".repeat(LivePrompts.MAX_LENGTH + 1)
        assertEquals(LivePrompts.Default, LivePrompts.normalize(listOf(tooLong)))
    }

    @Test
    fun `un prompt a la longueur maximale est accepte`() {
        val atLimit = "z".repeat(LivePrompts.MAX_LENGTH)
        assertEquals(listOf(atLimit), LivePrompts.normalize(listOf(atLimit)))
    }
}
