package io.fastpix.exoplayer_data_sdk.ads

import com.google.ads.interactivemedia.v3.api.Ad
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.domain.model.AdModel
import io.fastpix.data.utils.Logger
import io.fastpix.exoplayer_data_sdk.events.EventSink
import io.fastpix.exoplayer_data_sdk.events.TAG
import com.google.ads.interactivemedia.v3.api.AdEvent as ImaAdEvent

/**
 * Ad tracking. Breaks and ads are read from the player's ad playback state; IMA adds its VAST
 * metadata on top. ExoPlayer 2.19.1 has no HLS interstitials, so IMA is the only ad source
 */
internal class AdTracker(
    private val exoPlayer: ExoPlayer,
    private val sink: EventSink,
    /** Called when the last ad of a break is left and content plays again */
    private val onContentResumed: () -> Unit,
) {
    private var currentImaAd: Ad? = null
    private var adTagUrl: String? = null
    /** Ad group and index being played, or null during content */
    private var currentAdSlot: Pair<Int, Int>? = null
    /** The ad slot whose impression was sent, so each ad is reported once whichever signal comes first */
    private var impressedSlot: Pair<Int, Int>? = null
    private var lastDiscontinuityReason: Int? = null
    private var prerollScheduledSignalled = false
    /** Media URI of each ad, by ad group and index, from the ad's first media load */
    private val adMediaUris = mutableMapOf<Pair<Int, Int>, String>()
    /** The ad of the ad event being dispatched; read by currentAd() for the duration of that dispatch */
    private var dispatchingAd: AdModel? = null

    fun currentAd(): AdModel? = dispatchingAd

    fun onAdsRequested(tagUrl: String) {
        adTagUrl = tagUrl
        dispatchAdEvent(PlayerEventType.adRequest, AdModel(tagUrl = tagUrl, requestId = tagUrl))
    }

    fun onImaAdEvent(event: ImaAdEvent) {
        event.ad?.let { currentImaAd = it }
        when (event.type) {
            ImaAdEvent.AdEventType.STARTED -> {
                sync()
                dispatchImpression()
            }
            else -> Unit
        }
    }

    fun onImaAdError(event: AdErrorEvent) {
        val error = event.error
        val model = (currentAdSlot?.let(::adModel) ?: AdModel(tagUrl = adTagUrl)).copy(
            errorCode = error.errorCodeNumber.toString(),
            errorMessage = error.message,
            errorSource = "ima",
        )
        dispatchAdEvent(PlayerEventType.adError, model)
    }

    fun onTimelineChanged() {
        signalPrerollScheduled()
        sync()
    }

    fun onPositionDiscontinuity(reason: Int) {
        lastDiscontinuityReason = reason
        sync()
    }

    /**
     * The ad reached the screen, for ads that start without a playing event of their own.
     * Between two ads of a pod the next ad's first frame is reported before the other
     * callbacks, so the ad position is brought up to date first: adPlay before the impression
     */
    fun onRenderedFirstFrame() {
        sync()
        dispatchImpression()
    }

    /**
     * Turns the player's ad position into ad events: a new ad group opens a break, each ad in it
     * is an attempt, and leaving the group closes the break. An ad that ran into the next period
     * played to its end; one left any other way (skipped, seeked past) did not
     */
    fun sync() {
        if (sink.isReleased) return
        val slot = if (exoPlayer.isPlayingAd) {
            exoPlayer.currentAdGroupIndex to exoPlayer.currentAdIndexInAdGroup
        } else {
            null
        }
        val previous = currentAdSlot
        if (slot == previous) return
        currentAdSlot = slot
        impressedSlot = null
        val reason = lastDiscontinuityReason
        lastDiscontinuityReason = null

        previous?.let { leaveAd(it, next = slot, reason = reason) }
        slot?.let { enterAd(it, previous = previous) }
    }

    /** Leaving the ad at [slot]: it ended if it ran out on its own, and the break closes unless [next] is in it */
    private fun leaveAd(slot: Pair<Int, Int>, next: Pair<Int, Int>?, reason: Int?) {
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            dispatchAdEvent(PlayerEventType.adEnded, adModel(slot))
        }
        if (next?.first != slot.first) dispatchAdEvent(PlayerEventType.adBreakEnd, adModel(slot))
        if (next == null) onContentResumed()
    }

    /** Entering the ad at [slot]: a new ad group opens a break, and every ad is an attempt */
    private fun enterAd(slot: Pair<Int, Int>, previous: Pair<Int, Int>?) {
        val model = adModel(slot)
        if (previous?.first != slot.first) {
            dispatchAdEvent(PlayerEventType.adBreakStart, model)
            // Nothing else announces a preroll request per break, so it is signalled here
            if (model?.breakPosition == "preroll") dispatchAdEvent(PlayerEventType.prerollRequested, model)
        }
        dispatchAdEvent(PlayerEventType.adPlay, model)
        // An ad that starts while playback is already running is on screen at once. ExoPlayer rolls
        // from one ad of a pod into the next without a new playing event or a new first frame, so
        // this is the only impression signal the second and later ads of a pod get
        if (exoPlayer.isPlaying) dispatchImpression()
    }

    /** The current ad's impression, sent once per ad: IMA's STARTED, the first frame and playback all signal it */
    private fun dispatchImpression() {
        val slot = currentAdSlot ?: return
        if (slot == impressedSlot) return
        impressedSlot = slot
        dispatchAdEvent(PlayerEventType.adImpression, adModel(slot))
    }

    /** The schedule has a preroll: known before it is requested, so a preroll that never fills still counts as requested */
    private fun signalPrerollScheduled() {
        if (prerollScheduledSignalled) return
        val period = currentPeriod() ?: return
        val hasPreroll = (0 until period.adGroupCount).any { period.getAdGroupTimeUs(it) == 0L }
        if (!hasPreroll) return
        prerollScheduledSignalled = true
        dispatchAdEvent(PlayerEventType.prerollScheduled, null)
    }

    /**
     * The current period, which carries the ad schedule. ExoPlayer 2.19.1 keeps the period's
     * AdPlaybackState private, so ad groups are read through the period's own accessors
     */
    private fun currentPeriod(): Timeline.Period? {
        val timeline = exoPlayer.currentTimeline
        if (timeline.isEmpty) return null
        return timeline.getPeriod(exoPlayer.currentPeriodIndex, Timeline.Period())
    }

    /** The break and ad at [slot] as the player's ad schedule describes them, plus IMA's VAST metadata */
    private fun adModel(slot: Pair<Int, Int>): AdModel? {
        val period = currentPeriod() ?: return null
        val (groupIndex, adIndex) = slot
        if (groupIndex !in 0 until period.adGroupCount) return null
        val groupTimeUs = period.getAdGroupTimeUs(groupIndex)
        // ExoPlayer 2.19.1 ad groups carry no IDs, so the break is named by its index
        val breakId = "adgroup-$groupIndex"
        val contentDurationUs = period.durationUs.takeIf { it != C.TIME_UNSET }
            ?: exoPlayer.contentDuration.takeIf { it != C.TIME_UNSET }?.times(1000)
        val position = when {
            groupTimeUs == C.TIME_END_OF_SOURCE -> "postroll"
            groupTimeUs <= 0L -> "preroll"
            else -> "midroll"
        }
        val offsetMs = if (groupTimeUs == C.TIME_END_OF_SOURCE) contentDurationUs?.div(1000) else groupTimeUs / 1000
        val durationsUs = (0 until period.getAdCountInAdGroup(groupIndex)).map { period.getAdDurationUs(groupIndex, it) }
        val breakDurationMs = durationsUs
            .takeIf { it.isNotEmpty() && it.all { d -> d != C.TIME_UNSET && d > 0 } }
            ?.sum()?.div(1000)
        val ima = currentImaAd
        return AdModel(
            adId = ima?.adId,
            creativeId = ima?.creativeId,
            advertiserName = ima?.advertiserName,
            dealId = ima?.dealId,
            skipTimeOffset = ima?.skipTimeOffset?.takeIf { it >= 0 },
            wrapperAdIds = ima?.adWrapperIds?.toList(),
            wrapperCreativeIds = ima?.adWrapperCreativeIds?.toList(),
            wrapperSystems = ima?.adWrapperSystems?.toList(),
            adSystem = ima?.adSystem,
            breakId = breakId,
            breakPosition = position,
            timeOffset = offsetMs,
            breakDuration = breakDurationMs,
            tagUrl = adTagUrl,
            assetId = "$breakId:$adIndex",
            assetUrl = adMediaUris[slot],
            assetIndex = adIndex,
            assetDuration = durationsUs.getOrNull(adIndex)
                ?.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000),
        )
    }

    /**
     * The ad at [adGroupIndex], [adIndexInAdGroup] began loading its media from [uri]. The period
     * hides the ad's URI in ExoPlayer 2.19.1, so the first media load of each ad stands in for it
     */
    fun onAdMediaLoadStarted(adGroupIndex: Int, adIndexInAdGroup: Int, uri: String) {
        adMediaUris.putIfAbsent(adGroupIndex to adIndexInAdGroup, uri)
    }

    private fun dispatchAdEvent(type: PlayerEventType, model: AdModel?) {
        if (!sink.canDispatch) return
        Logger.log(TAG, "PLAYER_EVENT: $type")
        dispatchingAd = model
        try {
            sink.dispatch(type)
        } finally {
            dispatchingAd = null
        }
    }
}
