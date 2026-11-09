package io.fastpix.exoplayer_data_sdk.network

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.video.VideoSize
import io.fastpix.exoplayer_data_sdk.SinglePeriodTimeline
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandwidthMetricTest {
    private val exoPlayer = mockk<ExoPlayer>(relaxed = true) {
        every { currentTimeline } returns SinglePeriodTimeline()
        every { currentTracks } returns Tracks.EMPTY
    }
    private val media = ContentMediaTracker(exoPlayer, null)
    private val metric = BandwidthMetricHls(exoPlayer, media)

    private fun start(
        id: Long = 1,
        dataType: Int = C.DATA_TYPE_MEDIA,
        mimeType: String = MimeTypes.VIDEO_H264,
        startMs: Long = 10_000,
        endMs: Long = 14_000,
    ) = metric.onLoadStarted(id, startMs, endMs, "/video/seg.ts", dataType, "cdn.example.com", mimeType)

    private fun rendition(bitrate: Int, width: Int, height: Int) = Format.Builder()
        .setContainerMimeType(MimeTypes.VIDEO_MP4)
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setPeakBitrate(bitrate)
        .setWidth(width)
        .setHeight(height)
        .build()

    @Test
    fun `a media segment records its request and media time`() {
        val data = start(startMs = 10_000, endMs = 14_000)
        assertEquals("media", data.requestType)
        assertEquals(10_000L, data.requestMediaStartTime)
        assertEquals(4_000L, data.requestMediaDuration)
        assertEquals("/video/seg.ts", data.requestUrl)
        assertEquals("cdn.example.com", data.requestHostName)
    }

    @Test
    fun `a manifest request is typed manifest`() {
        assertEquals("manifest", start(dataType = C.DATA_TYPE_MANIFEST).requestType)
    }

    @Test
    fun `init segments are typed by their track`() {
        assertEquals("video_init", start(dataType = C.DATA_TYPE_MEDIA_INITIALIZATION, mimeType = MimeTypes.VIDEO_H264).requestType)
        assertEquals("audio_init", start(id = 2, dataType = C.DATA_TYPE_MEDIA_INITIALIZATION, mimeType = MimeTypes.AUDIO_AAC).requestType)
    }

    @Test
    fun `other requests have no type`() {
        assertNull(start(dataType = C.DATA_TYPE_DRM).requestType)
    }

    @Test
    fun `a request records the content's current size`() {
        media.onVideoSizeChanged(VideoSize(1280, 720))
        val data = start()
        assertEquals(1280, data.requestVideoWidth)
        assertEquals(720, data.requestVideoHeight)
    }

    @Test
    fun `a player in an invalid state does not stop the request being recorded`() {
        every { exoPlayer.currentTimeline } throws IllegalStateException("released")
        start(id = 7)
        assertEquals(500L, metric.onLoadCompleted(7, 500, null)?.requestBytesLoaded)
    }

    @Test
    fun `a completed request records its bytes once`() {
        start(id = 3)
        val data = metric.onLoadCompleted(3, 1_024, null)
        assertEquals(1_024L, data?.requestBytesLoaded)
        assertNull(metric.onLoadCompleted(3, 1_024, null))
    }

    @Test
    fun `a completion without a start is ignored`() {
        assertNull(metric.onLoadCompleted(99, 1_024, null))
    }

    @Test
    fun `a completed segment is matched to its rendition level and labelled bitrate`() {
        val low = rendition(800_000, 640, 360)
        val high = rendition(3_000_000, 1920, 1080)
        metric.availableTracks = TrackGroupArray(TrackGroup(low, high))
        start(id = 4)

        val data = metric.onLoadCompleted(4, 1_024, high)
        assertEquals(1, data?.requestCurrentLevel)
        assertEquals(3_000_000, data?.requestLabeledBitrate)
    }

    @Test
    fun `a cancelled request is an HLS emergency abort`() {
        start(id = 5)
        val data = metric.onLoadCanceled(5, "/other.ts", "other.example.com")
        assertEquals("FragLoadEmergencyAborted", data.requestCancel)
        assertEquals("/video/seg.ts", data.requestUrl)
        assertEquals("cdn.example.com", data.requestHostName)
    }

    @Test
    fun `a cancel without a start still names the request`() {
        val data = metric.onLoadCanceled(6, "/late.ts", "cdn.example.com")
        assertEquals("/late.ts", data.requestUrl)
        assertEquals("cdn.example.com", data.requestHostName)
    }

    @Test
    fun `a failed request carries its exception`() {
        start(id = 8)
        val error = IOException("timeout")
        val data = metric.onLoadError(8, error)
        assertEquals(-1, data.requestErrorCode)
        assertEquals("timeout", data.requestErrorText)
        assertEquals(error.toString(), data.requestError)
        assertEquals("/video/seg.ts", data.requestUrl)
    }

    @Test
    fun `requests carry the renditions known when they start`() {
        val renditions = listOf(ChunkRendition(bitrate = 800_000, width = 640, height = 360))
        metric.renditionList = renditions
        assertEquals(renditions, start().requestRenditionLists)
    }
}
