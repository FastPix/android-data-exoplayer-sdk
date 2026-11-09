package io.fastpix.exoplayer_data_sdk.network

/** One rendition of the content's video track */
internal data class ChunkRendition(
    var bitrate: Long = 0,
    var width: Int = 0,
    var height: Int = 0,
    var codec: String? = null,
    var fps: Int = 0
)

/** One segment, manifest or init request, from its start to its completion, cancellation or failure */
internal data class ChunkDownloadData(
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
