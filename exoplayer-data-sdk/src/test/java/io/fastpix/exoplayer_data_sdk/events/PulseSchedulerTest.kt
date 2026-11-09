package io.fastpix.exoplayer_data_sdk.events

import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.exoplayer_data_sdk.RecordingSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PulseSchedulerTest {
    private val recording = RecordingSdk()
    private val dispatcher = StandardTestDispatcher()
    private val pulse = PulseScheduler(recording.sink, CoroutineScope(dispatcher), dispatcher)

    private val pulses get() = recording.types.count { it == PlayerEventType.pulse }

    private fun advanceBy(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a pulse is sent every 10 seconds while scheduled`() {
        pulse.schedule()
        advanceBy(9_999)
        assertEquals(0, pulses)
        advanceBy(1)
        assertEquals(1, pulses)
        advanceBy(10_000)
        assertEquals(2, pulses)
    }

    @Test
    fun `scheduling again while scheduled runs one loop`() {
        pulse.schedule()
        pulse.schedule()
        advanceBy(10_000)
        assertEquals(1, pulses)
    }

    @Test
    fun `cancel stops the pulses`() {
        pulse.schedule()
        advanceBy(10_000)
        pulse.cancel()
        advanceBy(30_000)
        assertEquals(1, pulses)
    }

    @Test
    fun `a pulse can be scheduled again after a cancel`() {
        pulse.schedule()
        pulse.cancel()
        pulse.schedule()
        advanceBy(10_000)
        assertEquals(1, pulses)
    }

    @Test
    fun `a pulse already waiting is not sent once released`() {
        pulse.schedule()
        advanceBy(9_999)
        recording.sink.isReleased = true
        advanceBy(1)
        assertEquals(0, pulses)
    }

    @Test
    fun `a pulse already waiting is not sent while the SDK is uninitialized`() {
        val uninitialized = RecordingSdk(initialized = false)
        val scheduler = PulseScheduler(uninitialized.sink, CoroutineScope(dispatcher), dispatcher)
        scheduler.schedule()
        advanceBy(10_000)
        assertEquals(0, uninitialized.events.size)
    }
}
