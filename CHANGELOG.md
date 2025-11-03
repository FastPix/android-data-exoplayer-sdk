# Changelog

All notable changes to this project will be documented in this file.


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