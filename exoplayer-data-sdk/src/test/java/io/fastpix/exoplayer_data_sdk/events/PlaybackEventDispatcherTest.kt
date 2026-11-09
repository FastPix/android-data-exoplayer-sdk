package io.fastpix.exoplayer_data_sdk.events

import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.domain.model.ErrorSeverity
import io.fastpix.exoplayer_data_sdk.Dispatched
import io.fastpix.exoplayer_data_sdk.PlayerEvent
import io.fastpix.exoplayer_data_sdk.RecordingSdk
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEventDispatcherTest {
    private val recording = RecordingSdk()
    private val pulse = mockk<PulseScheduler>(relaxed = true)
    private val exoPlayer = mockk<ExoPlayer>(relaxed = true) {
        every { isPlaying } returns false
        every { isPlayingAd } returns false
    }

    private fun dispatcher(
        classifier: ((PlaybackException) -> Boolean?)? = null,
    ) = PlaybackEventDispatcher(recording.sink, pulse, exoPlayer, enableLogging = false, errorClassifier = classifier)

    @Test
    fun `view begin is sent and starts the pulse`() {
        dispatcher().viewBegin()
        assertEquals(listOf(PlayerEventType.viewBegin), recording.types)
        verify { pulse.schedule() }
    }

    @Test
    fun `player ready is sent without changing state`() {
        val dispatcher = dispatcher()
        dispatcher.playerReady()
        assertEquals(listOf(PlayerEventType.playerReady), recording.types)
        assertNull(dispatcher.currentEventState)
    }

    @Test
    fun `play starts a view and becomes the state`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        assertEquals(listOf(PlayerEventType.play), recording.types)
        assertEquals(PlayerEvent.PLAY, dispatcher.currentEventState)
        verify { pulse.schedule() }
    }

    @Test
    fun `pause before play is refused`() {
        val dispatcher = dispatcher()
        dispatcher.pause()
        assertTrue(recording.events.isEmpty())
        assertNull(dispatcher.currentEventState)
    }

    @Test
    fun `play is not sent twice in a row`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.play()
        assertEquals(listOf(PlayerEventType.play), recording.types)
    }

    @Test
    fun `pause carries its position and stops the pulse`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.pause(1_234)
        assertEquals(Dispatched(PlayerEventType.pause, 1_234), recording.events.last())
        verify { pulse.cancel() }
    }

    @Test
    fun `ended carries its position and stops the pulse`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.playing()
        dispatcher.ended(60_000)
        assertEquals(Dispatched(PlayerEventType.ended, 60_000), recording.events.last())
        assertEquals(PlayerEvent.ENDED, dispatcher.currentEventState)
        verify { pulse.cancel() }
    }

    @Test
    fun `no pause is sent after the end`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.playing()
        dispatcher.ended(60_000)
        dispatcher.pause()
        assertEquals(PlayerEventType.ended, recording.types.last())
        assertEquals(PlayerEvent.ENDED, dispatcher.currentEventState)
    }

    @Test
    fun `a full play, buffer and pause cycle is sent in order`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.playing()
        dispatcher.buffering()
        dispatcher.buffered()
        dispatcher.playing()
        dispatcher.pause()
        assertEquals(
            listOf(
                PlayerEventType.play,
                PlayerEventType.playing,
                PlayerEventType.buffering,
                PlayerEventType.buffered,
                PlayerEventType.playing,
                PlayerEventType.pause,
            ),
            recording.types,
        )
    }

    @Test
    fun `seeking carries where the seek started`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.pause()
        dispatcher.seeking(42_000)
        assertEquals(Dispatched(PlayerEventType.seeking, 42_000), recording.events.last())
    }

    @Test
    fun `seeked while paused stops the pulse`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.pause()
        dispatcher.seeking(0)
        every { exoPlayer.isPlaying } returns false
        dispatcher.seeked()
        assertEquals(PlayerEventType.seeked, recording.types.last())
        verify(exactly = 2) { pulse.cancel() }
    }

    @Test
    fun `seeked while playing keeps the pulse running`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.playing()
        dispatcher.seeking(0)
        every { exoPlayer.isPlaying } returns true
        dispatcher.seeked()
        assertEquals(PlayerEventType.seeked, recording.types.last())
        verify(exactly = 0) { pulse.cancel() }
    }

    @Test
    fun `a rendition switch during playback is sent without becoming the state`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        dispatcher.contentVariantChanged()
        assertEquals(listOf(PlayerEventType.play, PlayerEventType.variantChanged), recording.types)
        assertEquals(PlayerEvent.PLAY, dispatcher.currentEventState)
    }

    @Test
    fun `a rendition switch before play is sent right after play`() {
        val dispatcher = dispatcher()
        dispatcher.contentVariantChanged()
        assertTrue(recording.events.isEmpty())
        dispatcher.play()
        assertEquals(listOf(PlayerEventType.play, PlayerEventType.variantChanged), recording.types)
    }

    @Test
    fun `a content rendition read during an ad waits for content to resume`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        every { exoPlayer.isPlayingAd } returns true
        dispatcher.contentVariantChanged()
        assertEquals(listOf(PlayerEventType.play), recording.types)

        every { exoPlayer.isPlayingAd } returns false
        dispatcher.onContentResumed()
        dispatcher.onContentResumed()
        assertEquals(listOf(PlayerEventType.play, PlayerEventType.variantChanged), recording.types)
    }

    @Test
    fun `a new media item drops the rendition waiting on an ad`() {
        val dispatcher = dispatcher()
        dispatcher.play()
        every { exoPlayer.isPlayingAd } returns true
        dispatcher.contentVariantChanged()
        dispatcher.onMediaItemChanged()
        dispatcher.onContentResumed()
        assertEquals(listOf(PlayerEventType.play), recording.types)
    }

    @Test
    fun `an error is reported as fatal with its context and the app's classification`() {
        val dispatcher = dispatcher(classifier = { true })
        val error = PlaybackException(
            "Source error",
            IOException("connection reset"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        dispatcher.play()
        dispatcher.error(error)

        assertEquals(PlayerEventType.error, recording.types.last())
        assertEquals(PlayerEvent.ERROR, dispatcher.currentEventState)
        verify { pulse.cancel() }

        val reported = dispatcher.playerError()
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED.toString(), reported.errorCode)
        assertEquals("java.io.IOException: connection reset", reported.errorMessage)
        assertEquals(ErrorSeverity.FATAL, reported.severity)
        assertEquals(true, reported.isBusinessException)
        assertEquals(
            "error code: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED\n" +
                "error message: Source error\n" +
                "cause: IOException: connection reset",
            reported.errorContext,
        )
    }

    @Test
    fun `an error can be the first event of a view`() {
        val dispatcher = dispatcher()
        dispatcher.error(PlaybackException("boom", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(listOf(PlayerEventType.error), recording.types)
        assertNull(dispatcher.playerError().isBusinessException)
    }

    @Test
    fun `no error is reported before one happens`() {
        val reported = dispatcher().playerError()
        assertNull(reported.errorCode)
        assertNull(reported.errorMessage)
        assertNull(reported.errorContext)
        assertNull(reported.severity)
    }

    @Test
    fun `reset forgets the state, the error and a pending rendition switch`() {
        val dispatcher = dispatcher()
        dispatcher.contentVariantChanged()
        dispatcher.error(PlaybackException("boom", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        dispatcher.reset()

        assertNull(dispatcher.currentEventState)
        assertNull(dispatcher.playerError().errorCode)
        recording.clear()
        dispatcher.play()
        assertEquals(listOf(PlayerEventType.play), recording.types)
    }

    @Test
    fun `nothing is sent or tracked once released`() {
        val dispatcher = dispatcher()
        recording.sink.isReleased = true
        dispatcher.viewBegin()
        dispatcher.play()
        assertTrue(recording.events.isEmpty())
        assertNull(dispatcher.currentEventState)
    }

    @Test
    fun `nothing is sent while the SDK is uninitialized`() {
        val uninitialized = RecordingSdk(initialized = false)
        val dispatcher = PlaybackEventDispatcher(uninitialized.sink, pulse, exoPlayer, false, null)
        dispatcher.play()
        assertTrue(uninitialized.events.isEmpty())
    }

    @Test
    fun `an untracked player still follows the state machine`() {
        val sink = EventSink()
        val dispatcher = PlaybackEventDispatcher(sink, pulse, exoPlayer, false, null)
        dispatcher.play()
        assertEquals(PlayerEvent.PLAY, dispatcher.currentEventState)
    }
}
