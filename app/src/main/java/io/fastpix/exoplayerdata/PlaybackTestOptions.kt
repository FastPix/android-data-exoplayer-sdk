package io.fastpix.exoplayerdata

import android.content.Context

class PlaybackTestOptions(context: Context) {

    private val prefs = context.getSharedPreferences("fastpix_sample_playback", Context.MODE_PRIVATE)

    var autoPlay: Boolean
        get() = prefs.getBoolean(AUTO_PLAY, true)
        set(value) = prefs.edit().putBoolean(AUTO_PLAY, value).apply()

    var startMuted: Boolean
        get() = prefs.getBoolean(START_MUTED, false)
        set(value) = prefs.edit().putBoolean(START_MUTED, value).apply()

    var loop: Boolean
        get() = prefs.getBoolean(LOOP, false)
        set(value) = prefs.edit().putBoolean(LOOP, value).apply()

    var controlsOn: Boolean
        get() = prefs.getBoolean(CONTROLS_ON, true)
        set(value) = prefs.edit().putBoolean(CONTROLS_ON, value).apply()

    /** CMCD request data; off by default, see the media source factory in VideoPlayerActivity */
    var cmcd: Boolean
        get() = prefs.getBoolean(CMCD, false)
        set(value) = prefs.edit().putBoolean(CMCD, value).apply()

    private companion object {
        const val AUTO_PLAY = "auto_play"
        const val START_MUTED = "start_muted"
        const val LOOP = "loop"
        const val CONTROLS_ON = "controls_on"
        const val CMCD = "cmcd"
    }
}