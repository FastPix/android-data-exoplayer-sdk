package io.fastpix.exoplayer_data_sdk

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.source.ads.AdPlaybackState
import io.fastpix.data.FastPixDataSDK
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.exoplayer_data_sdk.events.EventSink
import io.mockk.every
import io.mockk.mockk

internal data class Dispatched(val type: PlayerEventType, val position: Int?)

/** An [EventSink] over a mocked SDK that records every event dispatched through it */
internal class RecordingSdk(initialized: Boolean = true) {
    val events = mutableListOf<Dispatched>()

    /** Runs inside each dispatch, to read what the SDK would read from the player at that moment */
    var onDispatch: (PlayerEventType) -> Unit = {}

    val sdk: FastPixDataSDK = mockk {
        every { isInitialized() } returns initialized
        every { dispatchEvent(any(), any()) } answers {
            val type = firstArg<PlayerEventType>()
            events += Dispatched(type, secondArg())
            onDispatch(type)
        }
    }

    val sink = EventSink().also { it.sdk = sdk }

    val types: List<PlayerEventType> get() = events.map { it.type }

    fun clear() = events.clear()
}

/** One window and one period of [periodDurationUs], the period carrying [adPlaybackState] */
internal class SinglePeriodTimeline(
    private val adPlaybackState: AdPlaybackState = AdPlaybackState.NONE,
    private val periodDurationUs: Long = C.TIME_UNSET,
) : Timeline() {
    override fun getWindowCount() = 1

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long) = window

    override fun getPeriodCount() = 1

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        period.set(PERIOD_UID, PERIOD_UID, 0, periodDurationUs, 0, adPlaybackState, false)

    override fun getIndexOfPeriod(uid: Any) = if (uid == PERIOD_UID) 0 else C.INDEX_UNSET

    override fun getUidOfPeriod(periodIndex: Int): Any = PERIOD_UID

    private companion object {
        val PERIOD_UID = Any()
    }
}
