package io.fastpix.exoplayer_data_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidTransitionsTest {

    @Test
    fun `a new view can only start with play or an error`() {
        assertEquals(setOf(PlayerEvent.PLAY, PlayerEvent.ERROR), validTransitions[null])
    }

    @Test
    fun `every playback state can move to an error`() {
        PlayerEvent.entries
            .filter { it != PlayerEvent.ERROR && it != PlayerEvent.VARIANT_CHANGED }
            .forEach { state -> assertTrue("$state -> ERROR", PlayerEvent.ERROR in validTransitions.getValue(state)) }
    }

    @Test
    fun `variant changed is never a state of its own`() {
        assertFalse(validTransitions.containsKey(PlayerEvent.VARIANT_CHANGED))
    }

    @Test
    fun `an error is not repeated`() {
        assertFalse(PlayerEvent.ERROR in validTransitions.getValue(PlayerEvent.ERROR))
    }

    @Test
    fun `nothing pauses after the end`() {
        assertFalse(PlayerEvent.PAUSE in validTransitions.getValue(PlayerEvent.ENDED))
    }

    @Test
    fun `play is not sent twice in a row`() {
        assertFalse(PlayerEvent.PLAY in validTransitions.getValue(PlayerEvent.PLAY))
    }
}
