package io.fastpix.exoplayerdata

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.ExoPlayer

import com.google.android.exoplayer2.ext.ima.ImaAdsLoader
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.CmcdConfiguration
import io.fastpix.data.domain.model.AdDataDetails
import io.fastpix.data.domain.model.ApplicationDataDetails
import io.fastpix.data.domain.model.CustomDataDetails
import io.fastpix.data.domain.model.PlayerDataDetails
import io.fastpix.data.domain.model.VideoDataDetails
import io.fastpix.data.domain.model.ViewerDataDetails
import io.fastpix.exoplayer_data_sdk.FastPixBaseExoPlayer
import io.fastpix.exoplayerdata.R
import io.fastpix.exoplayerdata.databinding.ActivityVideoPlayerBinding
import java.util.Locale
import java.util.UUID

class VideoPlayerActivity : AppCompatActivity() {
    private val binding: ActivityVideoPlayerBinding by lazy {
        ActivityVideoPlayerBinding.inflate(layoutInflater)
    }
    // Google IMA (client-side ads): ad events are forwarded to FastPix for the VAST metadata
    private val adsLoader: ImaAdsLoader by lazy {
        ImaAdsLoader.Builder(this)
            .setAdEventListener { fastPixDataSDK?.onImaAdEvent(it) }
            .setAdErrorListener { fastPixDataSDK?.onImaAdError(it) }
            .build()
    }
    private val exoPlayer: ExoPlayer by lazy {
        // Items with an IMA ad tag play their ads through adsLoader; the rest play as usual
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setLocalAdInsertionComponents({ adsLoader }, binding.playerView)
        // CMCD is sent as request headers, which FastPixBaseExoPlayer reads per request. Off by
        // default: in ExoPlayer 2.19.1, switching to another HLS audio or subtitle track with CMCD on
        // fails playback (IllegalArgumentException in CmcdHeadersFactory). Turn it on only for
        // streams whose tracks are never switched
        if (PlaybackTestOptions(this).cmcd) {
            mediaSourceFactory.setCmcdConfigurationFactory { mediaItem ->
                CmcdConfiguration(UUID.randomUUID().toString(), mediaItem.mediaId, sampleCmcdRequestConfig)
            }
        }
        ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
    }
    private var isFullscreen = false
    private var controlsVisible = true
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var fastPixDataSDK: FastPixBaseExoPlayer? = null
    private var videoModel: DummyData? = null
    private var episodeList: ArrayList<DummyData>? = null
    private var currentEpisodeIndex: Int = 0


    // Flag to track if fullscreen button was just pressed
    private var userTriggeredFullscreen = false

    // Network connectivity monitoring
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasPlayingBeforeNetworkLoss = false
    private var resumeOnStart = false
    private var hadNetworkError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readVideoFromIntent(intent)

