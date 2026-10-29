[![License](https://img.shields.io/badge/License-Proprietary-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.1.4-green.svg)](CHANGELOG.md)
[![Min SDK](https://img.shields.io/badge/minSdk-24-orange.svg)](app/build.gradle.kts)

# FastPix Video Data SDK for ExoPlayer - monitor playback performance and QoE on Android

The **FastPix Video Data SDK for ExoPlayer** is a QoE (quality-of-experience) analytics and performance-monitoring wrapper for Google's **ExoPlayer** on Android. It attaches to your existing `ExoPlayer` instance and tracks viewer engagement, streaming performance, and delivery bottlenecks - startup time, rebuffering, bitrate and resolution changes, dropped frames, and playback errors - then reports them to the [FastPix dashboard](https://dashboard.fastpix.com) in real time.

It is **not a video player**. It does not render video or replace ExoPlayer; it observes the player you already have and turns playback events into monitoring data, the same way a third-party monitoring SDK plugs into ExoPlayer alongside the built-in `AnalyticsListener`. The SDK is a thin wrapper built on [FastPix's core Kotlin library](https://github.com/FastPix/android-core-data-sdk).

> **Using AndroidX Media3 (the current ExoPlayer)?** This repository integrates with the standalone **Google ExoPlayer** (the legacy `com.google.android.exoplayer2` / [google/ExoPlayer](https://github.com/google/exoplayer) line). If your app uses **AndroidX Media3** - the actively developed successor that most new ExoPlayer apps are on - use the [FastPix AndroidX Media3 Data SDK](https://github.com/FastPix/android-data-androidXmedia3) instead.

**Works with:** Google ExoPlayer (standalone / legacy) · Android 7.0+ (API 24) · Kotlin and Java · Gradle · `io.fastpix.data:exoplayer`

[FastPix documentation](https://fastpix.com/docs/video-data/overview) · [Create a free account](https://dashboard.fastpix.com/signup?utm_source=github&utm_medium=referral&utm_campaign=github_signup)

## Start here

Follow these in order:

1. [Why FastPix for ExoPlayer monitoring](#why-fastpix)
2. [What you can track](#what-you-can-track)
3. [Who it's for](#who-its-for)
4. [Requirements](#requirements)
5. [Add the GitHub Maven repository](#step-1-add-the-github-maven-repository-to-settingsgradle)
6. [Add the SDK dependency](#step-2-add-the-sdk-dependency-to-buildgradle)
7. [Attach analytics to your player](#usage)
8. [Which FastPix SDK for which Android player](#which-fastpix-sdk-for-which-android-player)
9. [FAQ](#faq)

## Why FastPix

ExoPlayer exposes raw playback events, but turning them into viewer-facing quality metrics, storing them, and watching them across devices and app versions is work you would otherwise build yourself. FastPix does that for you:

- **Real-time QoE monitoring.** See startup time, rebuffering, bitrate switches, and errors as they happen on real devices, in one dashboard.
- **Built on ExoPlayer's own signals.** The wrapper listens to your player, so you keep full control of playback and add monitoring with a few lines.
- **One data model across platforms.** The same FastPix Video Data metrics are reported from web, iOS, Android, and other player integrations, so you compare experiences like for like.
- **Works with the player you already ship.** No player swap, no re-architecture - attach the SDK to your existing ExoPlayer setup.

## What you can track

- **User engagement tracking** - monitor viewer interactions in real time.
- **Playback quality analytics** - evaluate buffering, resolution changes, and network issues.
- **Custom event tracking** - track domain-specific user behaviors.
- **Device and app diagnostics** - gain insight into playback issues across devices.
- **Error logging** - automatically capture fatal and handled playback errors.
- **Beacon domain support** - send analytics to a custom tracking domain.

These map to the core QoE questions teams ask about ExoPlayer: how fast playback starts, how often and how long it rebuffers, which renditions play, how many frames drop, and which errors viewers hit.

## Who it's for

- **Android teams running the standalone Google ExoPlayer** who want production QoE monitoring without building an analytics pipeline.
- **Streaming and OTT apps** that need to catch rebuffering, slow startup, and playback errors before users churn.
- **Anyone comparing playback quality across platforms** who wants ExoPlayer data in the same dashboard as their web and iOS players.

If your app is on AndroidX Media3, use the [Media3 Data SDK](https://github.com/FastPix/android-data-androidXmedia3) instead - it reports the same metrics for the current ExoPlayer.

## Requirements

- **Android Studio**: Ladybug | 2024.2.1 or later
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 36
- **Kotlin**: 2.2.10+
- **Java**: 11
- **Gradle**: 8.9+

You will also need a FastPix account and a workspace ID. [Create a free account](https://dashboard.fastpix.com/signup?utm_source=github&utm_medium=referral&utm_campaign=github_signup), then follow [set up a workspace](https://fastpix.com/docs/getting-started/set-up-a-workspace) and [generate API credentials](https://fastpix.com/docs/getting-started/activate-your-account#generate-api-credentials).

## Setup

### Step 1: Add the GitHub Maven Repository to `settings.gradle`
```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/FastPix/android-data-exoplayer-sdk")
        credentials {
            username = "<your-github-username>"
            password = "<your-personal-access-token>"
        }
    }
}
```

The package is hosted on GitHub Packages, so the `username` is your GitHub username and the `password` is a GitHub personal access token with the `read:packages` scope.

### Step 2: Add the SDK Dependency to `build.gradle`
```groovy
dependencies {
    implementation 'io.fastpix.data:exoplayer:1.1.4'
}
```

## Usage

### Basic Player Setup

Create your `ExoPlayer` as usual, then pass it to `FastPixBaseExoPlayer` to start reporting analytics. The analytics details (`videoDataDetails`, `playerDataDetails`, `customDataDetails`) are optional metadata that enrich your dashboard.

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

Call `fastPixDataSDK.release()` when the player is torn down (as shown in `onDestroy`) so the SDK flushes and stops cleanly.

## Which FastPix SDK for which Android player

FastPix ships a separate Video Data SDK per player. Pick the one that matches the player your app ships:

| Your Android player | Use this SDK |
| --- | --- |
| **Google ExoPlayer** (standalone / legacy) | **FastPix/android-data-exoplayer-sdk** (this repository) |
| **AndroidX Media3** (the current ExoPlayer) | [FastPix/android-data-androidXmedia3](https://github.com/FastPix/android-data-androidXmedia3) |
| Any player, shared core layer | [FastPix/android-core-data-sdk](https://github.com/FastPix/android-core-data-sdk) (the core library this wrapper is built on) |

## FAQ

**How do I monitor ExoPlayer performance in Android?**
Attach the FastPix Video Data SDK to your existing `ExoPlayer` instance. It tracks startup time, rebuffering, bitrate and resolution changes, dropped frames, and errors, and reports them to the FastPix dashboard. See [Attach analytics to your player](#usage).

**What metrics does it track?**
Viewer engagement and QoE signals: startup/time-to-first-frame, rebuffering frequency and duration, bitrate and resolution switches, dropped frames, playback errors (fatal and handled), and device/app diagnostics. See [What you can track](#what-you-can-track).

**Is this a video player?**
No. It does not play or render video. It is a monitoring wrapper that observes the ExoPlayer you already use and turns playback events into analytics.

**How is this different from ExoPlayer's built-in `AnalyticsListener`?**
`AnalyticsListener` gives you raw playback events to handle yourself. FastPix consumes those signals for you, computes QoE metrics, stores them, and presents them across devices, sessions, and app versions in one dashboard - so you do not build and run the pipeline.

**I'm on AndroidX Media3 - which SDK do I use?**
Use the [FastPix AndroidX Media3 Data SDK](https://github.com/FastPix/android-data-androidXmedia3). This repository targets the standalone Google ExoPlayer (the legacy line); Media3 is the current ExoPlayer and has its own SDK that reports the same metrics.

**Does it work with the legacy Google ExoPlayer?**
Yes. This SDK is specifically for the standalone [google/ExoPlayer](https://github.com/google/exoplayer) integration.

**Do I need a FastPix account?**
Yes. You need a workspace ID from your FastPix account to report data. [Create a free account](https://dashboard.fastpix.com/signup?utm_source=github&utm_medium=referral&utm_campaign=github_signup).

## Support

For questions, issues, or feature requests:

- **Email**: support@fastpix.com
- **Documentation**: [FastPix Documentation](https://fastpix.com/docs)
- **SDK Issues**: [GitHub Issues](https://github.com/FastPix/android-data-exoplayer-sdk/issues)

## Related documentation

- [FastPix Android Data Core SDK](https://github.com/FastPix/android-core-data-sdk) - the core library this wrapper is built on
- [FastPix Video Data overview](https://fastpix.com/docs/video-data/overview)
- [FastPix Documentation](https://fastpix.com/docs)
- [ExoPlayer Developer Guide](https://developer.android.com/guide/topics/media/exoplayer)

## License

Copyright © 2025 FastPix. All rights reserved.

This project is proprietary software. The FastPix SDK components are proprietary and require appropriate licensing. See individual module README files for specific license information.
