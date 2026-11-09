package io.fastpix.exoplayer_data_sdk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.ui.StyledPlayerView
import io.fastpix.data.FastPixAnalytics
import io.fastpix.data.domain.SDKConfiguration
import io.fastpix.data.domain.listeners.PlayerListener
import io.fastpix.data.domain.model.AdDataDetails
import io.fastpix.data.domain.model.AdModel
import io.fastpix.data.domain.model.ApplicationDataDetails
import io.fastpix.data.domain.model.BandwidthModel
import io.fastpix.data.domain.model.CustomDataDetails
import io.fastpix.data.domain.model.ErrorModel
import io.fastpix.data.domain.model.PlayerDataDetails
import io.fastpix.data.domain.model.VideoDataDetails
import io.fastpix.data.domain.model.ViewerDataDetails
import io.fastpix.data.utils.Logger
import io.fastpix.exoplayer_data_sdk.ads.AdTracker
import io.fastpix.exoplayer_data_sdk.events.EventSink
import io.fastpix.exoplayer_data_sdk.events.PlaybackEventDispatcher
import io.fastpix.exoplayer_data_sdk.events.PulseScheduler
import io.fastpix.exoplayer_data_sdk.events.TAG
import io.fastpix.exoplayer_data_sdk.listeners.AnalyticsEventRouter
import io.fastpix.exoplayer_data_sdk.listeners.PlayerStateRouter
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import io.fastpix.exoplayer_data_sdk.network.BandwidthMetricDispatcher
import io.fastpix.exoplayer_data_sdk.network.BandwidthMetricHls
import io.fastpix.exoplayer_data_sdk.sdkInfo.FastPixExoplayerLibraryInfo
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import com.google.ads.interactivemedia.v3.api.AdEvent as ImaAdEvent

/**
 * FastPix ExoPlayer wrapper that automatically integrates with FastPixDataSDK
 * This wrapper provides a seamless integration between ExoPlayer and FastPix analytics
 */
