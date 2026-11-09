package io.fastpix.exoplayer_data_sdk.network

import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.source.TrackGroupArray
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import java.io.IOException

/** Follows each request from its start to its completion, cancellation or failure */
internal open class BandwidthMetric(
    private val exoPlayer: ExoPlayer,
    private val media: ContentMediaTracker,
) {
    var availableTracks: TrackGroupArray? = null
    var renditionList: List<ChunkRendition>? = null
    private val loadedSegments = HashMap<Long, ChunkDownloadData>()
    private val timelineWindow = Timeline.Window()

    fun onLoadStarted(
        loadTaskId: Long,
        mediaStartTimeMs: Long,
        mediaEndTimeMs: Long,
        segmentUrl: String?,
        dataType: Int,
        host: String?,
        segmentMimeType: String
    ): ChunkDownloadData {
        synchronized(timelineWindow) {
            try {
                @Suppress("DEPRECATION")
                exoPlayer.currentTimeline.getWindow(exoPlayer.currentWindowIndex, timelineWindow)
            } catch (e: IllegalStateException) {
                Log.e("ChunkDownloadData", "Player is in an invalid state: ${e.message}", e)
            }
        }

        val segmentData = ChunkDownloadData(
            loadTaskId = loadTaskId,
            requestResponseStart = System.currentTimeMillis(),
            requestMediaStartTime = mediaStartTimeMs,
            requestVideoWidth = media.videoSourceWidth,
            requestVideoHeight = media.videoSourceHeight,
            requestUrl = segmentUrl,
            requestHostName = host,
            requestRenditionLists = renditionList
        )

        when (dataType) {
            C.DATA_TYPE_MANIFEST -> segmentData.requestType = "manifest"
            C.DATA_TYPE_MEDIA_INITIALIZATION -> {
                if (segmentMimeType.contains("video")) {
                    segmentData.requestType = "video_init"
                } else if (segmentMimeType.contains("audio")) {
                    segmentData.requestType = "audio_init"
                }
            }
            C.DATA_TYPE_MEDIA -> {
                segmentData.requestType = "media"
                segmentData.requestMediaDuration = mediaEndTimeMs - mediaStartTimeMs
            }
            else -> Log.e("BandwidthMetricDefault", "default")
        }
        loadedSegments[loadTaskId] = segmentData
        return segmentData
    }

    open fun onLoadCompleted(loadTaskId: Long, bytesLoaded: Long, trackFormat: Format?): ChunkDownloadData? {
        val segmentData = loadedSegments[loadTaskId] ?: return null
        segmentData.requestBytesLoaded = bytesLoaded
        segmentData.requestResponseEnd = System.currentTimeMillis()

        val tracksList = availableTracks
        if (trackFormat != null && tracksList != null) {
            for (i in 0 until tracksList.length) {
                val tracks = tracksList[i]
                for (trackGroupIndex in 0 until tracks.length) {
                    val currentFormat = tracks.getFormat(trackGroupIndex)
                    if (trackFormat.width == currentFormat.width &&
                        trackFormat.height == currentFormat.height &&
                        trackFormat.bitrate == currentFormat.bitrate
                    ) {
                        segmentData.requestCurrentLevel = trackGroupIndex
                    }
                }
            }
        }
        loadedSegments.remove(loadTaskId)
        return segmentData
    }

    open fun onLoadCanceled(loadTaskId: Long, segmentUrl: String?, host: String?): ChunkDownloadData {
        val segmentData = loadedSegments[loadTaskId] ?: ChunkDownloadData(loadTaskId = loadTaskId)
        if (segmentData.requestUrl == null && segmentUrl != null) {
            segmentData.requestUrl = segmentUrl
        }
        if (segmentData.requestHostName == null && host != null) {
            segmentData.requestHostName = host
        }
        segmentData.requestCancel = "genericLoadCanceled"
        segmentData.requestResponseEnd = System.currentTimeMillis()
        return segmentData
    }

    fun onLoadError(loadTaskId: Long, e: IOException): ChunkDownloadData {
        val segmentData = loadedSegments[loadTaskId] ?: ChunkDownloadData(loadTaskId = loadTaskId)
        segmentData.requestError = e.toString()
        segmentData.requestErrorCode = -1
        segmentData.requestErrorText = e.message
        segmentData.requestResponseEnd = System.currentTimeMillis()
        return segmentData
    }
}

/** HLS names: a cancel is an emergency abort, and a completed segment carries its labelled bitrate */
internal class BandwidthMetricHls(
    exoPlayer: ExoPlayer,
    media: ContentMediaTracker,
) : BandwidthMetric(exoPlayer, media) {

    override fun onLoadCanceled(loadTaskId: Long, segmentUrl: String?, host: String?): ChunkDownloadData =
        super.onLoadCanceled(loadTaskId, segmentUrl, host).apply {
            requestCancel = "FragLoadEmergencyAborted"
        }

    override fun onLoadCompleted(loadTaskId: Long, bytesLoaded: Long, trackFormat: Format?): ChunkDownloadData? {
        val loadData = super.onLoadCompleted(loadTaskId, bytesLoaded, trackFormat)
        if (trackFormat != null && loadData != null) {
            loadData.requestLabeledBitrate = trackFormat.bitrate
        }
        return loadData
    }
}
