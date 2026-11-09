# Changelog

All notable changes to this project will be documented in this file.

## [1.1.5]
### Upgrade
- Upgrades Core SDK to 1.3.2
- Adds the ExoPlayer IMA extension (`extension-ima` 2.19.1) as a dependency
- Builds with Gradle 9.7.1, Android Gradle Plugin 9.4.1 and Kotlin 2.3.21 (JDK 17 to 25)

### Added
- **Ad tracking (Google IMA)**:
  - Forward IMA events with `onImaAdEvent` and `onImaAdError`, and call `onAdsRequested` with the ad tag
  - Ad breaks, ads and their media URLs are read from the player, so ad time is kept out of content metrics
  - New `adDataDetails` parameter for the view's ad details from your ad server or VAST
- **Playback and rendition fields**: rendition name, dynamic range (SDR, PQ, HLG), audio codec and bitrate, program date time for live streams, subtitle and audio language, picture-in-picture, fullscreen, mute, volume, playback rate and looping
- **Request fields**: CMCD data from each request (when CMCD is enabled on the player) and the CDN edge PoP from response headers
- **Error classification**: new `errorClassifier` parameter to mark a playback error as a business rule (for example geo-blocking) or a technical fault
- New `applicationDataDetails` and `viewerDataDetails` parameters for app, page and viewer context
- New `controlsEnabled` and `autoPlay` parameters for apps that draw their own controls or start playback themselves

### Changed
- Reports the software name as `fastpix-exoplayer`. Earlier versions reported `fastpix-media3`, so filter on both to see older views
- Restores the bandwidth data capture in `onLoadCanceled`, so cancelled requests report their bandwidth details again
- Sends each ad's impression once. IMA's `STARTED`, the ad's first frame and playback starting each used to send one
- Stops sending a pause after `ended` when a video plays to its end
- Reports CMCD version 1 when a request carries CMCD without a version, since ExoPlayer leaves out `v=1` as the CMCD spec allows
- Calling `release()` more than once, even from two threads at once, is now harmless. Before, a second call could end the view of another player tracked since
- Apps built against 1.1.4 keep working without recompiling: the 1.1.4 constructor is kept, hidden, beside the new one with the added optional parameters
- Compiled for Kotlin 2.1 and newer, the same as FastPix Core, so apps do not need the Kotlin 2.3 the SDK is built with
- `FastPixBaseExoPlayer.ChunkRendition` and `ChunkDownloadData` stay public but are deprecated; the SDK no longer uses them
- Restructures `FastPixBaseExoPlayer` into internal components for events, media, ads and network tracking. The public API is unchanged, so existing integrations keep working
- Adds unit tests for every component

## [1.1.4]
- Upgrades Core SDK to 1.3.1
- Removes synchronous bandwidth data capture and storage in `onLoadCanceled` to streamline event dispatching.

## [1.1.3]
- Upgrades Core SDK to 1.3.0

## [1.1.2]
Improves pulse analytics dispatch behavior during playback state transitions and updates SDK versions/documentation.

### General
- Updates `core` SDK version to `1.2.9` in `libs.versions.toml`.
- Updates `FastPixExoplayerLibraryInfo` SDK version constant to `1.1.2`.

### FastPixBaseExoPlayer
- Ensures pulse events are scheduled on `viewBegin`, `play`, and `buffering` instead of being canceled.
- Prevents unnecessary pulse cancellation on `playerReady`, `seeking`, and `buffered`.
- Updates `seeked` handling to cancel pulse events only when playback is not active, and keep pulse scheduling when playback continues.
- Stops scheduling pulse events on `variantChanged` to avoid unintended heartbeat behavior during rendition switches.

## [1.1.1]
Updates versions, refactors `FastPixBaseExoPlayer` to improve event handling, and enhances documentation.

### General
- Updates `core` SDK version to `1.2.7` in `libs.versions.toml`.
- Updates `exoplayer-data-sdk` Maven publication version to `1.1.1`.
- Configures `settings.gradle.kts` to load GitHub credentials from `local.properties`.

### FastPixBaseExoPlayer
- Refactors SDK initialization to use `FastPixAnalytics` singleton.
- Implements a coroutine-based pulse event system for periodic analytics heartbeats.
- Adds comprehensive state tracking for playback (playing, buffering, seeking, ended).
- Improves bandwidth and chunk load tracking, including better handling of canceled and failed requests.
- Ensures proper resource cleanup in `release()` by canceling background jobs and clearing state.
- Adds `FastPixExoplayerLibraryInfo` to track SDK name and version.

### Documentation
- Overhauls `README.md` with updated requirements, simplified installation steps, and clearer Kotlin integration examples.
- Adds version `1.1.1` to `CHANGELOG.md`.

## [1.1.0]

### Changed
- **Major Code Optimization and Refactoring**:
- Comprehensive code refactoring for improved maintainability and performance.
- Optimized internal components and dependencies for better efficiency.
- Enhanced code structure and organization across the SDK.
- Improved overall stability and reduced technical debt.

## [1.0.0]

### Added
- **Integration with ExoPlayer**:
  - Enabled video performance tracking using FastPix Data SDK, supporting ExoPlayer streams with user engagement metrics, playback quality monitoring, and real-time diagnostics.
  - Provides robust error management and reporting capabilities for seamless ExoPlayer video performance tracking.
  - Allows customizable behavior, including options to disable data collection, respect Do Not Track settings, and configure advanced error tracking with automatic error handling.
  - Includes support for custom metadata, enabling users to pass optional fields such as video_id, video_title, video_duration, and more.
  - Introduced event tracking for onPlayerStateChanged and onTracksChanged to handle seamless metadata updates during playback transitions.