        // Allow sensor-based rotation at all times
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        setupPlayer()
        setupControls()
        requestPhoneStatePermission()
        setupBackPressHandler()
        setupNetworkMonitoring()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Check if error is network related
                val isNetworkError = isNetworkRelatedError(error)
                if (isNetworkError) {
                    hadNetworkError = true
                    wasPlayingBeforeNetworkLoss = true
                    // For network errors, show loader instead of error UI
                    binding.loader.isVisible = true
                    binding.tvNetworkWaiting.isVisible = true
                    binding.errorContainer.isVisible = false
                } else {
                    // For non-network errors, show error UI
                    binding.loader.isVisible = false
                    binding.tvNetworkWaiting.isVisible = false
                    binding.errorContainer.isVisible = true
                    binding.errorMessage.text = error.cause?.message
                }
            }
        })
        monitorPlayerThroughFastPix()
    }

    private fun monitorPlayerThroughFastPix() {
        applyPlaybackOptions()

        val videoDataDetails = VideoDataDetails(
            videoId = videoModel?.id,
            videoTitle = videoModel?.id,
            videoSourceUrl = videoModel?.url
        ).apply {
            videoSeries = "video-series"
            videoProducer = "video-producer"
            videoContentType = "video-content-type"
            videoVariant = "video-variant"
            videoLanguage = "video-language"
            videoDrmType = "widevine"
            // Content metadata: declared by the app, sent as is
            videoBrand = "FastPix"
            videoAffiliate = "fastpix-sample"
            videoCreatorId = "creator-001"
            videoDescription = "FastPix Android sample video"
            videoVariant = "Video Variant"
            videoThumbnail = "Video thumbnail"
            videoBrand = "Video brand"
            videoMediaTags = "sample,android,exoplayer"
            videoCodec = "h264"
            videoAudioCodec = "aac"
            videoDynamicRangeType = "SDR"
            // The CDN that serves the video, as the app knows it
            videoCDN = "fastpix"
            // The device's DRM security level. The CDN edge PoP is read from response headers by
            // FastPixBaseExoPlayer; the origin is known only to the backend, so it is not set
            videoDrmLevel = widevineSecurityLevel()
            //...etc
        }
        // Application and page context: pass-through, sent only because the app sets them
        val applicationDataDetails = ApplicationDataDetails(
            applicationName = "FastPix Android Sample",
            applicationVersion = appVersionName(),
            pageType = "video_player",
            contentId = videoModel?.id,
            deviceId = sampleDeviceId(),
        )
        // Viewer details: pass-through
        val viewerDataDetails = ViewerDataDetails(
            viewerPlan = "Premium Monthly",
            viewerPlanCategory = "paid",
            viewerPlanStatus = "active",
        )
        val customDataDetails = CustomDataDetails().apply {
            customField1 = "Custom 1"
            customField2 = "Custom 2"
            customField3 = "Custom 3"
            customField4 = "Custom 4"
            customField5 = "Custom 5"
            customField6 = "Custom 6"
            customField7 = "Custom 7"
            customField8 = "Custom 8"
            customField9 = "Custom 9"
            customField10 = "Custom 10"
        }
        // By Default player sets these things, You don't have to worry about it unless you're not using
        // some wrapper around ExoPlayer
        val playerDataDetails = PlayerDataDetails(
            playerName = "exoplayer",
            playerVersion = "latest-version",
            playerPoster = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=1280"
        )
        fastPixDataSDK = FastPixBaseExoPlayer(
            this, // context
            playerView = binding.playerView, // ExoPlayer StyledPlayerView from XML
            exoPlayer = exoPlayer, // ExoPlayer player
            workSpaceId = "1221148661490417666", // replace with your workspace ID
            enableLogging = true, // payloads in Logcat under EVENT_EMIT_BEFORE
            beaconUrl = "anlytix.app",
            playerDataDetails = playerDataDetails,
            videoDataDetails = videoDataDetails,
            customDataDetails = customDataDetails,
            applicationDataDetails = applicationDataDetails,
            viewerDataDetails = viewerDataDetails,
            adDataDetails = sampleAdDetails(),
            errorClassifier = ::classifyPlayerError,
            // This screen draws its own controls (use_controller = false), and they are always enabled
            // The Controls switch: ExoPlayer's default controls report true, this screen's custom
            // controls false
            controlsEnabled = { controlsOn },
            autoPlay = PlaybackTestOptions(this).autoPlay,
        )
        videoModel?.adTagUrl?.let { fastPixDataSDK?.onAdsRequested(it) }
    }

    /**
     * Example classification: HTTP 403 and 451 from the CDN are treated as business rules
     * (entitlement, geo-block); other HTTP errors are technical faults; anything else is unclassified
     */
    private fun classifyPlayerError(error: PlaybackException): Boolean? {
        val httpError = error.cause as? HttpDataSource.InvalidResponseCodeException ?: return null
        return httpError.responseCode == 403 || httpError.responseCode == 451
    }

    private fun appVersionName(): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

    /** The media item for [video], with IMA ads when it has an ad tag */
    private fun mediaItemWithAds(video: DummyData?): MediaItem =
        MediaItem.Builder()
            .setUri(video?.url.orEmpty())
            // A bundled playlist has no HTTP content type to tell ExoPlayer it is HLS
            .apply { if (video?.url?.startsWith("asset:") == true) setMimeType(MimeTypes.APPLICATION_M3U8) }
            // ExoPlayer sends the media ID as CMCD content ID (cid)
            .apply { video?.id?.let(::setMediaId) }
            .apply {
                video?.adTagUrl?.let {
                    setAdsConfiguration(MediaItem.AdsConfiguration.Builder(Uri.parse(it)).build())
                }
            }
            .build()

    private fun setupPlayer() {
        // Set player to PlayerView
        binding.playerView.player = exoPlayer
        adsLoader.setPlayer(exoPlayer)
        // Create MediaItem from URL, with ads
        val mediaItem = mediaItemWithAds(videoModel)
        // Listen before preparing, so the first buffering already shows the loader
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        binding.loader.visibility = View.VISIBLE
                        binding.nextEpisodeOverlay.visibility = View.GONE
                        Log.d(TAG, "Player state: BUFFERING")
                    }

                    Player.STATE_READY -> {
                        binding.loader.visibility = View.GONE
                        binding.tvNetworkWaiting.visibility = View.GONE
                        binding.nextEpisodeOverlay.visibility = View.GONE
                        if (hadNetworkError) hadNetworkError = false
                        updateDuration()
                    }

                    Player.STATE_ENDED -> {
                        binding.loader.visibility = View.GONE
                        if (hasNext()) {
                            binding.nextEpisodeOverlay.visibility = View.VISIBLE
                            showControls()
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton(isPlaying)
                // Hide loader when video starts playing (especially after network recovery)
                if (isPlaying) {
                    binding.loader.visibility = View.GONE
                    binding.tvNetworkWaiting.visibility = View.GONE
                    if (hadNetworkError) {
                        hadNetworkError = false
                    }
                }
            }

            // Audio and quality pickers appear only when there is something to pick
            override fun onTracksChanged(tracks: Tracks) {
                binding.ivAudioTracks.isVisible = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                    .sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } } > 1
                binding.ivVideoQuality.isVisible =
                    tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
                val subtitlesOn = tracks.groups.any {
                    it.type == C.TRACK_TYPE_TEXT && it.isSelected &&
                            it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED == 0
                }
                binding.ivSubtitles.alpha = if (subtitlesOn) 1f else 0.5f
            }

            override fun onVolumeChanged(volume: Float) = updateVolumeUi()

            override fun onEvents(player: Player, events: Player.Events) = updateLoader()
        })
        // Set media item and prepare
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = PlaybackTestOptions(this).autoPlay
    }

    private fun setupControls() {
        binding.ivPlayPause.setOnClickListener {
            exoPlayer.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.currentPosition >= player.duration) {
                        player.seekTo(0)
                    }
                    player.play()
                }
            }
            startHideControlsTimer()
        }
        binding.ivBackwardSeek.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.ivForwardSeek.setOnClickListener { seekBy(SEEK_STEP_MS) }

        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer.let { player ->
                        val duration = player.duration
                        if (duration != C.TIME_UNSET) {
                            val position = (progress * duration / 100).toLong()
                            player.seekTo(position)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                controlsHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                startHideControlsTimer()
            }
        })

        // player_muted, player_volume_level
        binding.ivVolume.setOnClickListener { toggleMute() }
        binding.sbVolumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) exoPlayer.volume = progress / 100f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                controlsHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                startHideControlsTimer()
            }
        })

        binding.ivSubtitles.setOnClickListener { chooseSubtitles() }
        binding.ivAudioTracks.setOnClickListener { chooseAudioTrack() }
        binding.ivVideoQuality.setOnClickListener { chooseQuality() }
        binding.enterPipMode.setOnClickListener { enterPictureInPicture() }
        binding.ivMore.setOnClickListener { choosePlaybackSpeed() }

        binding.prevEpisode.setOnClickListener { playPreviousEpisode() }
        binding.nextEpisode.setOnClickListener { playNextEpisode() }
        binding.playNextEpisodeButton.setOnClickListener { playNextEpisode() }

        binding.ivFullScreen.setOnClickListener { toggleFullscreen() }
        binding.playerView.setOnClickListener { toggleControls() }
        // The default controls' fullscreen button drives the same fullscreen as the custom one
        binding.playerView.setFullscreenButtonClickListener { toggleFullscreen() }

        updateVolumeUi()
        layoutForOrientation()
        updateNextEpisodeVisibility()
        startHideControlsTimer()
    }

    private fun hasNext(): Boolean {
        val list = episodeList ?: return false
        return currentEpisodeIndex + 1 < list.size
    }

    /** Spinner while the player is loading: before the first frame, on rebuffering, and after a seek */
    private fun updateLoader() {
        val loading = exoPlayer.playbackState == Player.STATE_BUFFERING ||
                (exoPlayer.playbackState == Player.STATE_IDLE && exoPlayer.playWhenReady && !binding.errorContainer.isVisible)
        binding.loader.isVisible = loading
    }

    private fun hasPrevious(): Boolean = currentEpisodeIndex > 0

    private fun updateNextEpisodeVisibility() {
        binding.nextEpisode.visibility = if (hasNext()) View.VISIBLE else View.INVISIBLE
        binding.prevEpisode.visibility = if (hasPrevious()) View.VISIBLE else View.INVISIBLE
    }

    private fun playPreviousEpisode() {
        if (!hasPrevious()) return
        currentEpisodeIndex--
        videoModel = episodeList?.getOrNull(currentEpisodeIndex) ?: return
        switchToCurrentVideo()
    }

    /** Seeks within the content; inside an ad break the SDK drops the seek, as Web does */
    private fun seekBy(deltaMs: Long) {
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration))
        startHideControlsTimer()
    }

    private fun playNextEpisode() {
        if (!hasNext()) return
        currentEpisodeIndex++
        videoModel = episodeList?.getOrNull(currentEpisodeIndex) ?: return
        switchToCurrentVideo()
    }

    /**
     * This activity is singleTask: opening another video while one is playing, including from
     * picture-in-picture, reuses this screen and its single player instead of starting a second
     * one. The analytics SDK tracks one view at a time, so two players would mix their events
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readVideoFromIntent(intent)
        if (videoModel != null) switchToCurrentVideo()
    }

    private fun readVideoFromIntent(intent: Intent) {
        videoModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("video_model", DummyData::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("video_model")
        }
        episodeList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("video_list", DummyData::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("video_list")
        }
        currentEpisodeIndex =
            intent.getIntExtra("current_index", 0).coerceIn(0, (episodeList?.size ?: 1) - 1)
        if (episodeList == null && videoModel != null) {
            episodeList = arrayListOf(videoModel!!)
        }
        videoModel = episodeList?.getOrNull(currentEpisodeIndex) ?: videoModel
    }

    /** Ends the current view (viewCompleted) and starts a new one for [videoModel] on the same player */
    private fun switchToCurrentVideo() {
        val video = videoModel ?: return
        fastPixDataSDK?.release()
        exoPlayer.setMediaItem(mediaItemWithAds(video))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = PlaybackTestOptions(this).autoPlay
        monitorPlayerThroughFastPix()
        binding.errorContainer.isVisible = false
        binding.nextEpisodeOverlay.visibility = View.GONE
        updateNextEpisodeVisibility()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    exitFullscreen()
                } else {
                    finish()
                }
            }
        })
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    private fun enterFullscreen() {
        isFullscreen = true
        userTriggeredFullscreen = true
        // Lock to landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // After a delay, allow sensor-based rotation and clear the flag
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFullscreen) {  // Only allow sensor if still in fullscreen
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
            userTriggeredFullscreen = false
        }, 5000)
        hideSystemUI()
        layoutForOrientation()
    }

    private fun exitFullscreen() {
        isFullscreen = false
        userTriggeredFullscreen = true
        // Lock to portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // After a delay, allow sensor-based rotation and clear the flag
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFullscreen) {  // Only allow sensor if still not in fullscreen
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
            userTriggeredFullscreen = false
        }, 5000)
        showSystemUI()
        layoutForOrientation()
    }

    /**
     * Portrait: a 16:9 player box, centred. Fullscreen: the player fills the screen.
     * player_width/player_height on the next event follow the box
     */
    private fun layoutForOrientation() {
        val params = binding.playerContainer.layoutParams
        params.height = if (isFullscreen) {
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            resources.displayMetrics.widthPixels * 9 / 16
        }
        binding.playerContainer.layoutParams = params
        binding.ivFullScreen.setImageResource(if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_full_screen)
    }


    @SuppressLint("InlinedApi")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    @SuppressLint("InlinedApi")
    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.show(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /** Custom controls only: ExoPlayer's default controls show and hide themselves */
    private fun toggleControls() {
        if (controlsOn) return
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        if (controlsOn) return
        controlsVisible = true
        setControlsVisible(true)
        startHideControlsTimer()
    }

    private fun hideControls() {
        controlsVisible = false
        setControlsVisible(false)
    }

    private fun setControlsVisible(visible: Boolean) {
        binding.playerControls.isVisible = visible
        binding.playerTopControls.isVisible = visible
        binding.episodeControls.isVisible = visible
    }

    private fun startHideControlsTimer() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, 3000) // Hide after 3 seconds
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.ivPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
    }

    private fun updateDuration() {
        exoPlayer.let { player ->
            val duration = player.duration
            if (duration != C.TIME_UNSET) {
                binding.tvEndTime.text = formatTime(duration)
                binding.sbProgress.max = 100

                // Update progress periodically
                updateProgress()
            }
        }
    }

    private fun updateProgress() {
        exoPlayer.let { player ->
            val currentPosition = player.currentPosition
            val duration = player.duration

            if (duration != C.TIME_UNSET) {
                val progress = (currentPosition * 100 / duration).toInt()
                binding.sbProgress.progress = progress
                binding.tvStartTime.text = formatTime(currentPosition)

                // Schedule next update
                controlsHandler.postDelayed({ updateProgress() }, 1000)
            }
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000

        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Pause in onStop, not onPause: onPause also runs when entering picture-in-picture, where
     * playback must continue; closing the PiP window calls onStop and pauses here
     */
    override fun onStop() {
        super.onStop()
        resumeOnStart = exoPlayer.playWhenReady
        exoPlayer.pause()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // No controls of either kind inside the PiP window
        binding.playerView.useController = controlsOn && !isInPictureInPictureMode
        setControlsVisible(!controlsOn && !isInPictureInPictureMode)
    }

    // ---------------------------------------------------------------- player controls
    // Player state is sampled on every event, so each change shows up on the next event:
    // a pause, a seek, or the pulse sent every 10 s while playing.

    private var volumeBeforeMute = 1f
    private val playbackSpeeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var controlsOn = true

    /**
     * The video list's switches, applied before the view begins so viewBegin carries them:
     * player_default_muted_on, player_loop_on, player_controls_on
     */
    private fun applyPlaybackOptions() {
        val options = PlaybackTestOptions(this)
        exoPlayer.volume = if (options.startMuted) 0f else 1f
        exoPlayer.repeatMode = if (options.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        // On: ExoPlayer's default controls (player_controls_on = true). Off: this screen's custom
        // controls (player_controls_on = false)
        controlsOn = options.controlsOn
        binding.playerView.useController = controlsOn
        controlsVisible = !controlsOn
        setControlsVisible(!controlsOn)
        if (!controlsOn) startHideControlsTimer()
        updateVolumeUi()
    }

    /** The volume icon and slider follow the player's volume, whoever changed it */
    private fun updateVolumeUi() {
        val volume = exoPlayer.volume
        binding.ivVolume.setImageResource(if (volume == 0f) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        binding.sbVolumeSlider.progress = (volume * 100).toInt()
    }

    /** player_audio_language_code: every audio track of the stream, across its groups */
    private fun chooseAudioTrack() {
        val tracks = tracksOf(C.TRACK_TYPE_AUDIO)
        if (tracks.size < 2) return
        AlertDialog.Builder(this)
            .setTitle("Audio")
            .setItems(tracks.map { trackLabel(it) }.toTypedArray()) { _, which ->
                val track = tracks[which]
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
                    .build()
            }
            .show()
    }


    /**
     * Renditions: pins a quality, or goes back to automatic. A switch sends variantChanged with
     * the rendition fields, the shift counts and view_last_rendition_changed_time
     */
    private fun chooseQuality() {
        val group = exoPlayer.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
        if (group == null) {
            Toast.makeText(this, "This stream has a single rendition", Toast.LENGTH_SHORT).show()
            return
        }
        val indices = (0 until group.length).filter { group.isTrackSupported(it) }
            .sortedByDescending { group.getTrackFormat(it).bitrate }
        val labels = listOf("Auto") + indices.map {
            val format = group.getTrackFormat(it)
            "${format.height}p · ${format.bitrate / 1000} kbps"
        }
        AlertDialog.Builder(this)
            .setTitle("Quality")
            .setItems(labels.toTypedArray()) { _, which ->
                val builder = exoPlayer.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                } else {
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices[which - 1]))
                }
                exoPlayer.trackSelectionParameters = builder.build()
            }
            .show()
    }

    /**
     * viewer_connection_speed: with this permission the SDK reads the cellular radio type
     * (2g, 3g, LTE, 4g, 5g); without it, the bandwidth estimate (2g, 3g, LTE)
     */
    private fun requestPhoneStatePermission() {
        val permission = android.Manifest.permission.READ_PHONE_STATE
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 0)
        }
    }

    /**
     * The view's ad details, as an app would pass them from its ad server or VAST. Sample values,
     * for videos with an ad tag only; IMA adds each ad's own VAST metadata to its ad events
     */
    private fun sampleAdDetails(): AdDataDetails? {
        if (videoModel?.adTagUrl == null) return null
        return AdDataDetails(
            adId = "sample-ad-1001",
            creativeId = "sample-creative-2001",
            advertiserName = "FastPix Sample Advertiser",
            partner = "sample-partner",
            dealId = "sample-deal-3001",
            clickUrl = "https://www.fastpix.io/?utm_source=sample-ad",
            skipTimeOffset = 5.0,
            wrapperAdIds = listOf("wrapper-ad-1", "wrapper-ad-2"),
            wrapperCreativeIds = listOf("wrapper-creative-1", "wrapper-creative-2"),
            wrapperSystems = listOf("SampleAdServer", "SampleSSP"),
        )
    }

    /** A stable ID for this install, as an app would pass its own device ID */
    private fun sampleDeviceId(): String {
        val prefs = getSharedPreferences("fastpix_sample", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    }

    /** The device's Widevine security level ("L1" or "L3"), as the app's DRM setup would report it */
    private fun widevineSecurityLevel(): String? = try {
        val drm = android.media.MediaDrm(C.WIDEVINE_UUID)
        try {
            drm.getPropertyString("securityLevel")
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close() else @Suppress("DEPRECATION") drm.release()
        }
    } catch (e: Exception) {
        null
    }

    /** player_muted, player_volume_level */
    private fun toggleMute() {
        if (exoPlayer.volume > 0f) {
            volumeBeforeMute = exoPlayer.volume
            exoPlayer.volume = 0f
        } else {
            exoPlayer.volume = volumeBeforeMute.takeIf { it > 0f } ?: 1f
        }
        startHideControlsTimer()
    }

    /** player_playback_rate */
    private fun choosePlaybackSpeed() {
        val current = exoPlayer.playbackParameters.speed
        val labels = playbackSpeeds.map { if (it == current) "${it}x ✓" else "${it}x" }
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setItems(labels.toTypedArray()) { _, which -> exoPlayer.setPlaybackSpeed(playbackSpeeds[which]) }
            .show()
    }

    /**
     * player_subtitles_on, player_subtitles_language_code: every subtitle track of the stream, or
     * Off. HLS exposes each subtitle rendition as its own track group, so all groups are listed
     */
    private fun chooseSubtitles() {
        val tracks = tracksOf(C.TRACK_TYPE_TEXT)
        if (tracks.isEmpty()) {
            Toast.makeText(this, "This stream has no subtitles", Toast.LENGTH_SHORT).show()
            return
        }
        // Off whenever no viewer-chosen track plays: switched off, or never turned on (forced-only)
        val textOff = tracks.none { it.isSelected && it.format.selectionFlags and C.SELECTION_FLAG_FORCED == 0 }
        val labels = listOf(if (textOff) "Off ✓" else "Off") + tracks.map { trackLabel(it) }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(labels.toTypedArray()) { _, which ->
                val builder = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                if (which == 0) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val track = tracks[which - 1]
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
                }
                exoPlayer.trackSelectionParameters = builder.build()
            }
            .show()
    }

    /** One playable track: its group and its index in it */
    private data class TrackChoice(val group: Tracks.Group, val index: Int) {
        val format get() = group.getTrackFormat(index)
        val isSelected get() = group.isTrackSelected(index)
    }

    /** Every supported track of [type], across all its groups */
    private fun tracksOf(type: Int): List<TrackChoice> =
        exoPlayer.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { TrackChoice(group, it) }
        }

    /** "English · en-US", plus channels and codec for audio, "forced" for forced subtitles, ✓ when playing */
    private fun trackLabel(track: TrackChoice): String {
        val format = track.format
        val language = format.language?.let { Locale.forLanguageTag(it).displayName.takeIf { name -> name.isNotBlank() } }
        val parts = mutableListOf<String>()
        format.label?.let(parts::add)
        language?.takeIf { it !in parts }?.let(parts::add)
        if (format.channelCount > 0) parts += "${format.channelCount}ch"
        format.sampleMimeType?.substringAfter('/')?.takeIf { track.group.type == C.TRACK_TYPE_AUDIO }?.let { parts += it }
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "forced"
        val label = parts.joinToString(" · ").ifEmpty { "Track ${track.index + 1}" }
        return if (track.isSelected) "$label ✓" else label
    }

    /** player_is_pip (stays true for the rest of the view once seen) */
    private fun enterPictureInPicture() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "Picture-in-picture isn't supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        } else {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        controlsHandler.removeCallbacks(hideControlsRunnable)
        unregisterNetworkCallback()
        adsLoader.setPlayer(null)
        adsLoader.release()
        exoPlayer.release()
        fastPixDataSDK?.release()
    }

    override fun onResume() {
        super.onResume()
        // Only resumes what was playing when the screen stopped, so Auto play off stays paused
        if (resumeOnStart) exoPlayer.play()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Sync fullscreen state with actual orientation
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                if (!isFullscreen) {
                    isFullscreen = true
                    hideSystemUI()
                }
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                if (isFullscreen) {
                    isFullscreen = false
                    showSystemUI()
                }
            }
        }
        layoutForOrientation()
    }

    /**
     * Sets up network connectivity monitoring to automatically resume playback
     * when internet connection is restored after a network loss
     */
    private fun setupNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)

                // Resume playback on main thread when network is restored
                runOnUiThread {
                    handleNetworkRestored()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Network lost")

                runOnUiThread {
                    // Track if player was playing when network was lost
                    // Don't show loader yet - video might still play from buffer
                    // Loader will be shown only when player actually encounters an error
                    if (exoPlayer.isPlaying) {
                        wasPlayingBeforeNetworkLoss = true
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val hasInternet =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Handles network restoration by resuming playback if it was interrupted
     * due to connectivity issues
     */
    private fun handleNetworkRestored() {
        // If player had a network error or was playing before network loss, try to resume
        if (wasPlayingBeforeNetworkLoss || hadNetworkError) {
            try {
                // Show loading indicator while re-connecting
                binding.loader.isVisible = true

                // Retry preparation and resume playback
                exoPlayer.prepare()
                exoPlayer.play()
                wasPlayingBeforeNetworkLoss = false
                Log.d(TAG, "Attempting to resume playback after network restoration")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume playback", e)
                // Keep showing loader - player state listeners will handle hiding it
                // or showing error if the retry also fails
            }
        }
    }

    /**
     * Checks if a PlaybackException is network-related
     */
    private fun isNetworkRelatedError(error: PlaybackException): Boolean {
        // Check for common network-related error types
        val errorMessage = error.message?.lowercase() ?: ""
        val causeMessage = error.cause?.message?.lowercase() ?: ""

        val networkKeywords = listOf(
            "network", "connection", "timeout", "unreachable",
            "unable to connect", "failed to connect", "no internet",
            "http", "socket", "dns"
        )

        return networkKeywords.any { keyword ->
            errorMessage.contains(keyword) || causeMessage.contains(keyword)
        } || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    }

    /**
     * Unregisters network callback to prevent memory leaks
     */
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "Network callback unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val SEEK_STEP_MS = 10_000L

        /** CMCD request settings, used when the cmcd test option is on */
        private val sampleCmcdRequestConfig = object : CmcdConfiguration.RequestConfig {
            // Requested maximum throughput (rtp) is the app's choice; this sample asks for twice
            // the bitrate of the object being requested
            override fun getRequestedMaximumThroughputKbps(throughputKbps: Int): Int = throughputKbps * 2
        }
    }
}
