package io.fastpix.exoplayer_data_sdk.media

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.video.VideoSize
import io.fastpix.data.domain.model.VideoDataDetails
import io.fastpix.data.utils.RenditionName

/**
 * What the content itself is: its formats, rendition, codecs, size and source. Ad media is kept
 * out, so these fields never describe an ad playing at the time
 */
internal class ContentMediaTracker(
    private val exoPlayer: ExoPlayer,
    private val videoDataDetails: VideoDataDetails?,
) {
    /** Name of the video decoder in use */
    var playerCodec: String? = null
    var codec: String? = null
        private set
    var bitRate: String? = null
        private set
    var frameRate: Int? = null
        private set
    var declaredFrameRate: Double? = null
        private set
    var videoSourceWidth: Int? = null
        private set
    var videoSourceHeight: Int? = null
        private set

    private var containerMimeType: String? = null
    private var videoSourceUrl: String? = null

    /** The content's own formats, kept so rendition fields never describe an ad playing at the time */
    private var contentVideoFormat: Format? = null
    private var contentAudioFormat: Format? = null

    /** Bitrate of the last audio-only segment, for audio tracks that declare none */
    private var measuredAudioBitrate: Int? = null

    private val timelineWindow = Timeline.Window()

    /**
     * Records a content video format. True for the first rendition and every switch after it, as
     * Web reports them, so the rendition fields (name, audio codec and bitrate, dynamic range) are
     * sent even when playback never switches. The SDK reports no shift for the first one
     */
    fun onContentVideoFormat(format: Format): Boolean {
        contentVideoFormat = format
        val previousBitrate = bitRate?.toIntOrNull()
        val newBitrate = format.bitrate.takeIf { it > 0 }
        if (newBitrate != null) bitRate = newBitrate.toString()
        codec = format.codecs ?: codec
        frameRate = format.frameRate.takeIf { it > 0 }?.toInt()
        declaredFrameRate = format.frameRate.takeIf { it > 0 }?.toDouble()
        videoSourceWidth = format.width
        videoSourceHeight = format.height
        return newBitrate != null && newBitrate != previousBitrate
    }

    fun onContentAudioFormat(format: Format) {
        contentAudioFormat = format
    }

    fun onMediaItemTransition(mediaItem: MediaItem?) {
        measuredAudioBitrate = null
        contentVideoFormat = null
        contentAudioFormat = null
        if (videoSourceUrl != null) {
            mediaItem?.localConfiguration?.uri?.let { videoSourceUrl = it.toString() }
        }
    }

    fun onTimelineChanged() {
        if (videoSourceUrl != null) {
            exoPlayer.currentMediaItem?.localConfiguration?.uri?.let { videoSourceUrl = it.toString() }
        }
    }

    fun onSegmentLoadStarted(uri: Uri) {
        if (videoSourceUrl != null) videoSourceUrl = uri.toString()
    }

    fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.height > 0 && videoSize.width > 0) {
            videoSourceWidth = videoSize.width
            videoSourceHeight = videoSize.height
        }
    }

    fun onReady() {
        videoSourceHeight = exoPlayer.videoSize.height
        videoSourceWidth = exoPlayer.videoSize.width
    }

    fun onTracksChanged(trackGroups: TrackGroupArray) {
        if (trackGroups.length > 0) {
            containerMimeType = trackGroups.get(0).getFormat(0).containerMimeType
        }
    }

    fun onTracksUnreadable() {
        containerMimeType = null
    }

    /** A content segment finished loading; ad segments, loaded ahead of the break too, never count */
    fun onContentSegmentLoaded(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        measureAudioBitrate(loadEventInfo, mediaLoadData)
        captureCdnEdgePop(loadEventInfo, mediaLoadData)
    }

    fun mimeType(): String? =
        containerMimeType ?: exoPlayer.currentMediaItem?.localConfiguration?.mimeType

    fun sourceUrl(): String? =
        videoSourceUrl ?: exoPlayer.currentMediaItem?.localConfiguration?.uri.toString()

    /**
     * Wall-clock time of the content frame on screen: the window's program-date-time start plus
     * the content position. None during an ad, whose frames are not the program's, and none when
     * the stream carries no program date time (C.TIME_UNSET, e.g. a static DASH manifest)
     */
    fun programDateTime(): Long? {
        if (exoPlayer.isPlayingAd) return null
        val timeline = exoPlayer.currentTimeline
        if (timeline.isEmpty) return null
        val windowStartMs =
            timeline.getWindow(exoPlayer.currentMediaItemIndex, timelineWindow).windowStartTimeMs
        return if (windowStartMs == C.TIME_UNSET) null else windowStartMs + exoPlayer.contentPosition
    }

    /**
     * The declared bitrate of the selected audio track, else the bitrate measured from its
     * segments. HLS declares no bitrate for an audio rendition (EXT-X-MEDIA has no BANDWIDTH),
     * so for HLS with separate audio it is always measured, as the Web SDK does
     */
    fun audioBitrate(): Int? {
        val format = contentFormat(C.TRACK_TYPE_AUDIO)
        return format?.averageBitrate?.takeIf { it > 0 }
            ?: format?.bitrate?.takeIf { it > 0 }
            ?: measuredAudioBitrate
    }

    /** The transfer function as HLS names it (VIDEO-RANGE), as Web sends it: SDR, PQ or HLG */
    fun dynamicRangeType(): String? {
        val format = contentFormat(C.TRACK_TYPE_VIDEO) ?: return null
        return when {
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "PQ"
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            // Dolby Vision is PQ-based, and profile 5 declares no transfer in its colour info;
            // playlists mark it VIDEO-RANGE=PQ, which is what Web reports
            isDolbyVision(format) -> "PQ"
            else -> "SDR"
        }
    }

    /**
     * The rendition's label from the manifest when it has one (DASH Label, or an HLS NAME), else a
     * name built from the rendition as Web does: "1080p FHD", "2160p 4K PQ". FastPix playlists
     * name no rendition, so they always get the built name
     */
    fun renditionName(): String? {
        val format = contentFormat(C.TRACK_TYPE_VIDEO) ?: return null
        return format.label?.takeIf { it.isNotBlank() }
            ?: RenditionName.from(format.width, format.height, dynamicRangeType())
    }

    /**
     * The content's format of [trackType]: the last one its decoder received, which carries what the
     * stream itself says (colour info, so PQ/HLG) on top of the playlist's. The selected track's
     * playlist format is the fallback, never during an ad, when the selected tracks are the ad's
     */
    fun contentFormat(trackType: Int): Format? {
        val stored = if (trackType == C.TRACK_TYPE_VIDEO) contentVideoFormat else contentAudioFormat
        if (stored != null || exoPlayer.isPlayingAd) return stored
        return selectedTrackFormat(trackType)
    }

    /** The selected text track, unless it is forced-only: forced subtitles are not a viewer choice */
    fun viewerSubtitleFormat(): Format? =
        selectedTrackFormat(C.TRACK_TYPE_TEXT)
            ?.takeIf { it.selectionFlags and C.SELECTION_FLAG_FORCED == 0 }

    fun reset() {
        containerMimeType = null
        frameRate = null
        declaredFrameRate = null
        playerCodec = null
        videoSourceHeight = null
        videoSourceWidth = null
        codec = null
        bitRate = null
    }

    /** Format of the selected track of [trackType], or null when none is selected */
    private fun selectedTrackFormat(trackType: Int): Format? {
        val group = exoPlayer.currentTracks.groups
            .firstOrNull { it.type == trackType && it.isSelected } ?: return null
        return (0 until group.length).firstOrNull { group.isTrackSelected(it) }
            ?.let { group.getTrackFormat(it) }
    }

    /**
     * Audio-only media segments: bits loaded over the media time they cover. Muxed audio and
     * video segments are skipped, since their bytes are not the audio's
     */
    private fun measureAudioBitrate(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA || mediaLoadData.trackType != C.TRACK_TYPE_AUDIO) return
        val durationMs = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
        if (mediaLoadData.mediaStartTimeMs == C.TIME_UNSET || durationMs <= 0 || loadEventInfo.bytesLoaded <= 0) return
        measuredAudioBitrate = (loadEventInfo.bytesLoaded * 8 * 1000 / durationMs).toInt()
    }

    /**
     * The CDN edge that served the content, from the media segment's response headers: the PoP
     * code in Cloudflare's cf-ray, CloudFront's x-amz-cf-pop, or the last entry of x-served-by
     * (FastPix, Fastly). Reported through videoData, so it rides viewCompleted once known
     */
    private fun captureCdnEdgePop(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
        val video = videoDataDetails ?: return
        edgePopFromHeaders(loadEventInfo.responseHeaders)?.let { video.videoCdnEdgePop = it }
    }

    private companion object {
        val DOLBY_VISION_CODECS = setOf("dvh1", "dvhe", "dav1", "dva1")

        fun isDolbyVision(format: Format): Boolean =
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
                format.codecs?.split(',')?.any { it.trim().substringBefore('.') in DOLBY_VISION_CODECS } == true

        fun edgePopFromHeaders(headers: Map<String, List<String>>): String? {
            fun header(name: String): String? = headers.entries
                .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()?.takeIf { it.isNotBlank() }

            val pop = header("cf-ray")?.substringAfterLast('-')
                ?: header("x-amz-cf-pop")?.substringBefore('-')
                ?: header("x-served-by")?.split(',')?.last()?.trim()?.substringAfterLast('-')
            return pop?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        }
    }
}
