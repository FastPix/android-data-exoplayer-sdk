[![License](https://img.shields.io/badge/License-Proprietary-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.1.5-green.svg)](CHANGELOG.md)
[![Min SDK](https://img.shields.io/badge/minSdk-24-orange.svg)](app/build.gradle.kts)

# FastPix Exoplayer SDK

This SDK enables seamless integration with **Android ExoPlayer**, offering advanced video analytics via the **FastPix Dashboard**. It's a wrapper built on [FastPix’s core Kotlin library](https://github.com/FastPix/android-core-data-sdk) to deliver performance monitoring for video applications using [Google's ExoPlayer](https://github.com/google/exoplayer).

## Key Features
- **User engagement tracking** – Monitor viewer interactions in real-time.
- **Playback quality analytics** – Evaluate buffering, resolution changes, and network issues.
- **Rendition details** – Rendition name, dynamic range (SDR, PQ, HLG), video and audio codecs, and audio bitrate.
- **Player state** – Subtitles and audio language, picture-in-picture, fullscreen, mute, volume, playback rate, and looping.
- **Request details** – CMCD data from each segment request, and the CDN edge that served it.
- **Ad tracking** – Google IMA ad breaks, ads, and ad errors, kept out of content metrics.
- **Custom event tracking** – Track domain-specific user behaviors.
- **Device & app diagnostics** – Gain insights into playback issues across devices.
- **Error logging** – Automatically capture fatal and handled playback errors, optionally classified as business or technical.
- **Beacon domain support** – Send analytics to a custom tracking domain.

## 🔧 Requirements

- **ExoPlayer**: 2.19.1
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 36
- **Java**: 11 bytecode (`sourceCompatibility` / `targetCompatibility`)
- **Kotlin** (apps written in Kotlin): 2.1 or newer, as FastPix Core requires. Java apps have no Kotlin requirement
- **Tested with**: an app on Kotlin 2.1.0 and Android Gradle Plugin 8.9.1, run on a Pixel 7 (Android 17)

To build this repository and its sample app:

- **Android Studio**: a release that supports Android Gradle Plugin 9.4
- **Gradle**: 9.7.1 (wrapper included)
- **Android Gradle Plugin**: 9.4.1
- **Kotlin**: 2.3.21
- **JDK**: 17 to 25 (set it under **Settings > Build Tools > Gradle > Gradle JDK**)

## 🚀 Setup

### Step 1: Add the GitHub Maven Repositories to `settings.gradle`

The SDK and its core dependency (`io.fastpix.data:core`) are published to two GitHub Packages repositories. Add both, or the core dependency will fail to resolve. GitHub Packages needs your GitHub username and a personal access token with the `read:packages` scope:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/FastPix/android-data-exoplayer-sdk")
        credentials {
            username = "<your-github-username>"
            password = "<your-personal-access-token>"
        }
    }
    maven {
        url = uri("https://maven.pkg.github.com/FastPix/android-core-data-sdk")
        credentials {
            username = "<your-github-username>"
            password = "<your-personal-access-token>"
        }
    }
}
```

Keep the token out of tracked files: read it from `local.properties` (git-ignored) or an environment variable.

### Step 2: Add the SDK Dependency to `build.gradle`
```groovy
dependencies {
    implementation 'io.fastpix.data:exoplayer:1.1.5'
}
```

This also brings in `io.fastpix.data:core` and the ExoPlayer IMA extension.

### Step 3: Add the permissions to `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Optionally, add `READ_PHONE_STATE` so the SDK can report the cellular radio type (2G, 3G, LTE, 5G) as the viewer's connection speed. Without it, the SDK uses the bandwidth estimate instead:

```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

## 📖 Usage

### Basic Player Setup

```kotlin
class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var fastPixDataSDK: FastPixBaseExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupAnalytics()
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        
        val mediaItem = MediaItem.fromUri("YOUR_VIDEO_URL")
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    private fun setupAnalytics() {
        // Optional
        val videoDataDetails = VideoDataDetails(
            videoId = UUID.randomUUID().toString(),
            videoTitle = "My Video"
        ).apply {
            videoSeries = "Demo Series"
            videoProducer = "Demo Producer"
            videoContentType = "VOD"
            // ..etc
        }
        // Optional
        val playerDataDetails = PlayerDataDetails(
            playerName = "exoplayer",
            playerVersion = "latest-version"
        )
        // Optional
        val customDataDetails = CustomDataDetails().apply {
            customField1 = "Custom Value 1"
            customField2 = "Custom Value 2"
            // ..etc
        }

        fastPixDataSDK = FastPixBaseExoPlayer(
            context = this,
            playerView = binding.playerView,
            exoPlayer = exoPlayer,
            workSpaceId = "workspace-key",
            playerDataDetails = playerDataDetails,
            videoDataDetails = videoDataDetails,
            customDataDetails = customDataDetails
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
        fastPixDataSDK.release()
    }
}
```

### Optional parameters

| Parameter | What it does |
|---|---|
| `beaconUrl` | Sends analytics to a custom tracking domain. |
| `enableLogging` | Logs each event's payload to Logcat, for debugging. |
| `applicationDataDetails` | App and page context: app name and version, page type, content ID, device ID. |
| `viewerDataDetails` | Viewer details, such as the viewer's plan. |
| `adDataDetails` | The view's ad details from your ad server or VAST, sent on view begin and view completed. |
| `errorClassifier` | `(PlaybackException) -> Boolean?`. Return `true` for a business rule (geo-block, entitlement), `false` for a technical fault, or `null` when you can't tell. |
| `controlsEnabled` | `() -> Boolean?`. Whether your player controls are enabled. Set it when you draw your own controls (`use_controller = false`). |
| `autoPlay` | Whether your app starts playback on its own. |

### Track ads (Google IMA)

The SDK tracks ads played through the ExoPlayer IMA extension, which it brings in for you. Ad breaks and ads are read from the player, so ad time never counts as content. Forward IMA's events and errors to the SDK, and call `onAdsRequested` with the ad tag:

```kotlin
private val adsLoader: ImaAdsLoader by lazy {
    ImaAdsLoader.Builder(this)
        .setAdEventListener { fastPixDataSDK.onImaAdEvent(it) }
        .setAdErrorListener { fastPixDataSDK.onImaAdError(it) }
        .build()
}

// After creating FastPixBaseExoPlayer
fastPixDataSDK.onAdsRequested(AD_TAG_URL)
```

ExoPlayer 2.19.1 has no HLS interstitials support, so server-side ads in HLS playlists are not tracked as ads. For those, use the [AndroidX Media3 SDK](https://github.com/FastPix/android-data-androidXmedia3).

### Enable CMCD (optional)

The SDK reports the CMCD data ExoPlayer sends with each request. Turn CMCD on in your media source factory:

```kotlin
val mediaSourceFactory = DefaultMediaSourceFactory(this)
    .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
```

> **Known ExoPlayer 2.19.1 issue:** with CMCD on, switching to another HLS audio or subtitle track fails playback with `IllegalArgumentException` in `CmcdHeadersFactory`. This is a bug in ExoPlayer itself, and 2.19.1 is the final ExoPlayer release, so it will not be fixed there. Turn CMCD on only for streams whose audio and subtitle tracks are never switched, or move to [AndroidX Media3](https://github.com/FastPix/android-data-androidXmedia3). The sample app ships with CMCD off for this reason.

ExoPlayer 2.19.1 sends bitrate, buffer length, content and session IDs, streaming format, stream type, top bitrate, object duration and type, and measured throughput. It leaves out the CMCD version (`v`) because it is 1, as the CMCD spec allows, so the SDK reports version 1 for it. ExoPlayer sends requested maximum throughput (`rtp`) only if your `CmcdConfiguration.RequestConfig` returns a value from `getRequestedMaximumThroughputKbps`.

## 📄 License

Copyright © 2025 FastPix. All rights reserved.

This project is proprietary software. The FastPix SDK components are proprietary and require appropriate licensing. See individual module README files for specific license information.

## 📧 Support

For questions, issues, or feature requests:

- **Email**: support@fastpix.com
- **Documentation**: [FastPix Documentation](https://fastpix.com/docs)
- **SDK Issues**: [GitHub Issues](https://github.com/FastPix/android-core-data-sdk/issues)

## 🔗 Related Documentation

- [Android Data Core SDK README](android-data-core/README.md)
- [FastPix Documentation](https://fastpix.com/docs)
- [ExoPlayer Developer Guide](https://developer.android.com/guide/topics/media/exoplayer)

---

**Built with ❤️ using Exoplayer and FastPix Analytics**

