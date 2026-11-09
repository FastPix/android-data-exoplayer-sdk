package io.fastpix.exoplayer_data_sdk.network

import android.util.Log
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.source.TrackGroupArray
import io.fastpix.data.di.DependencyContainer
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.domain.model.BandwidthModel
import io.fastpix.data.utils.CmcdPayload
import io.fastpix.exoplayer_data_sdk.events.EventSink
import io.fastpix.exoplayer_data_sdk.events.TAG
import java.io.IOException

/**
 * Turns the player's loads into requestCompleted, requestCanceled and requestFailed events, at
 * most 10 of each per segment duration, and holds the request being reported for getBandWidthData
 */
internal class BandwidthMetricDispatcher(
    private val sink: EventSink,
    private val metric: BandwidthMetric,
) {
    private enum class Outcome { COMPLETED, CANCELLED, FAILED }

    /** The request whose event is being dispatched, or was last dispatched */
    private var lastRequest: ChunkDownloadData? = null

    /** CMCD of the request whose event is being dispatched; set only for the duration of that dispatch */
    private var cmcdPayload: String? = null

    private var requestSegmentDuration: Long = 1000
    private var lastRequestSentAt: Long = -1
    private val sentPerSegment = mutableMapOf<Outcome, Int>()

    fun onLoadStarted(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val segmentMimeType = mediaLoadData.trackFormat?.sampleMimeType ?: "unknown"
        try {
            metric.onLoadStarted(
                loadEventInfo.loadTaskId,
                mediaLoadData.mediaStartTimeMs,
                mediaLoadData.mediaEndTimeMs,
                loadEventInfo.requestPath(),
                mediaLoadData.dataType,
                loadEventInfo.uri.host,
                segmentMimeType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling load started: ${e.message}")
        }
    }

    fun onLoadCompleted(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
        reporting("completed", loadEventInfo) {
            val data = metric.onLoadCompleted(
                loadEventInfo.loadTaskId,
                loadEventInfo.bytesLoaded,
                mediaLoadData.trackFormat
            )
            if (data != null) {
                applyHeaders(data, loadEventInfo.responseHeaders)
                dispatch(Outcome.COMPLETED, data)
            }
        }

    fun onLoadCanceled(loadEventInfo: LoadEventInfo) =
        reporting("canceled", loadEventInfo) {
            val data = metric.onLoadCanceled(
                loadEventInfo.loadTaskId,
                loadEventInfo.requestPath(),
                loadEventInfo.uri.host
            )
            applyHeaders(data, loadEventInfo.responseHeaders)
            dispatch(Outcome.CANCELLED, data)
        }

    fun onLoadError(loadEventInfo: LoadEventInfo, error: IOException) =
        reporting("error", loadEventInfo) {
            dispatch(Outcome.FAILED, metric.onLoadError(loadEventInfo.loadTaskId, error))
        }

    /** Records the available tracks, and the renditions of the last video track among them */
    fun onTracksChanged(trackGroups: TrackGroupArray) {
        metric.availableTracks = trackGroups
        for (groupIndex in 0 until trackGroups.length) {
            val trackGroup = trackGroups[groupIndex]
            if (trackGroup.length == 0) continue
            if (trackGroup.getFormat(0).containerMimeType?.contains("video") != true) continue
            metric.renditionList = (0 until trackGroup.length).map { i ->
                val trackFormat = trackGroup.getFormat(i)
                ChunkRendition(
                    bitrate = trackFormat.bitrate.toLong(),
                    width = trackFormat.width,
                    height = trackFormat.height,
                    codec = trackFormat.codecs,
                    fps = trackFormat.frameRate.toInt()
                )
            }
        }
    }

    fun bandwidthModel(): BandwidthModel {
        val request = lastRequest
        val bandWidthModel = BandwidthModel()
        bandWidthModel.cmcdPayload = cmcdPayload
        bandWidthModel.requestId = request?.requestId
        // Only construct URL if both host and path are available
        val hostName = request?.requestHostName
        val requestUrl = request?.requestUrl
        bandWidthModel.requestUrl = if (hostName != null && requestUrl != null) {
            "https://${hostName}${requestUrl}"
        } else {
            null
        }
        bandWidthModel.requestMethod = request?.requestType
        bandWidthModel.requestResponseHeaders = request?.requestResponseHeaders
        bandWidthModel.requestResponseCode = request?.requestErrorCode.toString()
        bandWidthModel.requestResponseSize = request?.requestBytesLoaded.toString()
        bandWidthModel.requestErrorCode = request?.requestErrorCode.toString()
        bandWidthModel.requestHostName = request?.requestHostName
        bandWidthModel.requestCancel = request?.requestCancel
        bandWidthModel.requestErrorText = request?.requestErrorText
        return bandWidthModel
    }

    fun reset() {
        metric.renditionList = null
    }

    /** Runs [block] with the request's CMCD available to bandwidthModel(), logging any failure */
    private inline fun reporting(outcome: String, loadEventInfo: LoadEventInfo, block: () -> Unit) {
        try {
            cmcdPayload = CmcdPayload.from(loadEventInfo.dataSpec.uri, loadEventInfo.dataSpec.httpRequestHeaders)
                ?.let(::withCmcdVersion)
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling load $outcome: ${e.message}")
        } finally {
            cmcdPayload = null
        }
    }

    /**
     * The CMCD spec lets a client leave out the version key when it is 1, and ExoPlayer does;
     * the version is added back so the request still reports which CMCD version it used
     */
    private fun withCmcdVersion(payload: String): String {
        val hasVersion = payload.split(',').any { it.substringBefore('=').trim() == "v" }
        return if (hasVersion) payload else "$payload,v=1"
    }

    private fun dispatch(outcome: Outcome, data: ChunkDownloadData) {
        if (!withinRateLimit(outcome, data)) return
        if (!sink.canDispatch) return
        lastRequest = data
        when (outcome) {
            Outcome.COMPLETED -> sink.dispatch(PlayerEventType.requestCompleted)
            Outcome.FAILED -> sink.dispatch(PlayerEventType.requestFailed)
            Outcome.CANCELLED -> {
                // Captured before dispatch: the SDK may clear its player listener while releasing,
                // so the event builder reads this copy from SDKStateService instead
                val bandwidthData = bandwidthModel()
                try {
                    DependencyContainer.getSDKStateService().setLastRequestCancelledBandwidthData(bandwidthData)
                } catch (e: Exception) {
                    // SDK might be in release process, ignore
                }
                sink.dispatch(PlayerEventType.requestCanceled)
            }
        }
    }

    /** Counts the event against its segment-duration window; false once more than 10 of its kind were sent */
    private fun withinRateLimit(outcome: Outcome, data: ChunkDownloadData): Boolean {
        requestSegmentDuration = data.requestMediaDuration.coerceAtLeast(1000)
        if (System.currentTimeMillis() - lastRequestSentAt > requestSegmentDuration) {
            lastRequestSentAt = System.currentTimeMillis()
            sentPerSegment.clear()
        }
        sentPerSegment[outcome] = (sentPerSegment[outcome] ?: 0) + 1
        return sentPerSegment.values.none { it > MAX_EVENTS_PER_SEGMENT_DURATION }
    }

    private fun applyHeaders(data: ChunkDownloadData, responseHeaders: Map<String, List<String>>) {
        val headers = allowedHeaders(responseHeaders)
        data.requestId = headers["x-request-id"]
        data.requestResponseHeaders = headers
    }

    /** The allow-listed response headers, names and values lowercased, multiple values joined */
    private fun allowedHeaders(responseHeaders: Map<String, List<String>>): Map<String, String> {
        val headers = HashMap<String, String>()
        for ((headerName, headerValues) in responseHeaders) {
            if (ALLOWED_HEADERS.none { it.equals(headerName, ignoreCase = true) }) continue
            if (headerValues.isNotEmpty()) {
                headers[headerName.lowercase()] = headerValues.joinToString(", ").lowercase()
            }
        }
        return headers
    }

    private companion object {
        const val MAX_EVENTS_PER_SEGMENT_DURATION = 10
        val ALLOWED_HEADERS = listOf("x-cdn", "content-type", "server", "x-request-id")
    }
}

/** The request's URL without its scheme and host, reported beside requestHostName */
internal fun LoadEventInfo.requestPath(): String = uri.toString().removePrefix("https://${uri.host}")
