package io.fastpix.exoplayer_data_sdk.events

import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.HttpDataSource
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.domain.model.ErrorModel
import io.fastpix.data.domain.model.ErrorSeverity
import io.fastpix.data.utils.Logger
import io.fastpix.exoplayer_data_sdk.PlayerEvent
import io.fastpix.exoplayer_data_sdk.validTransitions

/**
 * Playback events (view, play, pause, seek, buffer, end, rendition, error), each sent only when
 * [validTransitions] allows it from the last one sent
 */
internal class PlaybackEventDispatcher(
    private val sink: EventSink,
    private val pulse: PulseScheduler,
    private val exoPlayer: ExoPlayer,
    private val enableLogging: Boolean,
    private val errorClassifier: ((PlaybackException) -> Boolean?)?,
) {
    /** The last playback event sent. variantChanged is not a state and never becomes it */
    var currentEventState: PlayerEvent? = null
        private set

    /** A variantChanged refused by the state machine, sent after the next play */
    private var variantChangePending = false

    /** Content's rendition arrived during an ad; its variantChanged waits for content to resume */
    private var variantChangeAfterAd = false

    private var errorCode: String? = null
    private var errorMessage: String? = null
    private var errorContext: String? = null
    private var errorIsBusinessException: Boolean? = null

    fun viewBegin() {
        if (!sink.canDispatch) return
        Logger.log(TAG, "PLAYER_EVENT: viewBegin")
        debug("Dispatching ViewBegin event")
        pulse.schedule()
        sink.dispatch(PlayerEventType.viewBegin)
    }

    fun playerReady() {
        if (!sink.canDispatch) return
        Logger.log(TAG, "PLAYER_EVENT: playerReady")
        debug("Dispatching Play Ready event")
        sink.dispatch(PlayerEventType.playerReady)
    }

    fun play() {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.PLAY)) return
        Logger.log(TAG, "PLAYER_EVENT: play")
        debug("Dispatching Play event")
        pulse.schedule()
        sink.dispatch(PlayerEventType.play)
        if (variantChangePending) {
            variantChangePending = false
            variantChanged()
        }
    }

    fun playing() {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.PLAYING)) return
        Logger.log(TAG, "PLAYER_EVENT: playing")
        debug("Dispatching Playing event")
        pulse.schedule()
        sink.dispatch(PlayerEventType.playing)
    }

    fun pause(position: Int? = null) {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.PAUSE)) return
        Logger.log(TAG, "PLAYER_EVENT: pause position=${position ?: "none"}")
        debug("Dispatching Pause event")
        pulse.cancel()
        sink.dispatch(PlayerEventType.pause, position)
    }

    /** [position] is where the seek started */
    fun seeking(position: Int? = null) {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.SEEKING)) return
        debug("Dispatching Seeking event")
        sink.dispatch(PlayerEventType.seeking, position)
    }

    fun seeked() {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.SEEKED)) return
        debug("Dispatching Seeked event")
        if (exoPlayer.isPlaying) pulse.schedule() else pulse.cancel()
        sink.dispatch(PlayerEventType.seeked)
    }

    fun buffering() {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.BUFFERING)) return
        Logger.log(TAG, "PLAYER_EVENT: buffering_start")
        debug("Dispatching Buffering event")
        pulse.schedule()
        sink.dispatch(PlayerEventType.buffering)
    }

    fun buffered() {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.BUFFERED)) return
        Logger.log(TAG, "PLAYER_EVENT: buffering_end")
        debug("Dispatching Buffered event")
        sink.dispatch(PlayerEventType.buffered)
    }

    fun ended(position: Int? = null) {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.ENDED)) return
        Logger.log(TAG, "PLAYER_EVENT: ended position=${position ?: "none"}")
        debug("Dispatching Ended event")
        pulse.cancel()
        sink.dispatch(PlayerEventType.ended, position)
    }

    fun error(error: PlaybackException) {
        if (!sink.canDispatch || !transitionTo(PlayerEvent.ERROR)) return
        debug("Dispatching Error event: ${error.message}")
        pulse.cancel()
        errorCode = error.errorCode.toString()
        errorMessage = "${error.cause}"
        errorContext = describeError(error)
        errorIsBusinessException = errorClassifier?.invoke(error)
        sink.dispatch(PlayerEventType.error)
    }

    /**
     * Content switched rendition. One read ahead while an ad still plays is sent once content is
     * on screen, so its audio fields describe the content too
     */
    fun contentVariantChanged() {
        if (exoPlayer.isPlayingAd) variantChangeAfterAd = true else variantChanged()
    }

    /** The last ad break ended and content plays again */
    fun onContentResumed() {
        if (variantChangeAfterAd) {
            variantChangeAfterAd = false
            variantChanged()
        }
    }

    fun onMediaItemChanged() {
        variantChangeAfterAd = false
    }

    // ExoPlayer's onPlayerError means playback stopped, so every player error is fatal
    fun playerError(): ErrorModel = ErrorModel(
        errorCode = errorCode,
        errorMessage = errorMessage,
        errorContext = errorContext,
        severity = if (errorCode != null) ErrorSeverity.FATAL else null,
        isBusinessException = errorIsBusinessException,
    )

    fun reset() {
        currentEventState = null
        variantChangePending = false
        errorCode = null
        errorMessage = null
        errorContext = null
        errorIsBusinessException = null
    }

    private fun variantChanged() {
        if (!sink.canDispatch) return
        if (transitionTo(PlayerEvent.VARIANT_CHANGED)) {
            debug("Dispatching VariantChange event")
            sink.dispatch(PlayerEventType.variantChanged)
        } else {
            variantChangePending = true
        }
    }

    private fun transitionTo(event: PlayerEvent): Boolean {
        if (event !in validTransitions[currentEventState].orEmpty()) return false
        if (event != PlayerEvent.VARIANT_CHANGED) currentEventState = event
        return true
    }

    private fun debug(message: String) {
        if (enableLogging) Log.d(TAG, message)
    }

    /** player_error_context: the ExoPlayer error name, its cause chain and the failing URL, one per line as on Web */
    private fun describeError(error: PlaybackException): String = buildList {
        add("error code: ${error.errorCodeName}")
        error.message?.let { add("error message: $it") }
        generateSequence(error.cause) { it.cause }.take(5).forEach { cause ->
            add("cause: ${cause.javaClass.simpleName}" + (cause.message?.let { ": $it" } ?: ""))
        }
        (error.cause as? HttpDataSource.HttpDataSourceException)?.let { add("url: ${it.dataSpec.uri}") }
    }.joinToString("\n")
}
