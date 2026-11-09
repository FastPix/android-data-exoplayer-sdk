package io.fastpix.exoplayer_data_sdk.events

import io.fastpix.data.FastPixDataSDK
import io.fastpix.data.domain.enums.PlayerEventType

internal const val TAG = "FastPixBaseExoPlayer"

/**
 * The tracked SDK, if any, and the guard every event passes: nothing is sent once the player is
 * released, or while the SDK reports itself uninitialized
 */
internal class EventSink {
    /** Null when another player is already tracked; dispatches then go nowhere */
    var sdk: FastPixDataSDK? = null
    var isReleased = false

    val canDispatch: Boolean get() = !isReleased && sdk?.isInitialized() != false

    fun dispatch(type: PlayerEventType, position: Int? = null) {
        sdk?.dispatchEvent(type, position)
    }
}
