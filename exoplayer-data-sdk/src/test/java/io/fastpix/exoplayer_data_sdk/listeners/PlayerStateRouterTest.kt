package io.fastpix.exoplayer_data_sdk.listeners

import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.video.VideoSize
import io.fastpix.exoplayer_data_sdk.PlayerEvent
import io.fastpix.exoplayer_data_sdk.ads.AdTracker
import io.fastpix.exoplayer_data_sdk.events.PlaybackEventDispatcher
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import io.fastpix.exoplayer_data_sdk.network.BandwidthMetricDispatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test

class PlayerStateRouterTest {
    private val exoPlayer = mockk<ExoPlayer>(relaxed = true) {
        every { duration } returns 60_000L
    }
    private val media = mockk<ContentMediaTracker>(relaxed = true)
    private val playback = mockk<PlaybackEventDispatcher>(relaxed = true) {
        every { currentEventState } returns null
    }
    private val ads = mockk<AdTracker>(relaxed = true)
    private val bandwidth = mockk<BandwidthMetricDispatcher>(relaxed = true)
    private val router = PlayerStateRouter(exoPlayer, media, playback, ads, bandwidth)

    private fun position(ms: Long) = Player.PositionInfo(null, 0, MediaItem.EMPTY, null, 0, ms, ms, -1, -1)

    private fun seekFrom(ms: Long) =
        router.onPositionDiscontinuity(position(ms), position(0), Player.DISCONTINUITY_REASON_SEEK)

    // Buffering

    @Test
    fun `buffering is sent once, then buffered and playing when ready`() {
        router.onPlaybackStateChanged(Player.STATE_BUFFERING)
        router.onPlaybackStateChanged(Player.STATE_BUFFERING)
        router.onPlaybackStateChanged(Player.STATE_READY)

        verify(exactly = 1) { playback.buffering() }
        verifyOrder {
            playback.buffering()
            playback.buffered()
            playback.playing()
        }
    }

    @Test
    fun `ready without buffering sends neither buffered nor playing`() {
        router.onPlaybackStateChanged(Player.STATE_READY)
        verify(exactly = 0) { playback.buffered() }
        verify(exactly = 0) { playback.playing() }
        verify { media.onReady() }
    }

    @Test
    fun `playback resuming after buffering closes the buffering`() {
        router.onPlaybackStateChanged(Player.STATE_BUFFERING)
        router.onIsPlayingChanged(true)
        verifyOrder {
            playback.play()
            playback.buffered()
            playback.playing()
        }
    }

    // Seeking

    @Test
    fun `a seek sends seeking from where it started, and seeked once ready`() {
        seekFrom(42_000)
        router.onPlaybackStateChanged(Player.STATE_READY)
        router.onPlaybackStateChanged(Player.STATE_READY)

        verify { playback.seeking(42_000) }
        verify(exactly = 1) { playback.seeked() }
    }

    @Test
    fun `buffering during a seek is part of the seek`() {
        seekFrom(42_000)
        router.onPlaybackStateChanged(Player.STATE_BUFFERING)
        verify(exactly = 0) { playback.buffering() }
    }

    @Test
    fun `a second seek before the first settles is not another seeking`() {
        seekFrom(42_000)
        seekFrom(10_000)
        verify(exactly = 1) { playback.seeking(any()) }
    }

    @Test
    fun `other discontinuities are not seeks but still reach the ad tracker`() {
        router.onPositionDiscontinuity(position(5_000), position(0), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        verify(exactly = 0) { playback.seeking(any()) }
        verify { ads.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION) }
    }

    // Play, pause, end

    @Test
    fun `playing sends play then playing`() {
        router.onIsPlayingChanged(true)
        verifyOrder {
            playback.play()
            playback.playing()
        }
    }

    @Test
    fun `playing right after a seek does not send play again`() {
        every { playback.currentEventState } returns PlayerEvent.SEEKED
        router.onIsPlayingChanged(true)
        verify(exactly = 0) { playback.play() }
        verify { playback.playing() }
    }

    @Test
    fun `stopping playback is a pause`() {
        router.onIsPlayingChanged(false)
        verify { playback.pause() }
    }

    @Test
    fun `dropping play-when-ready is a pause`() {
        router.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        verify { playback.pause() }
    }

    @Test
    fun `setting play-when-ready is not a pause`() {
        router.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        verify(exactly = 0) { playback.pause(any()) }
    }

    @Test
    fun `idle is a pause`() {
        router.onPlaybackStateChanged(Player.STATE_IDLE)
        verify { playback.pause() }
    }

    @Test
    fun `the end is a pause then ended, both at the duration`() {
        router.onPlaybackStateChanged(Player.STATE_ENDED)
        verifyOrder {
            playback.pause(60_000)
            playback.ended(60_000)
        }
    }

    // Errors

    @Test
    fun `a player error is reported`() {
        val error = PlaybackException("boom", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        router.onPlayerError(error)
        verify { playback.error(error) }
    }

    @Test
    fun `an error right after an error is not reported again`() {
        every { playback.currentEventState } returns PlayerEvent.ERROR
        router.onPlayerError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        verify(exactly = 0) { playback.error(any()) }
    }

    // Media, tracks and ads

    @Test
    fun `a new media item resets content media and any rendition waiting on an ad`() {
        router.onMediaItemTransition(MediaItem.EMPTY, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        verify { media.onMediaItemTransition(MediaItem.EMPTY) }
        verify { playback.onMediaItemChanged() }
    }

    @Test
    fun `a timeline change updates ads before the source`() {
        router.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        verifyOrder {
            ads.onTimelineChanged()
            media.onTimelineChanged()
        }
    }

    @Test
    fun `video size changes reach the media tracker`() {
        router.onVideoSizeChanged(VideoSize(1280, 720))
        verify { media.onVideoSizeChanged(VideoSize(1280, 720)) }
    }

    @Test
    fun `new tracks reach the media tracker and request tracking`() {
        router.onTracksChanged(Tracks.EMPTY)
        verify { media.onTracksChanged(any()) }
        verify { bandwidth.onTracksChanged(any()) }
    }

    @Test
    fun `tracks that cannot be read clear the mime type`() {
        every { bandwidth.onTracksChanged(any()) } throws IllegalStateException("bad tracks")
        router.onTracksChanged(Tracks.EMPTY)
        verify { media.onTracksUnreadable() }
    }

    @Test
    fun `a first frame reaches the ad tracker`() {
        router.onRenderedFirstFrame()
        verify { ads.onRenderedFirstFrame() }
    }

    @Test
    fun `state callbacks bring the ad position up to date first`() {
        router.onPlaybackStateChanged(Player.STATE_IDLE)
        router.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        router.onIsPlayingChanged(false)
        router.onEvents(exoPlayer, Player.Events(com.google.android.exoplayer2.util.FlagSet.Builder().build()))

        verify(exactly = 4) { ads.sync() }
        verifyOrder {
            ads.sync()
            playback.pause()
        }
    }
}
