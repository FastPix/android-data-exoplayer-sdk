# Changelog

All notable changes to this project will be documented in this file.

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