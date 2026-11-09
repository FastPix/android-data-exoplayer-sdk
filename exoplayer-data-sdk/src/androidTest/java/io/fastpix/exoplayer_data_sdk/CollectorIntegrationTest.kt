package io.fastpix.exoplayer_data_sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import io.fastpix.data.FastPixAnalytics
import io.fastpix.data.domain.model.VideoDataDetails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
/**
 * The public collector wired to a real player and the real FastPix Core 1.3.2, on a device. Core's
 * events are read back from this app's own log, where Core prints each one when logging is on
 */
@RunWith(AndroidJUnit4::class)
class CollectorIntegrationTest {

    private fun newCollector(videoId: String, play: Boolean = false): FastPixBaseExoPlayer = main {
        val player = ExoPlayer.Builder(context).build()
        val view = StyledPlayerView(context).apply { this.player = player }
        val collector = FastPixBaseExoPlayer(
            context, view, player,
            workSpaceId = "instrumented-test",
            enableLogging = true,
            videoDataDetails = VideoDataDetails(videoId = videoId, videoTitle = videoId),
        )
        cleanup += { collector.release(); player.release() }
        if (play) {
            player.setMediaItem(MediaItem.fromUri(STREAM))
            player.prepare()
            player.playWhenReady = true
        }
        collector
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val cleanup = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        main { cleanup.reversed().forEach { runCatching(it) } }
        cleanup.clear()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun tracked(collector: Any): Boolean = main {
        FastPixAnalytics.getSDK()?.isTracking(collector as io.fastpix.data.domain.listeners.PlayerListener) == true
    }

    /**
     * The events Core emitted for [videoId]'s view, in order, read from this app's own log. Core
     * prints most events with the video ID and the view's ID (veid); viewCompleted, sent while it
     * releases, only with the view's ID, so the view is found first and its events collected
     */
    private fun events(videoId: String): List<String> {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "raw", "-s", "FastPixAnalytics:D", "FastPixDataSDK:D")
        )
        val lines = process.inputStream.bufferedReader().readLines().filter { "EVENT_EMIT_BEFORE: event=" in it }
        val viewIds = lines.filter { "videoId=$videoId " in it }.mapNotNull { VIEW_ID.find(it)?.groupValues?.get(1) }.toSet()
        return lines.filter { line -> VIEW_ID.find(line)?.groupValues?.get(1) in viewIds }
            .mapNotNull { EVENT.find(it)?.groupValues?.get(1) }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out after ${timeoutMs}ms", System.currentTimeMillis() < deadline)
            Thread.sleep(250)
        }
    }

    private fun id(name: String) = "it-$name-${System.nanoTime()}"

    @Test
    fun playbackEventsReachCoreInOrderAndADoubleReleaseCompletesTheViewOnce() {
        val videoId = id("playback")
        val collector = newCollector(videoId, play = true)
        waitFor(30_000) { events(videoId).let { "playing" in it && "requestCompleted" in it } }

        main {
            collector.release()
            collector.release()
        }
        waitFor(5_000) { "viewCompleted" in events(videoId) }
        Thread.sleep(1_000)

        val events = events(videoId)
        // Request events (and rendition switches, pulses) may come at any point; playback events keep their order
        val playback = events.filter { it !in UNORDERED }
        assertEquals(events.toString(), listOf("viewBegin", "playerReady", "play", "playing"), playback.take(4))
        assertTrue(events.toString(), "requestCompleted" in events)
        assertEquals(1, events.count { it == "viewCompleted" })
        assertEquals("viewCompleted", events.last())
        assertFalse(tracked(collector))
    }

    @Test
    fun aSecondPlayerWhileOneIsTrackedStaysUntrackedAndDoesNotEndTheFirstView() {
        val firstId = id("first")
        val secondId = id("second")
        val first = newCollector(firstId)
        val second = newCollector(secondId)
        assertTrue(tracked(first))
        assertFalse(tracked(second))

        main { second.release() }
        Thread.sleep(1_000)
        assertTrue(tracked(first))
        assertFalse("viewCompleted" in events(firstId))
        assertTrue(events(secondId).isEmpty())

        main { first.release() }
        waitFor(5_000) { "viewCompleted" in events(firstId) }
    }

    @Test
    fun releasingAgainAfterAnotherPlayerBecameTrackedLeavesThatPlayerTracked() {
        val oldId = id("old")
        val newId = id("new")
        val old = newCollector(oldId)
        main { old.release() }
        val current = newCollector(newId)
        assertTrue(tracked(current))

        main { old.release() }
        Thread.sleep(1_000)
        assertTrue(tracked(current))
        assertFalse("viewCompleted" in events(newId))
        assertEquals(1, events(oldId).count { it == "viewCompleted" })

        main { current.release() }
    }

    @Test
    fun anUntrackedPlayerDoesNotBecomeTrackedWhenTheTrackedOneReleases() {
        val trackedId = id("tracked")
        val untrackedId = id("untracked")
        val trackedCollector = newCollector(trackedId)
        val untracked = newCollector(untrackedId)
        main { trackedCollector.release() }
        Thread.sleep(500)

        assertFalse(tracked(untracked))
        assertTrue(events(untrackedId).isEmpty())
        main { untracked.release() }
    }

    private companion object {
        val VIEW_ID = Regex(""""veid":"([^"]+)"""")
        val UNORDERED = setOf("requestCompleted", "requestCanceled", "requestFailed", "variantChanged", "pulse")
        val EVENT = Regex("""EVENT_EMIT_BEFORE: event=(\w+)""")
        const val STREAM = "https://stream.fastpix.io/3ad91e5f-0f45-403f-bda0-2a668a3581ee.m3u8"
    }
}
