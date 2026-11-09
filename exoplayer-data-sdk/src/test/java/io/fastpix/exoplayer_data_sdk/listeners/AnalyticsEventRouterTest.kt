package io.fastpix.exoplayer_data_sdk.listeners

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.util.MimeTypes
import io.fastpix.exoplayer_data_sdk.ads.AdTracker
import io.fastpix.exoplayer_data_sdk.events.PlaybackEventDispatcher
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import io.fastpix.exoplayer_data_sdk.network.BandwidthMetricDispatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import org.junit.Test

class AnalyticsEventRouterTest {
    private val media = mockk<ContentMediaTracker>(relaxed = true)
    private val playback = mockk<PlaybackEventDispatcher>(relaxed = true)
    private val bandwidth = mockk<BandwidthMetricDispatcher>(relaxed = true)
    private val ads = mockk<AdTracker>(relaxed = true)
    private val router = AnalyticsEventRouter(media, playback, bandwidth, ads)

    private val content = eventTime(MediaPeriodId(Any()))
    private val ad = eventTime(MediaPeriodId(Any(), 0, 0, 0))
    private val secondAdOfMidroll = eventTime(MediaPeriodId(Any(), 1, 1, 0))
    private val noPeriod = eventTime(null)

    private val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setPeakBitrate(3_000_000).build()
    private val uri = mockk<Uri>(relaxed = true) { every { this@mockk.toString() } returns "https://ads.example.com/ad.mp4" }
    private val loadEventInfo = LoadEventInfo(1, DataSpec(uri), uri, emptyMap(), 0, 0, 1_024)
    private val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

    private fun eventTime(periodId: MediaPeriodId?) =
        EventTime(0, Timeline.EMPTY, 0, periodId, 0, Timeline.EMPTY, 0, periodId, 0, 0)

    @Test
    fun `the video decoder's name is the player codec`() {
        router.onVideoDecoderInitialized(content, "c2.qti.avc.decoder", 0, 0)
        verify { media.playerCodec = "c2.qti.avc.decoder" }
    }

    @Test
    fun `a content rendition switch is a variant change`() {
        every { media.onContentVideoFormat(format) } returns true
        router.onVideoInputFormatChanged(content, format, null)
        verify { playback.contentVariantChanged() }
    }

    @Test
    fun `a content format that is no switch is not a variant change`() {
        every { media.onContentVideoFormat(format) } returns false
        router.onVideoInputFormatChanged(content, format, null)
        verify(exactly = 0) { playback.contentVariantChanged() }
    }

    @Test
    fun `an ad's video format is not a content rendition`() {
        router.onVideoInputFormatChanged(ad, format, null)
        verify(exactly = 0) { media.onContentVideoFormat(any()) }
    }

    @Test
    fun `a format without a period counts as content`() {
        every { media.onContentVideoFormat(format) } returns true
        router.onVideoInputFormatChanged(noPeriod, format, null)
        verify { media.onContentVideoFormat(format) }
    }

    @Test
    fun `content audio formats are recorded and ad audio is not`() {
        router.onAudioInputFormatChanged(ad, format, null)
        verify(exactly = 0) { media.onContentAudioFormat(any()) }
        router.onAudioInputFormatChanged(content, format, null)
        verify { media.onContentAudioFormat(format) }
    }

    @Test
    fun `a load start reaches the source url and request tracking`() {
        router.onLoadStarted(content, loadEventInfo, mediaLoadData)
        verify { media.onSegmentLoadStarted(uri) }
        verify { bandwidth.onLoadStarted(loadEventInfo, mediaLoadData) }
    }

    @Test
    fun `a content segment feeds the media tracker and request tracking`() {
        router.onLoadCompleted(content, loadEventInfo, mediaLoadData)
        verify { media.onContentSegmentLoaded(loadEventInfo, mediaLoadData) }
        verify { bandwidth.onLoadCompleted(loadEventInfo, mediaLoadData) }
    }

    @Test
    fun `an ad segment is a request but not content media`() {
        router.onLoadCompleted(ad, loadEventInfo, mediaLoadData)
        verify(exactly = 0) { media.onContentSegmentLoaded(any(), any()) }
        verify { bandwidth.onLoadCompleted(loadEventInfo, mediaLoadData) }
    }

    @Test
    fun `cancelled and failed loads reach request tracking`() {
        val error = IOException("timeout")
        router.onLoadCanceled(content, loadEventInfo, mediaLoadData)
        router.onLoadError(content, loadEventInfo, mediaLoadData, error, false)
        verify { bandwidth.onLoadCanceled(loadEventInfo) }
        verify { bandwidth.onLoadError(loadEventInfo, error) }
    }

    @Test
    fun `an ad's media load is its asset URL`() {
        router.onLoadStarted(secondAdOfMidroll, loadEventInfo, mediaLoadData)
        verify { ads.onAdMediaLoadStarted(1, 1, "https://ads.example.com/ad.mp4") }
    }

    @Test
    fun `content loads and ad manifests are not ad media`() {
        router.onLoadStarted(content, loadEventInfo, mediaLoadData)
        router.onLoadStarted(ad, loadEventInfo, MediaLoadData(C.DATA_TYPE_MANIFEST))
        verify(exactly = 0) { ads.onAdMediaLoadStarted(any(), any(), any()) }
    }
}
