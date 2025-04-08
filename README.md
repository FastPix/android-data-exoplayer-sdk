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
        username = "Github_User_Name"  // Your Github account user name 
        password = "Github_PAT" // Your (PAT) Personal access token Get It from you Github account 
    }
  }
}
```
Add the FastPix Data Core SDK dependencie to your **build.gradle**:
```gradle
dependencies {
    implementation 'io.fastpix.data:exoplayer:1.0.0'
}
```

## Usage 

Make sure ExoPlayer is installed and integrated with your project as part of the FastPix data setup. You can initialize ExoPlayer with a PlayerView or SurfaceView in your Android application to enable seamless functionality. 

### Kotlin

Integrate the following Kotlin code into your application to configure Exoplayer Player with FastPix.

### Globally declare
``` Kotlin
import ... 

class VideoPlayerActivity : AppCompatActivity() { 
    private lateinit var exoPlayer: ExoPlayer // Global ExoPlayer instance 
    private lateinit var fastPixBaseExoPlayer: FastPixBaseExoPlayer // Global FastPix instance 
} 
```

# Including Custom Data and metadata
**workspace_id** is the only mandatory field. Providing additional metadata can greatly enhance analytics and reporting.  

**CustomerData and CustomOptions :** Create the CustomerPlayerData and CustomerVideoData objects as appropriate for your current playback 
``` Kotlin
override fun onCreate(savedInstanceState: Bundle?) { 
    val customerPlayerDataEntity = CustomerPlayerDataEntity() 
    customerPlayerDataEntity.workspaceKey = Constants.wsKey 
    customerPlayerDataEntity.playerName = "Exoplayer" 
 
/* Data about this video Add or change properties here to customize video metadata such as title,language, etc */ 
    val customerVideoDataEntity = CustomerVideoDataEntity() 
    customerVideoDataEntity.videoId = “id” 
    customerVideoDataEntity.videoTitle = “title” 
    customerVideoDataEntity.videoSourceUrl = “urlSting” 
    customerVideoDataEntity.videoLanguageCode = "Lang" 
    customerVideoDataEntity.videoProducer = "Producer" 
    customerVideoDataEntity.videoContentType = “itemType” 
 
/* Add values for your Custom Dimensions. Up to 10 strings can be set to track your own data */ 
    val customDataEntity = CustomDataEntity()
    customDataEntity.customData1 = "data1" 
    customDataEntity.customData2 = "data2" 
    customDataEntity.customData3 = "data3" 
        ||        ||             || 
        ||        ||             ||    
    customDataEntity.customData10 = "data10" 

/* You need to pass the view session ID which is only used with CMCD, if not used CMCd you need to create empty CustomerViewDataEntity*/ 
    val customerViewDataEntity = CustomerViewDataEntity() 
    customerViewDataEntity.viewSessionId = UUID.randomUUID().toString() 
    
/* CustomerDataEntity binding with customerPlayerDataEntity, customerVideoDataEntity, customerViewDataEntity */ 
    val customerDataEntity = CustomerDataEntity( 
    customerPlayerDataEntity, 
    customerVideoDataEntity, 
    customerViewDataEntity) 

// customOptions set customized Domain or else create empty CustomOptions
    val customOptions = CustomOptions() 
    customOptions.beaconDomain = "domain.com" //by defalt set up with "metrix.ws"
} 
```

### Create FastPixBaseExoPlayer
To set up video analytics, create a FastPixBaseExoPlayer object by providing the following parameters: your application's Context (usually the Activity), the ExoPlayer instance, and the CustomerDataEntity and CustomOptions objects that you have prepared.
```Kotlin
fastPixBaseExoPlayer = FastPixBaseExoPlayer( 
    this, 
    exoPlayer, 
    customerDataEntity, 
    customOptions 
) 

```
If your PlayerView hasn’t been configured yet, make sure to set it up now. This step is crucial for capturing various viewer context values and accurately tracking the dimensions of the video player.
```Kotlin
fastPixBaseExoPlayer.setPlayerView(playerView) 
```
### XML

Include the XML code below to integrate ExoPlayer with FastPix:

```xml
<com.google.android.exoplayer2.ui.PlayerView
        android:id="@+id/player_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@android:color/black"
        app:auto_show="true"
        app:hide_on_touch="true"
        app:show_subtitle_button="true"
        app:surface_type="surface_view" />
```

Finally, when destroying the player, make sure to call the FastPixBaseExoPlayer.release() function to properly release resources.

```kotlin
override fun onDestroy() { 
  super.onDestroy() 
  fastPixBaseExoPlayer.release() // Cleanup FastPix tracking 
  fastPixBaseExoPlayer = null
} 
```

# Changing Video Streams

Effective video view tracking is essential for monitoring multiple videos in the same player within your Android application. You should reset tracking in two key scenarios: when loading a new source (such as in video series or episodic content). 

This is done by calling fastPixBaseExoPlayer.videoChange(CustomerVideoData), which will clear all previous video data and reset all metrics for the new video view. Refer to the Metadata section for the list of video details you can provide. While you can include any metadata when changing the video, you should primarily update the values that start with "video". 

It's best to update the video information immediately after instructing the player to load the new source. 

```kotlin
fastPixBaseExoPlayer.videoChange(CustomerVideoData) 
```

### Configure error tracking preferences: 

FastPix’s integration with ExoPlayer automatically tracks fatal errors that occur within the player. However, if a fatal error occurs outside of ExoPlayer’s context and you want to log it with FastPix, you can do so by calling the fastPixBaseExoPlayer.error method. 

Note that fastPixBaseExoPlayer.error(FastPixErrorException e) can be used with or without automatic error tracking. If your application includes retry logic to recover from ExoPlayer errors, you may want to disable automatic error tracking by doing the following: 
```kotlin
fastPixBaseExoPlayer.setAutomaticErrorTracking(false)// disable 

fastPixBaseExoPlayer.setAutomaticErrorTracking(true)// enable 
```


# Detailed Usage:

For more detailed steps and advanced usage, please refer to the official [FastPix Documentation](https://docs.fastpix.io/docs/exo-player-android).
