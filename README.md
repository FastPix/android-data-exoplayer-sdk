# Introduction

This SDK simplifies the integration process with ExoPlayer on Android, enabling the seamless collection of player analytics. It automatically tracks video performance metrics, making the data accessible on the FastPix Dashboard for monitoring and analysis.

This is the FastPix wrapper for ExoPlayer, built on top of FastPix's core Java library, delivering FastPix Data performance analytics for apps that use [Google's ExoPlayer](https://github.com/google/ExoPlayer).

# Key Features:
- **User engagement metrics:** Capture detailed viewer interaction data. 
- **Playback quality monitoring:** Real-time performance analysis of your video streams. 
- **Android performance insights:** Identify and resolve bottlenecks affecting video delivery. 
- **Customizable tracking:** Flexible configuration to match your specific monitoring needs. 
- **Error management:** Robust error handling and reporting. 
- **Custom Domain:** Using with custom Beacon Domain. 
- **Streaming diagnostics:** Gain deep insights into the performance of your video streaming. 

# Prerequisites:
- Android Studio Arctic Fox or later
- Minimum Android SDK 21+
- Sample Player integrated into your project
- FastPix Data Exo-player SDK added as a dependency
- Generate a GitHub Personal Access Token (PAT) from Your GitHub account

## Getting started with FastPix:

To track and analyze video performance, initialize the FastPix Data SDK with your Workspace key:

1. **[Access the FastPix Dashboard](https://dashbord.fastpix.io)**: Log in and navigate to the Workspaces section.
2. **Locate Your Workspace Key**: Copy the Workspace Key for client-side monitoring. Included in your Android application's code wherever you want to track video performance and analytics.

# Installation:

To get started with integrate the FastPix Data SDK. 
Gradle configured in your project for managing dependencies

# Installation:
Add our maven repository to your **settings.gradle**:
```groovy
repositories {
  maven {
    url = uri("https://maven.pkg.github.com/FastPix/android-data-exo-player-sdk")
    credentials {
        username = "github-user-name" 
        password = "github-password"
    }
  }
}
```
Add the FastPix Data Core SDK dependencie to your **build.gradle**:
```gradle
dependencies {
    implementation 'io.fastpix.data:exoplayer:1.1.0'
}
```

## Usage 

Make sure ExoPlayer is installed and integrated with your project as part of the FastPix data setup. You can initialize ExoPlayer with a PlayerView or SurfaceView in your Android application to enable seamless functionality. 

### Kotlin

Integrate the following Kotlin code into your application to configure Exoplayer Player with FastPix.

### Globally declare
```Kotlin
import ... 

class VideoPlayerActivity : AppCompatActivity() { 
    private lateinit var exoPlayer: ExoPlayer // Global ExoPlayer instance 
    private lateinit var fastPixDataSDK: FastPixBaseExoPlayer // Global FastPix instance 
} 
```

# Including Custom Data and metadata
**workspace_id** is the only mandatory field. Providing additional metadata can greatly enhance analytics and reporting.  

**CustomerData and CustomOptions :** Create the CustomerPlayerData and CustomerVideoData objects as appropriate for your current playback 
```Kotlin
    override fun onCreate(savedInstanceState: Bundle?) { 
        // Pass player details here (Optional Parameter)
        val playerDataDetails = PlayerDataDetails(
                "player-name",
                "player-version"
        )
    
        /* Data about this video Add or change properties here to customize video metadata such as title,language, etc */ 
        val videoDataDetails =
                VideoDataDetails(
                    UUID.randomUUID().toString(),
                    "Video Title"
                ).apply {
                    videoSeries = "This is video series"
                    videoProducer = "This is video Producer"
                    videoContentType = "This is video Content Type"
                    videoVariant = "This is video Variant"
                    videoLanguage = "This is video Language"
        }
    
        /* Add values for your Custom Dimensions. Up to 10 strings can be set to track your own data */ 
        val customDataDetails = CustomDataDetails()
        customDataDetails.customField1 = "Custom 1"
        customDataDetails.customField2 = "Custom 2"
                ||        ||             || 
                ||        ||             ||    
        customDataDetails.customField9 = "Custom 9"

        fastPixDataSDK = FastPixBaseExoPlayer(
            this,
            playerView = binding.playerView,
            exoPlayer = exoPlayer,
            workSpaceId = "workspace-key",
            viewerId = UUID.randomUUID().toString(),
            videoDataDetails = videoDataDetails, // Optional
            playerDataDetails = playerDataDetails, // Optional
            customDataDetails = customDataDetails // Optional
        )
    } 
```

### Create FastPixBaseExoPlayer
To set up video analytics, create a FastPixBaseExoPlayer object by providing the following parameters: your application's Context (usually the Activity), the ExoPlayer instance, and the CustomerDataEntity and CustomOptions objects that you have prepared.
```Kotlin
        fastPixDataSDK = FastPixBaseExoPlayer(
            this,
            playerView = binding.playerView,
            exoPlayer = exoPlayer,
            workSpaceId = "workspace-key",
            viewerId = UUID.randomUUID().toString(), // viewer-id
        )

```

### XML

Include the XML code below to integrate ExoPlayer with FastPix:

```xml
    <com.google.android.exoplayer2.ui.PlayerView
            android:id="@+id/player_view"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />
```

Finally, when destroying the player, make sure to call the FastPixBaseExoPlayer.release() function to properly release resources.

```kotlin
    override fun onDestroy() { 
      super.onDestroy()
      fastPixDataSDK.release() // Cleanup FastPix tracking 
    } 
```


# Detailed Usage:

For more detailed steps and advanced usage, please refer to the official [FastPix Documentation](https://docs.fastpix.io/docs/exo-player-android).