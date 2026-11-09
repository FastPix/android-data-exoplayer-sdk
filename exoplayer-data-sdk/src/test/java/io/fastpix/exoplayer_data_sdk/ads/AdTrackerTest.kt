package io.fastpix.exoplayer_data_sdk.ads

import com.google.ads.interactivemedia.v3.api.Ad
import com.google.ads.interactivemedia.v3.api.AdError
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ads.AdPlaybackState
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.domain.model.AdModel
import io.fastpix.exoplayer_data_sdk.RecordingSdk
import io.fastpix.exoplayer_data_sdk.SinglePeriodTimeline
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.google.ads.interactivemedia.v3.api.AdEvent as ImaAdEvent

class AdTrackerTest {
    /** A preroll pod of two ads, a midroll at 30s and a postroll, over 60s of content */
    private val schedule = AdPlaybackState("ads", 0L, 30_000_000L, C.TIME_END_OF_SOURCE)
        .withContentDurationUs(60_000_000L)
        .withAdCount(0, 2)
        .withAdDurationsUs(0, 10_000_000L, 5_000_000L)
        .withAdCount(1, 1)
        .withAdCount(2, 1)

    private val recording = RecordingSdk()
    private val exoPlayer = mockk<ExoPlayer>(relaxed = true)
    private var contentResumed = 0
    private val tracker = AdTracker(exoPlayer, recording.sink, onContentResumed = { contentResumed++ })

    /** Each event with the ad the SDK would read for it */
    private val sent = mutableListOf<Pair<PlayerEventType, AdModel?>>()
    private val sentTypes get() = sent.map { it.first }

    @Before
    fun setUp() {
        useSchedule(schedule)
        every { exoPlayer.currentPeriodIndex } returns 0
        every { exoPlayer.contentDuration } returns C.TIME_UNSET
        every { exoPlayer.isPlaying } returns false
        playContent()
        recording.onDispatch = { sent += it to tracker.currentAd() }
    }

    private fun useSchedule(state: AdPlaybackState) {
        every { exoPlayer.currentTimeline } returns SinglePeriodTimeline(state, periodDurationUs = 60_000_000L)
    }

    private fun playAd(group: Int, index: Int) {
        every { exoPlayer.isPlayingAd } returns true
        every { exoPlayer.currentAdGroupIndex } returns group
        every { exoPlayer.currentAdIndexInAdGroup } returns index
    }

    private fun playContent() {
        every { exoPlayer.isPlayingAd } returns false
    }

    private fun enterFirstPrerollAd() {
        playAd(0, 0)
        tracker.sync()
        sent.clear()
    }

    private fun ad(type: PlayerEventType): AdModel? = sent.single { it.first == type }.second

    // Schedule

    @Test
    fun `a schedule with a preroll is signalled once`() {
        tracker.onTimelineChanged()
        tracker.onTimelineChanged()
        assertEquals(listOf(PlayerEventType.prerollScheduled), sentTypes)
        assertNull(ad(PlayerEventType.prerollScheduled))
    }

    @Test
    fun `a schedule without a preroll is not signalled`() {
        useSchedule(AdPlaybackState("ads", 30_000_000L))
        tracker.onTimelineChanged()
        assertTrue(sent.isEmpty())
    }

    // Breaks and ads

    @Test
    fun `entering the preroll opens the break and plays its first ad`() {
        playAd(0, 0)
        tracker.sync()
        assertEquals(
            listOf(PlayerEventType.adBreakStart, PlayerEventType.prerollRequested, PlayerEventType.adPlay),
            sentTypes,
        )
    }

    @Test
    fun `an ad that starts while playback runs is an impression at once`() {
        every { exoPlayer.isPlaying } returns true
        playAd(0, 0)
        tracker.sync()
        assertEquals(PlayerEventType.adImpression, sentTypes.last())
    }

    @Test
    fun `an ad that ran out rolls into the next ad of the pod`() {
        enterFirstPrerollAd()
        playAd(0, 1)
        tracker.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

        assertEquals(listOf(PlayerEventType.adEnded, PlayerEventType.adPlay), sentTypes)
        assertEquals(0, ad(PlayerEventType.adEnded)?.assetIndex)
        assertEquals(1, ad(PlayerEventType.adPlay)?.assetIndex)
    }

