# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0]

### Added
- **Integration with ExoPlayer**:
    - Enabled video performance tracking using FastPix Data SDK, supporting ExoPlayer streams with user engagement metrics, playback quality monitoring, and real-time diagnostics.
    - Provides robust error management and reporting capabilities for seamless ExoPlayer video performance tracking.
    - Allows customizable behavior, including options to disable data collection, respect Do Not Track settings, and configure advanced error tracking with automatic error handling.
    - Includes support for custom metadata, enabling users to pass optional fields such as video_id, video_title, video_duration, and more.
    - Introduced event tracking for onPlayerStateChanged and onTracksChanged to handle seamless metadata updates during playback transitions.
