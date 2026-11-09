package io.fastpix.exoplayer_data_sdk.events

import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.utils.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sends a pulse every 10 seconds while playback is active */
internal class PulseScheduler(
    private val sink: EventSink,
    /** Where the loop waits between pulses; tests pass one on virtual time */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    /** Where each pulse is sent: the main thread, like every other event */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val isScheduled = AtomicBoolean(false)
    private var job: Job? = null

    fun schedule() {
        if (isScheduled.get()) return

        isScheduled.set(true)
        Logger.log(TAG, "COROUTINE_START: scope=playerScope task=pulseLoop")
        job = scope.launch {
            while (isScheduled.get()) {
                delay(PULSE_INTERVAL_MS)
                if (isScheduled.get()) {
                    withContext(mainDispatcher) {
                        // The same guard as every other event, so nothing is sent after release
                        if (sink.canDispatch) sink.dispatch(PlayerEventType.pulse)
                    }
                }
            }
        }
    }

    fun cancel() {
        if (isScheduled.get()) {
            Logger.log(TAG, "COROUTINE_CANCELLED: scope=playerScope task=pulseLoop")
            isScheduled.set(false)
            job?.cancel()
        }
    }

    private companion object {
        const val PULSE_INTERVAL_MS = 10_000L
    }
}
