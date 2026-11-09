package io.fastpix.exoplayer_data_sdk.media

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.video.ColorInfo
import com.google.android.exoplayer2.video.VideoSize
import io.fastpix.data.domain.model.VideoDataDetails
import io.fastpix.data.utils.RenditionName
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentMediaTrackerTest {
    private val exoPlayer = mockk<ExoPlayer>(relaxed = true) {
        every { isPlayingAd } returns false
        every { currentTracks } returns Tracks.EMPTY
        every { currentMediaItem } returns null
    }
    private val videoData = VideoDataDetails()
    private val tracker = ContentMediaTracker(exoPlayer, videoData)

    private fun video(
        bitrate: Int = Format.NO_VALUE,
        width: Int = 1920,
        height: Int = 1080,
        codecs: String? = "avc1.640028",
        frameRate: Float = 30f,
        transfer: Int? = null,
        mimeType: String = MimeTypes.VIDEO_H264,
        label: String? = null,
    ): Format = Format.Builder()
        .setSampleMimeType(mimeType)
        .setPeakBitrate(bitrate)
        .setWidth(width)
        .setHeight(height)
        .setCodecs(codecs)
        .setFrameRate(frameRate)
        .setLabel(label)
        .apply { if (transfer != null) setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()) }
        .build()

    private fun selected(vararg formats: Format): Tracks = Tracks(
        formats.map { Tracks.Group(TrackGroup(it), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)) }
    )

    private fun segment(
        headers: Map<String, List<String>> = emptyMap(),
        bytes: Long = 0,
        dataType: Int = C.DATA_TYPE_MEDIA,
        trackType: Int = C.TRACK_TYPE_DEFAULT,
        startMs: Long = 0,
        endMs: Long = 4_000,
    ): Pair<LoadEventInfo, MediaLoadData> {
        val uri = mockk<Uri>(relaxed = true)
        return LoadEventInfo(1, DataSpec(uri), uri, headers, 0, 0, bytes) to
            MediaLoadData(dataType, trackType, null, C.SELECTION_REASON_UNKNOWN, null, startMs, endMs)
    }

    // Renditions

    @Test
    fun `the first rendition is a variant change`() {
        assertTrue(tracker.onContentVideoFormat(video(bitrate = 3_000_000)))
        assertEquals("3000000", tracker.bitRate)
    }

    @Test
    fun `the same bitrate again is not a variant change`() {
        tracker.onContentVideoFormat(video(bitrate = 3_000_000))
        assertFalse(tracker.onContentVideoFormat(video(bitrate = 3_000_000)))
    }

    @Test
    fun `a new bitrate is a variant change`() {
        tracker.onContentVideoFormat(video(bitrate = 3_000_000))
        assertTrue(tracker.onContentVideoFormat(video(bitrate = 6_000_000)))
        assertEquals("6000000", tracker.bitRate)
    }

    @Test
    fun `a format without a bitrate is no change and keeps the last bitrate`() {
        tracker.onContentVideoFormat(video(bitrate = 3_000_000))
        assertFalse(tracker.onContentVideoFormat(video()))
        assertEquals("3000000", tracker.bitRate)
    }

    @Test
    fun `a video format records its codec, frame rate and size`() {
        tracker.onContentVideoFormat(video(width = 1280, height = 720, codecs = "hvc1.1.6.L93.B0", frameRate = 29.97f))
        assertEquals("hvc1.1.6.L93.B0", tracker.codec)
        assertEquals(29, tracker.frameRate)
        assertEquals(29.97, tracker.declaredFrameRate!!, 0.001)
        assertEquals(1280, tracker.videoSourceWidth)
        assertEquals(720, tracker.videoSourceHeight)
    }

    @Test
    fun `a format without codecs keeps the previous codec`() {
        tracker.onContentVideoFormat(video(codecs = "avc1.640028"))
        tracker.onContentVideoFormat(video(codecs = null))
        assertEquals("avc1.640028", tracker.codec)
    }

    @Test
    fun `an unknown frame rate is reported as none`() {
        tracker.onContentVideoFormat(video(frameRate = Format.NO_VALUE.toFloat()))
        assertNull(tracker.frameRate)
        assertNull(tracker.declaredFrameRate)
    }

    // Dynamic range and rendition name

    @Test
    fun `PQ transfer is PQ`() {
        tracker.onContentVideoFormat(video(transfer = C.COLOR_TRANSFER_ST2084))
        assertEquals("PQ", tracker.dynamicRangeType())
    }

    @Test
    fun `HLG transfer is HLG`() {
        tracker.onContentVideoFormat(video(transfer = C.COLOR_TRANSFER_HLG))
        assertEquals("HLG", tracker.dynamicRangeType())
    }

    @Test
    fun `Dolby Vision without colour info is PQ`() {
        tracker.onContentVideoFormat(video(mimeType = MimeTypes.VIDEO_DOLBY_VISION, codecs = null))
        assertEquals("PQ", tracker.dynamicRangeType())
    }

    @Test
    fun `a Dolby Vision codec string is PQ`() {
        tracker.onContentVideoFormat(video(mimeType = MimeTypes.VIDEO_H265, codecs = "dvh1.05.06"))
        assertEquals("PQ", tracker.dynamicRangeType())
    }

    @Test
    fun `anything else is SDR`() {
        tracker.onContentVideoFormat(video(transfer = C.COLOR_TRANSFER_SDR))
        assertEquals("SDR", tracker.dynamicRangeType())
    }

    @Test
    fun `no video format means no dynamic range or rendition name`() {
        assertNull(tracker.dynamicRangeType())
        assertNull(tracker.renditionName())
    }

    @Test
    fun `the manifest label is the rendition name`() {
        tracker.onContentVideoFormat(video(label = "Full HD"))
        assertEquals("Full HD", tracker.renditionName())
    }

    @Test
    fun `without a label the rendition name is built from the rendition`() {
        tracker.onContentVideoFormat(video(width = 3840, height = 2160, transfer = C.COLOR_TRANSFER_ST2084))
        assertEquals(RenditionName.from(3840, 2160, "PQ"), tracker.renditionName())
    }

    // Content formats, ads and selected tracks

    @Test
    fun `the stored content format is used even during an ad`() {
        val format = video(bitrate = 3_000_000)
        tracker.onContentVideoFormat(format)
        every { exoPlayer.isPlayingAd } returns true
        assertSame(format, tracker.contentFormat(C.TRACK_TYPE_VIDEO))
    }

    @Test
    fun `during an ad the selected tracks are the ad's and are not used`() {
        every { exoPlayer.currentTracks } returns selected(video(bitrate = 500_000))
        every { exoPlayer.isPlayingAd } returns true
        assertNull(tracker.contentFormat(C.TRACK_TYPE_VIDEO))
    }

    @Test
    fun `before any decoded format the selected track is used`() {
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("hi").build()
        every { exoPlayer.currentTracks } returns selected(audio)
        assertEquals("hi", tracker.contentFormat(C.TRACK_TYPE_AUDIO)?.language)
    }

    @Test
    fun `a new media item forgets the previous content's formats`() {
        tracker.onContentVideoFormat(video(bitrate = 3_000_000))
        tracker.onMediaItemTransition(null)
        assertNull(tracker.contentFormat(C.TRACK_TYPE_VIDEO))
    }

    @Test
    fun `a selected subtitle track is reported with its language`() {
        val subtitles = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage("en").build()
        every { exoPlayer.currentTracks } returns selected(subtitles)
        assertEquals("en", tracker.viewerSubtitleFormat()?.language)
    }

    @Test
    fun `forced subtitles are not the viewer's choice`() {
        val forced = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_FORCED)
            .build()
        every { exoPlayer.currentTracks } returns selected(forced)
        assertNull(tracker.viewerSubtitleFormat())
    }

    // Audio bitrate

    @Test
    fun `the declared average audio bitrate comes first`() {
        tracker.onContentAudioFormat(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(96_000).setPeakBitrate(128_000).build()
        )
        assertEquals(96_000, tracker.audioBitrate())
    }

    @Test
    fun `the peak audio bitrate is used when no average is declared`() {
        tracker.onContentAudioFormat(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setPeakBitrate(128_000).build())
        assertEquals(128_000, tracker.audioBitrate())
    }

    @Test
    fun `an audio track that declares no bitrate is measured from its segments`() {
        tracker.onContentAudioFormat(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        val (info, data) = segment(bytes = 64_000, trackType = C.TRACK_TYPE_AUDIO, startMs = 0, endMs = 4_000)
        tracker.onContentSegmentLoaded(info, data)
        assertEquals(128_000, tracker.audioBitrate())
    }

    @Test
    fun `muxed segments are not measured as audio`() {
        val (info, data) = segment(bytes = 64_000, trackType = C.TRACK_TYPE_DEFAULT)
        tracker.onContentSegmentLoaded(info, data)
        assertNull(tracker.audioBitrate())
    }

    @Test
    fun `an audio segment with no media time is not measured`() {
        val (info, data) = segment(bytes = 64_000, trackType = C.TRACK_TYPE_AUDIO, startMs = C.TIME_UNSET, endMs = C.TIME_UNSET)
        tracker.onContentSegmentLoaded(info, data)
        assertNull(tracker.audioBitrate())
    }

    // CDN edge

    @Test
    fun `the Cloudflare PoP is read from cf-ray`() {
        val (info, data) = segment(headers = mapOf("CF-Ray" to listOf("8b2d1f3c4a5e6f70-bom")))
        tracker.onContentSegmentLoaded(info, data)
        assertEquals("BOM", videoData.videoCdnEdgePop)
    }

    @Test
    fun `the CloudFront PoP is read from x-amz-cf-pop`() {
        val (info, data) = segment(headers = mapOf("x-amz-cf-pop" to listOf("BOM78-P1")))
        tracker.onContentSegmentLoaded(info, data)
        assertEquals("BOM78", videoData.videoCdnEdgePop)
    }

    @Test
    fun `the last cache in x-served-by is the edge`() {
        val (info, data) = segment(headers = mapOf("X-Served-By" to listOf("cache-iad-kiad7000001-IAD, cache-bom-vabb7000002-BOM")))
        tracker.onContentSegmentLoaded(info, data)
        assertEquals("BOM", videoData.videoCdnEdgePop)
    }

    @Test
    fun `cf-ray wins over the other headers`() {
        val (info, data) = segment(
            headers = mapOf(
                "x-amz-cf-pop" to listOf("SIN2-C1"),
                "cf-ray" to listOf("8b2d1f-DEL"),
            )
        )
        tracker.onContentSegmentLoaded(info, data)
        assertEquals("DEL", videoData.videoCdnEdgePop)
    }

    @Test
    fun `only media segments report the edge`() {
        val (info, data) = segment(headers = mapOf("cf-ray" to listOf("8b2d1f-BOM")), dataType = C.DATA_TYPE_MANIFEST)
        tracker.onContentSegmentLoaded(info, data)
        assertNull(videoData.videoCdnEdgePop)
    }

    @Test
    fun `no edge header leaves the edge unknown`() {
        val (info, data) = segment(headers = mapOf("server" to listOf("nginx")))
        tracker.onContentSegmentLoaded(info, data)
        assertNull(videoData.videoCdnEdgePop)
    }

    @Test
    fun `without video data the edge is simply not recorded`() {
        val (info, data) = segment(headers = mapOf("cf-ray" to listOf("8b2d1f-BOM")))
        ContentMediaTracker(exoPlayer, null).onContentSegmentLoaded(info, data)
    }

    // Size, mime type, program date time, reset

    @Test
    fun `an empty video size keeps the last known size`() {
        tracker.onVideoSizeChanged(VideoSize(1280, 720))
        tracker.onVideoSizeChanged(VideoSize.UNKNOWN)
        assertEquals(1280, tracker.videoSourceWidth)
        assertEquals(720, tracker.videoSourceHeight)
    }

    @Test
    fun `ready reads the player's video size`() {
        every { exoPlayer.videoSize } returns VideoSize(640, 360)
        tracker.onReady()
        assertEquals(640, tracker.videoSourceWidth)
        assertEquals(360, tracker.videoSourceHeight)
    }

    @Test
    fun `the first track's container is the mime type`() {
        val hls = Format.Builder().setContainerMimeType(MimeTypes.APPLICATION_M3U8).setSampleMimeType(MimeTypes.VIDEO_H264).build()
        tracker.onTracksChanged(TrackGroupArray(TrackGroup(hls)))
        assertEquals(MimeTypes.APPLICATION_M3U8, tracker.mimeType())
    }

    @Test
    fun `unreadable tracks clear the mime type`() {
        val hls = Format.Builder().setContainerMimeType(MimeTypes.APPLICATION_M3U8).build()
        tracker.onTracksChanged(TrackGroupArray(TrackGroup(hls)))
        tracker.onTracksUnreadable()
        assertNull(tracker.mimeType())
    }

    @Test
    fun `there is no program date time during an ad`() {
        every { exoPlayer.isPlayingAd } returns true
        assertNull(tracker.programDateTime())
    }

    @Test
    fun `there is no program date time without a timeline`() {
        every { exoPlayer.currentTimeline } returns Timeline.EMPTY
        assertNull(tracker.programDateTime())
    }

    @Test
    fun `reset clears what the next view must not inherit`() {
        tracker.playerCodec = "c2.android.avc.decoder"
        tracker.onContentVideoFormat(video(bitrate = 3_000_000))
        tracker.onTracksChanged(TrackGroupArray(TrackGroup(Format.Builder().setContainerMimeType(MimeTypes.APPLICATION_M3U8).build())))
        tracker.reset()

        assertNull(tracker.playerCodec)
        assertNull(tracker.codec)
        assertNull(tracker.bitRate)
        assertNull(tracker.frameRate)
        assertNull(tracker.declaredFrameRate)
        assertNull(tracker.videoSourceWidth)
        assertNull(tracker.videoSourceHeight)
        assertNull(tracker.mimeType())
    }
}
