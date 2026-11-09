package io.fastpix.exoplayer_data_sdk.network

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.util.MimeTypes
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.domain.model.BandwidthModel
import io.fastpix.exoplayer_data_sdk.RecordingSdk
import io.fastpix.exoplayer_data_sdk.SinglePeriodTimeline
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BandwidthMetricDispatcherTest {
    private val recording = RecordingSdk()
    private val exoPlayer = mockk<ExoPlayer>(relaxed = true) {
        every { currentTimeline } returns SinglePeriodTimeline()
        every { currentTracks } returns Tracks.EMPTY
    }
    private val metric = BandwidthMetricHls(exoPlayer, ContentMediaTracker(exoPlayer, null))
    private val dispatcher = BandwidthMetricDispatcher(recording.sink, metric)

    /** What the SDK reads through getBandWidthData while each event is dispatched */
    private val models = mutableListOf<BandwidthModel>()

    @Before
    fun setUp() {
        recording.onDispatch = { models += dispatcher.bandwidthModel() }
    }

    private fun load(
        id: Long,
        url: String = "https://cdn.example.com/video/seg$id.ts",
        responseHeaders: Map<String, List<String>> = emptyMap(),
        bytes: Long = 0,
    ): LoadEventInfo {
        val uri = Uri.parse(url)
        return LoadEventInfo(id, DataSpec(uri), uri, responseHeaders, 0, 0, bytes)
    }

    private fun media(dataType: Int = C.DATA_TYPE_MEDIA, format: Format? = null) =
        MediaLoadData(dataType, C.TRACK_TYPE_VIDEO, format, C.SELECTION_REASON_UNKNOWN, null, 0, 4_000)

    private fun completeSegment(id: Long, responseHeaders: Map<String, List<String>> = emptyMap(), bytes: Long = 0) {
        dispatcher.onLoadStarted(load(id), media())
        dispatcher.onLoadCompleted(load(id, responseHeaders = responseHeaders, bytes = bytes), media())
    }

    @Test
    fun `a completed segment is reported with its request`() {
        completeSegment(1, bytes = 2_048)

        assertEquals(listOf(PlayerEventType.requestCompleted), recording.types)
        val model = models.single()
        assertEquals("https://cdn.example.com/video/seg1.ts", model.requestUrl)
        assertEquals("cdn.example.com", model.requestHostName)
        assertEquals("media", model.requestMethod)
        assertEquals("2048", model.requestResponseSize)
    }

    @Test
    fun `only allow-listed response headers are reported, lowercased`() {
        completeSegment(
            1,
            responseHeaders = mapOf(
                "X-Request-Id" to listOf("REQ-42"),
                "Server" to listOf("nginx"),
                "Content-Type" to listOf("video/MP2T"),
                "Set-Cookie" to listOf("session=secret"),
            ),
        )

        val model = models.single()
        assertEquals("req-42", model.requestId)
        assertEquals(
            mapOf("x-request-id" to "req-42", "server" to "nginx", "content-type" to "video/mp2t"),
            model.requestResponseHeaders,
        )
    }

    @Test
    fun `repeated header values are joined`() {
        completeSegment(1, responseHeaders = mapOf("x-cdn" to listOf("Fastly", "Shield")))
        assertEquals("fastly, shield", models.single().requestResponseHeaders?.get("x-cdn"))
    }

    @Test
    fun `a manifest request is reported as one`() {
        dispatcher.onLoadStarted(load(1, url = "https://cdn.example.com/master.m3u8"), media(C.DATA_TYPE_MANIFEST))
        dispatcher.onLoadCompleted(load(1, url = "https://cdn.example.com/master.m3u8"), media(C.DATA_TYPE_MANIFEST))
        assertEquals("manifest", models.single().requestMethod)
    }

    @Test
    fun `an init segment is typed by its sample format`() {
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        dispatcher.onLoadStarted(load(1), media(C.DATA_TYPE_MEDIA_INITIALIZATION, audio))
        dispatcher.onLoadCompleted(load(1), media(C.DATA_TYPE_MEDIA_INITIALIZATION, audio))
        assertEquals("audio_init", models.single().requestMethod)
    }

    @Test
    fun `a completion that never started is not reported`() {
        dispatcher.onLoadCompleted(load(1), media())
        assertTrue(recording.events.isEmpty())
    }

    @Test
    fun `the request's CMCD is readable only while its event is dispatched`() {
        val url = "https://cdn.example.com/video/seg1.ts?CMCD=br%3D3200%2Cot%3Dv%2Csid%3D%22abc%22"
        dispatcher.onLoadStarted(load(1, url = url), media())
        dispatcher.onLoadCompleted(load(1, url = url), media())

        assertNotNull(models.single().cmcdPayload)
        assertNull(dispatcher.bandwidthModel().cmcdPayload)
    }

    @Test
    fun `CMCD without a version reports version 1`() {
        val url = "https://cdn.example.com/video/seg1.ts?CMCD=br%3D3200%2Cot%3Dv%2Csid%3D%22abc%22"
        dispatcher.onLoadStarted(load(1, url = url), media())
        dispatcher.onLoadCompleted(load(1, url = url), media())

        assertEquals("br=3200,ot=v,sid=\"abc\",v=1", models.single().cmcdPayload)
    }

    @Test
    fun `CMCD that carries its version keeps it`() {
        val url = "https://cdn.example.com/video/seg1.ts?CMCD=br%3D3200%2Cv%3D2"
        dispatcher.onLoadStarted(load(1, url = url), media())
        dispatcher.onLoadCompleted(load(1, url = url), media())

        assertEquals("br=3200,v=2", models.single().cmcdPayload)
    }

    @Test
    fun `a request without CMCD reports none`() {
        dispatcher.onLoadStarted(load(1), media())
        dispatcher.onLoadCompleted(load(1), media())

        assertNull(models.single().cmcdPayload)
    }

    @Test
    fun `a cancelled segment is reported as an HLS emergency abort`() {
        dispatcher.onLoadStarted(load(1), media())
        dispatcher.onLoadCanceled(load(1))

        assertEquals(listOf(PlayerEventType.requestCanceled), recording.types)
        assertEquals("FragLoadEmergencyAborted", models.single().requestCancel)
        assertEquals("https://cdn.example.com/video/seg1.ts", models.single().requestUrl)
    }

    @Test
    fun `a failed segment is reported with its error`() {
        dispatcher.onLoadStarted(load(1), media())
        dispatcher.onLoadError(load(1), IOException("timeout"))

        assertEquals(listOf(PlayerEventType.requestFailed), recording.types)
        val model = models.single()
        assertEquals("-1", model.requestErrorCode)
        assertEquals("timeout", model.requestErrorText)
    }

    @Test
    fun `at most ten completions are reported per segment duration`() {
        (1L..12L).forEach { completeSegment(it) }
        assertEquals(10, recording.types.count { it == PlayerEventType.requestCompleted })
    }

    @Test
    fun `nothing is reported once released`() {
        recording.sink.isReleased = true
        completeSegment(1)
        assertTrue(recording.events.isEmpty())
    }

    @Test
    fun `the renditions of the video track ride on later requests`() {
        val formats = listOf(800_000 to 360, 3_000_000 to 1080).map { (bitrate, height) ->
            Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_MP4)
                .setPeakBitrate(bitrate)
                .setWidth(height * 16 / 9)
                .setHeight(height)
                .setCodecs("avc1.640028")
                .setFrameRate(30f)
                .build()
        }
        dispatcher.onTracksChanged(TrackGroupArray(TrackGroup(*formats.toTypedArray())))

        val renditions = metric.onLoadStarted(1, 0, 4_000, "/seg.ts", C.DATA_TYPE_MEDIA, "cdn", "video/avc")
            .requestRenditionLists!!
        assertEquals(listOf(800_000L, 3_000_000L), renditions.map { it.bitrate })
        assertEquals(listOf(360, 1080), renditions.map { it.height })
        assertEquals(listOf(30, 30), renditions.map { it.fps })
    }

    @Test
    fun `audio-only tracks are not renditions`() {
        val audio = Format.Builder().setContainerMimeType(MimeTypes.AUDIO_MP4).setPeakBitrate(128_000).build()
        dispatcher.onTracksChanged(TrackGroupArray(TrackGroup(audio)))
        assertNull(metric.renditionList)
    }

    @Test
    fun `reset forgets the renditions`() {
        metric.renditionList = listOf(ChunkRendition(bitrate = 800_000))
        dispatcher.reset()
        assertNull(metric.renditionList)
    }

    @Test
    fun `the request path drops the scheme and host`() {
        assertEquals("/video/seg1.ts?token=abc", load(1, url = "https://cdn.example.com/video/seg1.ts?token=abc").requestPath())
    }

    @Test
    fun `overlapping requests each report their own request`() {
        val a = "https://cdn.example.com/video/segA.ts?CMCD=br%3D1000"
        val b = "https://cdn.example.com/video/segB.ts?CMCD=br%3D2000"
        dispatcher.onLoadStarted(load(1, url = a), media())
        dispatcher.onLoadStarted(load(2, url = b), media())
        dispatcher.onLoadCompleted(
            load(2, url = b, responseHeaders = mapOf("x-request-id" to listOf("req-b")), bytes = 200),
            media(),
        )
        dispatcher.onLoadError(load(1, url = a), IOException("connection reset"))

        assertEquals(listOf(PlayerEventType.requestCompleted, PlayerEventType.requestFailed), recording.types)
        val (completed, failed) = models
        assertTrue(completed.requestUrl!!.contains("segB.ts"))
        assertEquals("req-b", completed.requestId)
        assertEquals("200", completed.requestResponseSize)
        assertTrue(completed.cmcdPayload!!.startsWith("br=2000"))
        assertTrue(failed.requestUrl!!.contains("segA.ts"))
        assertNull(failed.requestId)
        assertTrue(failed.cmcdPayload!!.startsWith("br=1000"))
        assertEquals("connection reset", failed.requestErrorText)
    }
}
