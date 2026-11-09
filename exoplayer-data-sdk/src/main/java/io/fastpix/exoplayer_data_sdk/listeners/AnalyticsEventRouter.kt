package io.fastpix.exoplayer_data_sdk.listeners

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import io.fastpix.exoplayer_data_sdk.ads.AdTracker
import io.fastpix.exoplayer_data_sdk.events.PlaybackEventDispatcher
import io.fastpix.exoplayer_data_sdk.media.ContentMediaTracker
import io.fastpix.exoplayer_data_sdk.network.BandwidthMetricDispatcher
import java.io.IOException

/** Routes ExoPlayer's decoder, format and load callbacks to the trackers */
internal class AnalyticsEventRouter(
    private val media: ContentMediaTracker,
    private val playback: PlaybackEventDispatcher,
    private val bandwidth: BandwidthMetricDispatcher,
    private val ads: AdTracker,
) : AnalyticsListener {

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        media.playerCodec = decoderName
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        // Ad media formats are not content renditions. Decided by the period the format
        // belongs to, not the player's position: the next period's format is read ahead,
        // so content's first format arrives while the player is still in the preroll
        if (eventTime.mediaPeriodId?.isAd == true) return
        if (media.onContentVideoFormat(format)) playback.contentVariantChanged()
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        if (eventTime.mediaPeriodId?.isAd != true) media.onContentAudioFormat(format)
    }

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        if (loadEventInfo.uri == null) return
        media.onSegmentLoadStarted(loadEventInfo.uri)
        bandwidth.onLoadStarted(loadEventInfo, mediaLoadData)
        val periodId = eventTime.mediaPeriodId
        if (periodId?.isAd == true && mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
            ads.onAdMediaLoadStarted(periodId.adGroupIndex, periodId.adIndexInAdGroup, loadEventInfo.uri.toString())
        }
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        // Ad segments are loaded ahead of the break too; only content segments count
        if (eventTime.mediaPeriodId?.isAd != true) media.onContentSegmentLoaded(loadEventInfo, mediaLoadData)
        bandwidth.onLoadCompleted(loadEventInfo, mediaLoadData)
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        if (loadEventInfo.uri != null) bandwidth.onLoadCanceled(loadEventInfo)
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        bandwidth.onLoadError(loadEventInfo, error)
    }
}
