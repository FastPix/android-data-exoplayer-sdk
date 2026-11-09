package io.fastpix.exoplayerdata

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Google's public IMA sample VMAP: one preroll, one midroll and one postroll */
const val SAMPLE_VMAP_AD_TAG =
    "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/vmap_ad_samples" +
            "&sz=640x480&cust_params=sample_ar%3Dpremidpost&ciu_szs=300x250&gdfp_req=1&ad_rule=1" +
            "&output=vmap&unviewed_position_start=1&env=vp&impl=s&cmsid=496&vid=short_onecue&correlator="

/** An ad tag that returns HTTP 404 (non-existent FastPix playback ID), for testing IMA ad errors */
const val BROKEN_AD_TAG = "https://stream.fastpix.io/7a1d6c2e-4b8f-4e2a-9c3d-5f6e7a8b9c0d.m3u8"

val dummyData = listOf<DummyData>(
    // For testing ad tracking
    DummyData(
        "Video with Ads",
        "https://stream.fastpix.io/16ac212a-0f4f-49c5-9fd7-a42d9ff61541.m3u8",
        adTagUrl = SAMPLE_VMAP_AD_TAG,
    ),
    // For testing ad error tracking
    DummyData(
        "Video with broken Ads",
        "https://stream.fastpix.io/16ac212a-0f4f-49c5-9fd7-a42d9ff61541.m3u8",
        adTagUrl = BROKEN_AD_TAG,
    ),
    // For testing business errors: HTTP 403, which the sample's errorClassifier marks as a business rule
    DummyData(
        "Video with business error (403)",
        "https://httpbin.org/status/403",
    ),
    // Several audio and subtitle languages, for the audio and subtitle pickers
    DummyData(
        "Multiple Audio Tracks",
        "https://stream.fastpix.io/2125094c-db43-4748-90e1-18539f2ccf98.m3u8",
    ),
    DummyData(
        "16ac212a-0f4f-49c5-9fd7-a42d9ff61541",
        "https://stream.fastpix.io/16ac212a-0f4f-49c5-9fd7-a42d9ff61541.m3u8",
    ),
    DummyData(
        "3ad91e5f-0f45-403f-bda0-2a668a3581ee",
        "https://stream.fastpix.io/3ad91e5f-0f45-403f-bda0-2a668a3581ee.m3u8",
    ),
    DummyData(
        "46c09d0c-d97a-44b2-9737-c5e6daf30a41",
        "https://stream.fastpix.io/46c09d0c-d97a-44b2-9737-c5e6daf30a41.m3u8",
    ),
    DummyData(
        "d81105da-c78a-482e-b1dc-d89e10d8d682",
        "https://stream.fastpix.io/d81105da-c78a-482e-b1dc-d89e10d8d682.m3u8",
    ),
    DummyData(
        "f19268f5-9719-403f-87c9-b604fb3bdce3",
        "https://stream.fastpix.io/f19268f5-9719-403f-87c9-b604fb3bdce3.m3u8",
    ),
    DummyData(
        "112a2222-0f31-44a0-bcf6-30cfa6e1d17d",
        "https://stream.fastpix.io/112a2222-0f31-44a0-bcf6-30cfa6e1d17d.m3u8",
    ),
    DummyData(
        "ca854fd4-a3d0-4525-bd43-80de50887e1a",
        "https://stream.fastpix.io/ca854fd4-a3d0-4525-bd43-80de50887e1a.m3u8",
    ),
)


@Parcelize
data class DummyData(
    var id: String,
    var url: String,
    /** IMA ad tag (VAST or VMAP) whose ads play with this video; null for no ads */
    var adTagUrl: String? = null,
) : Parcelable