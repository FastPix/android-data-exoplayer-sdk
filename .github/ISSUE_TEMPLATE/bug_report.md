---
name: Bug Report
about: Create a report to help us improve
title: '[BUG] '
labels: bug
assignees: ''
---

## Bug Description

A clear and concise description of what the bug is.

## Reproduction Steps

1. **Setup Environment**

```groovy
dependencies {
    implementation 'io.fastpix.data:exoplayer:1.1.0'
}
```

2. **Code To Reproduce**

```kotlin
private val fastPixDataSDK = FastPixBaseExoPlayer()
val playerDataDetails = PlayerDataDetails(
            "player-name",
            "player-version"
        )
        val videoDataDetails =
            VideoDataDetails(
                UUID.randomUUID().toString(),
                videoModel?.id
            ).apply {
                videoSeries = "This is video series"
                videoProducer = "This is video Producer"
                videoContentType = "This is video Content Type"
                videoVariant = "This is video Variant"
                videoLanguage = "This is video Language"
            }
        val customDataDetails = CustomDataDetails()
        customDataDetails.customField1 = "Custom 1"
        customDataDetails.customField2 = "Custom 2"
        fastPixDataSDK = FastPixBaseExoPlayer(
            this,
            playerView = binding.playerView,
            exoPlayer = exoPlayer,
            workSpaceId = "workspace-key",
            viewerId = UUID.randomUUID().toString(),
            videoDataDetails = videoDataDetails,
            playerDataDetails = playerDataDetails,
            customDataDetails = customDataDetails
        )
```

3. **Expected Behavior**
```
<!-- A clear and concise description of what you expected to happen.  -->
```

4. **Actual Behavior**
```
<!-- A clear and concise description of what actually happened. -->
```

5. **Environment**

- **SDK Version**: [e.g., 1.2.2]
- **Android Version**: [e.g., Android 12]
- **Min SDK Version**: [e.g., 24]
- **Target SDK Version**: [e.g., 35]
- **Device/Emulator**: [e.g., Pixel 5, Android Emulator]
- **Player**: [e.g., ExoPlayer 2.19.0, VideoView, etc.]
- **Kotlin Version**: [e.g., 2.0.21]

## Code Sample

```kotlin
// Please provide a minimal code sample that reproduces the issue
private val fastPixDataSDK = FastPixBaseExoPlayer()
val playerDataDetails = PlayerDataDetails(
            "player-name",
            "player-version"
        )
        val videoDataDetails =
            VideoDataDetails(
                UUID.randomUUID().toString(),
                videoModel?.id
            ).apply {
                videoSeries = "This is video series"
                videoProducer = "This is video Producer"
                videoContentType = "This is video Content Type"
                videoVariant = "This is video Variant"
                videoLanguage = "This is video Language"
            }
        val customDataDetails = CustomDataDetails()
        customDataDetails.customField1 = "Custom 1"
        customDataDetails.customField2 = "Custom 2"
        fastPixDataSDK = FastPixBaseExoPlayer(
            this,
            playerView = binding.playerView,
            exoPlayer = exoPlayer,
            workSpaceId = "workspace-key",
            viewerId = UUID.randomUUID().toString(),
            videoDataDetails = videoDataDetails,
            playerDataDetails = playerDataDetails,
            customDataDetails = customDataDetails
        )
```

## Logs/Stack Trace

```
Paste relevant logs or stack traces here
```

## Additional Context

Add any other context about the problem here.

## Screenshots

If applicable, add screenshots to help explain your problem.

