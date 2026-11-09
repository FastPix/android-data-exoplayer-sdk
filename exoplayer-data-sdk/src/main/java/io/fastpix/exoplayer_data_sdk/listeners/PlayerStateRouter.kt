package io.fastpix.exoplayer_data_sdk.listeners

import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.video.VideoSize
import io.fastpix.exoplayer_data_sdk.PlayerEvent
import io.fastpix.exoplayer_data_sdk.ads.AdTracker
import io.fastpix.exoplayer_data_sdk.events.PlaybackEventDispatcher
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import io.fastpix.exoplayer_data_sdk.network.BandwidthMetricDispatcher

/**
 * Turns ExoPlayer's state callbacks into playback events. Every callback brings the ad position up
 * to date first, so play, pause and buffering inside an ad break are marked as ad time
 */
internal class PlayerStateRouter(
    private val exoPlayer: ExoPlayer,
    private val media: ContentMediaTracker,
    private val playback: PlaybackEventDispatcher,
    private val ads: AdTracker,
    private val bandwidth: BandwidthMetricDispatcher,
) : Player.Listener {
    private var isBuffering = false
    private var isSeeking = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        media.onMediaItemTransition(mediaItem)
        playback.onMediaItemChanged()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        ads.onTimelineChanged()
        media.onTimelineChanged()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        media.onVideoSizeChanged(videoSize)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        ads.sync()
        when (playbackState) {
            Player.STATE_READY -> {
                media.onReady()
                if (isBuffering) {
                    isBuffering = false
                    playback.buffered()
                    playback.playing()
                }
                if (isSeeking) {
                    isSeeking = false
                    playback.seeked()
                }
            }

            Player.STATE_BUFFERING -> {
                if (!isSeeking && !isBuffering) {
                    isBuffering = true
                    playback.buffering()
                }
            }

            Player.STATE_IDLE -> playback.pause()

            Player.STATE_ENDED -> {
                playback.pause(exoPlayer.duration.toInt())
                playback.ended(exoPlayer.duration.toInt())
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        ads.sync()
        if (!playWhenReady) playback.pause()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        ads.onPositionDiscontinuity(reason)
        if (!isSeeking && reason == Player.DISCONTINUITY_REASON_SEEK) {
            isSeeking = true
            // A seek is not a pause: dispatch seeking only, so paused time stays accurate
            playback.seeking(oldPosition.positionMs.toInt())
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Play, pause and buffering inside an ad break are sent as usual; the SDK marks
        // them as ad time, and a playing inside the break is the ad's impression
        ads.sync()
        if (isPlaying) {
            if (playback.currentEventState != PlayerEvent.SEEKED) playback.play()
            if (isSeeking) {
                isSeeking = true
                playback.seeked()
            }
            if (isBuffering) {
                isBuffering = false
                playback.buffered()
            }
            playback.playing()
        } else {
            playback.pause()
        }
    }

    override fun onRenderedFirstFrame() {
        ads.onRenderedFirstFrame()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        ads.sync()
    }

    override fun onTracksChanged(tracks: Tracks) {
        try {
            val trackGroups = TrackGroupArray(*tracks.groups.map { it.mediaTrackGroup }.toTypedArray())
            media.onTracksChanged(trackGroups)
            bandwidth.onTracksChanged(trackGroups)
        } catch (ex: Exception) {
            media.onTracksUnreadable()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        // An error can happen from any state
        if (playback.currentEventState != PlayerEvent.ERROR) playback.error(error)
    }
}