class FastPixBaseExoPlayer(
    val context: Context,
    val playerView: View,
    private val exoPlayer: ExoPlayer,
    private val workSpaceId: String,
    private val beaconUrl: String? = null,
    private val enableLogging: Boolean = false,
    private val playerDataDetails: PlayerDataDetails? = null,
    private val videoDataDetails: VideoDataDetails? = null,
    private val customDataDetails: CustomDataDetails? = null,
    private val applicationDataDetails: ApplicationDataDetails? = null,
    private val viewerDataDetails: ViewerDataDetails? = null,
    /** The view's ad details from the app's ad server or VAST, sent on viewBegin and viewCompleted */
    private val adDataDetails: AdDataDetails? = null,
    /**
     * Classifies a player error for the app: true for a business rule (geo-block, entitlement),
     * false for a technical fault, null when the app can't tell. Null classifier = unclassified
     */
    private val errorClassifier: ((PlaybackException) -> Boolean?)? = null,
    /**
     * Whether the app's player controls are enabled. Needed when the app draws its own controls
     * (PlayerView use_controller = false); without it a custom UI reports nothing rather than false
     */
    private val controlsEnabled: (() -> Boolean?)? = null,
    /** Whether the app starts playback on its own (playWhenReady at load); null when unknown */
    private val autoPlay: Boolean? = null,
) : PlayerListener {

    /**
     * The constructor as published in 1.1.4, kept so apps compiled against it still link. Hidden,
     * so new code resolves to the constructor above
     */
    @Deprecated("Kept for binary compatibility with 1.1.4", level = DeprecationLevel.HIDDEN)
    constructor(
        context: Context,
        playerView: View,
        exoPlayer: ExoPlayer,
        workSpaceId: String,
        beaconUrl: String? = null,
        enableLogging: Boolean = false,
        playerDataDetails: PlayerDataDetails? = null,
        videoDataDetails: VideoDataDetails? = null,
        customDataDetails: CustomDataDetails? = null,
    ) : this(
        context, playerView, exoPlayer, workSpaceId, beaconUrl, enableLogging,
        playerDataDetails, videoDataDetails, customDataDetails, applicationDataDetails = null,
    )

    private val playerInstanceId: String = UUID.randomUUID().toString()
    private val videoIdForLogs: String? get() = videoDataDetails?.videoId

    private val sink = EventSink()
    private val releaseStarted = AtomicBoolean(false)
    private val pulse = PulseScheduler(sink)
    private val playback = PlaybackEventDispatcher(sink, pulse, exoPlayer, enableLogging, errorClassifier)
    private val media = ContentMediaTracker(exoPlayer, videoDataDetails)
    private val ads = AdTracker(exoPlayer, sink, onContentResumed = playback::onContentResumed)
    private val bandwidth = BandwidthMetricDispatcher(sink, BandwidthMetricHls(exoPlayer, media))

    init {
        Logger.log(
            TAG,
            "SESSION_CREATED: videoId=${videoIdForLogs ?: "none"} playerInstanceId=$playerInstanceId"
        )
        exoPlayer.addAnalyticsListener(AnalyticsEventRouter(media, playback, bandwidth, ads))
        exoPlayer.addListener(PlayerStateRouter(exoPlayer, media, playback, ads, bandwidth))
        val sdkConfiguration = SDKConfiguration(
            workspaceId = workSpaceId,
            beaconUrl = beaconUrl,
            playerData = playerDataDetails ?: PlayerDataDetails(
                "Exoplayer",
                "2.19.1"
            ),
            videoData = videoDataDetails,
            playerListener = this,
            enableLogging = enableLogging,
            customData = customDataDetails,
            applicationData = applicationDataDetails,
            viewerData = viewerDataDetails,
            adData = adDataDetails,
        )
        FastPixAnalytics.initialize(sdkConfiguration, context)
        // The SDK tracks one player at a time: if another player is already tracked, this one stays
        // untracked, sends nothing, and never releases the other player's view
        sink.sdk = FastPixAnalytics.getSDK()?.takeIf { it.isTracking(this) }
        if (sink.sdk == null) {
            Logger.logWarning(TAG, "Another player is already tracked; this player is not tracked")
        } else {
            playback.viewBegin()
            playback.playerReady()
            // With autoplay off, play waits for the viewer to press play (onIsPlayingChanged)
            if (exoPlayer.playWhenReady) playback.play()
        }
    }

    // Ads

    /** Call when the player requests ads (an IMA ad tag or VMAP) for the current media item */
    fun onAdsRequested(tagUrl: String) = ads.onAdsRequested(tagUrl)

    /**
     * Forward from ImaAdsLoader.Builder.setAdEventListener. IMA contributes the VAST metadata and
     * its own impression; breaks and ads themselves are read from the player
     */
    fun onImaAdEvent(event: ImaAdEvent) = ads.onImaAdEvent(event)

    /** Forward from ImaAdsLoader.Builder.setAdErrorListener */
    fun onImaAdError(event: AdErrorEvent) = ads.onImaAdError(event)

    fun release() {
        // release() is final. Core releases whichever player it tracks at the time, so a second
        // call must not reach it: it would end the view of a player tracked since. Atomic, so two
        // calls at the same moment release at most once
        if (!releaseStarted.compareAndSet(false, true)) return
        Logger.log(
            TAG,
            "onPlayerRelease(): begin playerInstanceId=$playerInstanceId videoId=${videoIdForLogs ?: "none"}"
        )
        sink.isReleased = true
        pulse.cancel()
        if (sink.sdk != null) {
            FastPixAnalytics.release()
        }
        media.reset()
        playback.reset()
        bandwidth.reset()
        Logger.log(TAG, "onPlayerRelease(): complete")
    }

    // PlayerListener: what the SDK reads from the player when it builds a beacon

    override fun playerHeight(): Int? = ceil(playerView.height / context.resources.displayMetrics.density).toInt()

    override fun playerWidth(): Int? = ceil(playerView.width / context.resources.displayMetrics.density).toInt()

    override fun videoSourceWidth(): Int? = media.videoSourceWidth

    override fun videoSourceHeight(): Int? = media.videoSourceHeight

    /**
     * The content playhead. During an ad, currentPosition is the position inside the ad, which
     * content metrics (playback time, seeks, scaling) would read as content jumping around;
     * contentPosition holds the content position where the break interrupts it
     */
    override fun playHeadTime(): Int? = exoPlayer.contentPosition.toInt()

    override fun mimeType(): String? = media.mimeType()

    override fun sourceFps(): Int? = media.frameRate

    override fun sourceAdvertisedBitrate(): String? = media.bitRate

    override fun sourceAdvertiseFrameRate(): Int? = media.frameRate

    override fun declaredFrameRate(): Double? = media.declaredFrameRate

    /** Null until the source has a known duration (C.TIME_UNSET would narrow to 1) */
    override fun sourceDuration(): Int? =
        exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.toInt()

    override fun isPause(): Boolean? = !exoPlayer.isPlaying

    override fun isAutoPlay(): Boolean? = autoPlay

    override fun preLoad(): Boolean? = false

    override fun isBuffering(): Boolean? = exoPlayer.playbackState == Player.STATE_BUFFERING

    override fun playerCodec(): String? = media.playerCodec

    override fun sourceHostName(): String? =
        exoPlayer.currentMediaItem?.localConfiguration?.uri.toString()

    override fun getPlayerError(): ErrorModel = playback.playerError()

    override fun getVideoCodec(): String? = media.codec

    override fun getSoftwareName(): String? = FastPixExoplayerLibraryInfo.SDK_NAME

    override fun getSoftwareVersion(): String? = FastPixExoplayerLibraryInfo.SDK_VERSION

    override fun isLive(): Boolean? = exoPlayer.isCurrentMediaItemLive

    override fun sourceUrl(): String? = media.sourceUrl()

    /**
     * Fullscreen means the player fills the window, whatever the orientation: a landscape phone
     * with a small player is not fullscreen. Reading the orientation instead lagged a pulse behind
     * entering and leaving fullscreen. Null before the player is laid out
     */
    override fun isFullScreen(): Boolean? {
        val root = playerView.rootView ?: return null
        if (playerView.width <= 0 || root.width <= 0) return null
        return playerView.width >= root.width * 0.95 && playerView.height >= root.height * 0.9
    }

    // ExoPlayer has no mute flag of its own: Player.mute() sets the volume to 0, so that is its mute state
    override fun isMuted(): Boolean? = exoPlayer.volume == 0f

    override fun volumeLevel(): Double? = exoPlayer.volume.toDouble()

    override fun playbackRate(): Double? = exoPlayer.playbackParameters.speed.toDouble()

    override fun isLooping(): Boolean? = exoPlayer.repeatMode != Player.REPEAT_MODE_OFF

    // useController = false can mean "custom controls", so only true is trusted from the player view
    override fun areControlsEnabled(): Boolean? =
        controlsEnabled?.invoke() ?: viewUsesController()?.takeIf { it }

    /** ExoPlayer 2.19.1 has two player views: StyledPlayerView and the older PlayerView */
    @Suppress("DEPRECATION")
    private fun viewUsesController(): Boolean? = when (playerView) {
        is StyledPlayerView -> playerView.useController
        is PlayerView -> playerView.useController
        else -> null
    }

    override fun isPictureInPicture(): Boolean? = context.findActivity()?.isInPictureInPictureMode

    override fun isSubtitlesOn(): Boolean? = media.viewerSubtitleFormat() != null

    override fun subtitlesLanguageCode(): String? = media.viewerSubtitleFormat()?.language

    override fun audioLanguageCode(): String? = media.contentFormat(C.TRACK_TYPE_AUDIO)?.language

    override fun bufferLength(): Long? =
        (exoPlayer.bufferedPosition - exoPlayer.currentPosition).coerceAtLeast(0L)

    override fun programDateTime(): Long? = media.programDateTime()

    override fun audioCodec(): String? = media.contentFormat(C.TRACK_TYPE_AUDIO)?.codecs

    override fun audioBitrate(): Int? = media.audioBitrate()

    override fun dynamicRangeType(): String? = media.dynamicRangeType()

    override fun renditionName(): String? = media.renditionName()

    override fun currentAd(): AdModel? = ads.currentAd()

    override fun getBandWidthData(): BandwidthModel = bandwidth.bandwidthModel()
    /** Public before 1.1.5, kept so apps that referenced it still compile and link */
    @Deprecated("Not used by the SDK; will be removed in the next major version")
    data class ChunkRendition(
        var bitrate: Long = 0,
        var width: Int = 0,
        var height: Int = 0,
        var codec: String? = null,
        var fps: Int = 0
    )

    /** Public before 1.1.5, kept so apps that referenced it still compile and link */
    @Deprecated("Not used by the SDK; will be removed in the next major version")
    @Suppress("DEPRECATION")
    data class ChunkDownloadData(
        var loadTaskId: Long = 0,
        var requestResponseStart: Long = 0,
        var requestResponseEnd: Long = 0,
        var requestMediaStartTime: Long = 0,
        var requestMediaDuration: Long = 0,
        var requestVideoWidth: Int? = null,
        var requestVideoHeight: Int? = null,
        var requestUrl: String? = null,
        var requestType: String? = null,
        var requestHostName: String? = null,
        var requestBytesLoaded: Long = 0,
        var requestCurrentLevel: Int = 0,
        var requestLabeledBitrate: Int = 0,
        var requestError: String? = null,
        var requestErrorCode: Int = 0,
        var requestErrorText: String? = null,
        var requestCancel: String? = null,
        var requestId: String? = null,
        var requestResponseHeaders: Map<String, String>? = null,
        var requestRenditionLists: List<ChunkRendition>? = null
    )
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