    @Test
    fun `leaving the last ad closes the break and resumes content`() {
        enterFirstPrerollAd()
        playContent()
        tracker.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

        assertEquals(listOf(PlayerEventType.adEnded, PlayerEventType.adBreakEnd), sentTypes)
        assertEquals(1, contentResumed)
    }

    @Test
    fun `a skipped ad is not ended`() {
        enterFirstPrerollAd()
        playContent()
        tracker.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SKIP)
        assertEquals(listOf(PlayerEventType.adBreakEnd), sentTypes)
    }

    @Test
    fun `moving to the next ad of the pod does not resume content`() {
        enterFirstPrerollAd()
        playAd(0, 1)
        tracker.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(0, contentResumed)
    }

    @Test
    fun `nothing changes while the ad position stays the same`() {
        enterFirstPrerollAd()
        tracker.sync()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `nothing is tracked once released`() {
        recording.sink.isReleased = true
        playAd(0, 0)
        tracker.sync()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the ad is only readable during its own dispatch`() {
        playAd(0, 0)
        tracker.sync()
        assertNull(tracker.currentAd())
    }

    @Test
    fun `a first frame during an ad is its impression`() {
        enterFirstPrerollAd()
        tracker.onRenderedFirstFrame()
        assertEquals(listOf(PlayerEventType.adImpression), sentTypes)
    }

    @Test
    fun `an ad's impression is sent once whichever signals arrive`() {
        every { exoPlayer.isPlaying } returns true
        playAd(0, 0)
        tracker.sync()
        tracker.onRenderedFirstFrame()
        tracker.onImaAdEvent(imaEvent(ImaAdEvent.AdEventType.STARTED, imaAd()))
        assertEquals(1, sentTypes.count { it == PlayerEventType.adImpression })
    }

    @Test
    fun `each ad of a pod gets its own impression`() {
        every { exoPlayer.isPlaying } returns true
        playAd(0, 0)
        tracker.sync()
        playAd(0, 1)
        tracker.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        tracker.onImaAdEvent(imaEvent(ImaAdEvent.AdEventType.STARTED, imaAd()))
        assertEquals(2, sentTypes.count { it == PlayerEventType.adImpression })
    }

    @Test
    fun `IMA's started event during content is not an impression`() {
        tracker.onImaAdEvent(imaEvent(ImaAdEvent.AdEventType.STARTED, imaAd()))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a first frame during content is not an impression`() {
        tracker.onRenderedFirstFrame()
        assertTrue(sent.isEmpty())
    }

    // The ad model

    @Test
    fun `a preroll ad describes its break and asset`() {
        playAd(0, 1)
        tracker.sync()
        val model = ad(PlayerEventType.adPlay)!!

        assertEquals("adgroup-0", model.breakId)
        assertEquals("preroll", model.breakPosition)
        assertEquals(0L, model.timeOffset)
        assertEquals(15_000L, model.breakDuration)
        assertEquals("adgroup-0:1", model.assetId)
        assertEquals(1, model.assetIndex)
        assertEquals(5_000L, model.assetDuration)
    }

    @Test
    fun `a break is named by its group, and unknown durations are left out`() {
        playAd(1, 0)
        tracker.sync()
        val model = ad(PlayerEventType.adPlay)!!

        assertEquals("adgroup-1", model.breakId)
        assertEquals("midroll", model.breakPosition)
        assertEquals(30_000L, model.timeOffset)
        assertNull(model.breakDuration)
        assertNull(model.assetDuration)
    }

    @Test
    fun `a postroll sits at the end of the content`() {
        playAd(2, 0)
        tracker.sync()
        val model = ad(PlayerEventType.adPlay)!!

        assertEquals("postroll", model.breakPosition)
        assertEquals(60_000L, model.timeOffset)
    }

    @Test
    fun `a midroll break does not count as a preroll request`() {
        playAd(1, 0)
        tracker.sync()
        assertFalse(PlayerEventType.prerollRequested in sentTypes)
    }

    // IMA

    @Test
    fun `an ads request carries its tag, which later ads inherit`() {
        tracker.onAdsRequested("https://ads.example.com/vmap")
        val request = ad(PlayerEventType.adRequest)!!
        assertEquals("https://ads.example.com/vmap", request.tagUrl)
        assertEquals("https://ads.example.com/vmap", request.requestId)

        playAd(1, 0)
        tracker.sync()
        assertEquals("https://ads.example.com/vmap", ad(PlayerEventType.adPlay)?.tagUrl)
    }

    @Test
    fun `IMA's started event is the impression and carries the VAST metadata`() {
        enterFirstPrerollAd()
        tracker.onImaAdEvent(imaEvent(ImaAdEvent.AdEventType.STARTED, imaAd()))

        assertEquals(listOf(PlayerEventType.adImpression), sentTypes)
        val model = ad(PlayerEventType.adImpression)!!
        assertEquals("ad-1", model.adId)
        assertEquals("creative-1", model.creativeId)
        assertEquals("GDFP", model.adSystem)
        assertEquals(5.0, model.skipTimeOffset!!, 0.0)
        assertEquals(listOf("wrapper-ad"), model.wrapperAdIds)
    }

    @Test
    fun `other IMA events only update the VAST metadata`() {
        enterFirstPrerollAd()
        tracker.onImaAdEvent(imaEvent(ImaAdEvent.AdEventType.LOADED, imaAd()))
        assertTrue(sent.isEmpty())

        playAd(0, 1)
        tracker.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals("ad-1", ad(PlayerEventType.adPlay)?.adId)
    }

    @Test
    fun `an unskippable ad reports no skip offset`() {
        enterFirstPrerollAd()
        tracker.onImaAdEvent(imaEvent(ImaAdEvent.AdEventType.STARTED, imaAd(skipTimeOffset = -1.0)))
        assertNull(ad(PlayerEventType.adImpression)?.skipTimeOffset)
    }

    @Test
    fun `an IMA error outside a break reports the ad tag`() {
        tracker.onAdsRequested("https://ads.example.com/vmap")
        sent.clear()
        val error = mockk<AdError> {
            every { errorCodeNumber } returns 1009
            every { message } returns "VAST response was empty"
        }
        tracker.onImaAdError(mockk<AdErrorEvent> { every { this@mockk.error } returns error })

        val model = ad(PlayerEventType.adError)!!
        assertEquals("1009", model.errorCode)
        assertEquals("VAST response was empty", model.errorMessage)
        assertEquals("ima", model.errorSource)
        assertEquals("https://ads.example.com/vmap", model.tagUrl)
    }

    // Ad media

    @Test
    fun `an ad's first media load is its asset URL`() {
        tracker.onAdMediaLoadStarted(0, 1, "https://ads.example.com/ad-2.mp4")
        tracker.onAdMediaLoadStarted(0, 1, "https://ads.example.com/ad-2-retry.mp4")
        playAd(0, 1)
        tracker.sync()
        assertEquals("https://ads.example.com/ad-2.mp4", ad(PlayerEventType.adPlay)?.assetUrl)
    }

    @Test
    fun `an ad whose media has not loaded has no asset URL`() {
        tracker.onAdMediaLoadStarted(0, 0, "https://ads.example.com/ad-1.mp4")
        playAd(0, 1)
        tracker.sync()
        assertNull(ad(PlayerEventType.adPlay)?.assetUrl)
    }

    private fun imaAd(skipTimeOffset: Double = 5.0): Ad = mockk {
        every { adId } returns "ad-1"
        every { creativeId } returns "creative-1"
        every { advertiserName } returns "Acme"
        every { dealId } returns ""
        every { this@mockk.skipTimeOffset } returns skipTimeOffset
        every { adWrapperIds } returns arrayOf("wrapper-ad")
        every { adWrapperCreativeIds } returns emptyArray()
        every { adWrapperSystems } returns emptyArray()
        every { adSystem } returns "GDFP"
    }

    private fun imaEvent(type: ImaAdEvent.AdEventType, ad: Ad): ImaAdEvent = mockk {
        every { this@mockk.type } returns type
        every { this@mockk.ad } returns ad
    }
}
