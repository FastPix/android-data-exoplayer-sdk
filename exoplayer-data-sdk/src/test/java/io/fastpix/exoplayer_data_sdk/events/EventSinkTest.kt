package io.fastpix.exoplayer_data_sdk.events

import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.exoplayer_data_sdk.Dispatched
import io.fastpix.exoplayer_data_sdk.RecordingSdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSinkTest {

    @Test
    fun `dispatches while the SDK is initialized`() {
        assertTrue(RecordingSdk(initialized = true).sink.canDispatch)
    }

    @Test
    fun `does not dispatch while the SDK is uninitialized`() {
        assertFalse(RecordingSdk(initialized = false).sink.canDispatch)
    }

    @Test
    fun `does not dispatch once released`() {
        val sink = RecordingSdk().sink
        sink.isReleased = true
        assertFalse(sink.canDispatch)
    }

    @Test
    fun `an untracked player passes the guard but its events go nowhere`() {
        val sink = EventSink()
        assertTrue(sink.canDispatch)
        sink.dispatch(PlayerEventType.play)
    }

    @Test
    fun `forwards the event type and position`() {
        val recording = RecordingSdk()
        recording.sink.dispatch(PlayerEventType.pause, 1_500)
        recording.sink.dispatch(PlayerEventType.play)
        assertEquals(
            listOf(Dispatched(PlayerEventType.pause, 1_500), Dispatched(PlayerEventType.play, null)),
            recording.events,
        )
    }
}
