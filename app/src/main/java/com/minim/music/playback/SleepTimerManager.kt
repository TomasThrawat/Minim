package com.minim.music.playback

import android.os.Handler
import android.os.Looper
import com.minim.music.data.stories.SleepOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 5.7.0 u47: the sleep timer lives in Now Playing's menu only. It either counts down minutes or
 * pauses at the end of the song (or tape) that's playing, using ExoPlayer's pause-at-end so the
 * next one never starts.
 */
class SleepTimerManager(private val playbackController: PlaybackController) {

    enum class Mode { OFF, MINUTES, END_OF_TRACK }

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    private val _mode = MutableStateFlow(Mode.OFF)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _remainingTimeMs = MutableStateFlow(0L)
    /** Real time left: the minutes left, or what's left of the track at its speed. */
    val remainingTimeMs: StateFlow<Long> = _remainingTimeMs.asStateFlow()

    /** The last minutes picked, so the menu can tick it. 0 when none. */
    var minutesChosen: Int = 0
        private set

    fun startTimer(durationMinutes: Int) {
        cancelTimer()
        if (durationMinutes <= 0) return
        minutesChosen = durationMinutes
        _mode.value = Mode.MINUTES
        val endAt = android.os.SystemClock.elapsedRealtime() + durationMinutes * 60_000L
        _remainingTimeMs.value = durationMinutes * 60_000L
        runnable = object : Runnable {
            override fun run() {
                val left = endAt - android.os.SystemClock.elapsedRealtime()
                if (left > 0L) {
                    _remainingTimeMs.value = left
                    handler.postDelayed(this, (left % 1000L).coerceIn(50L, 1000L))
                } else {
                    runnable = null
                    _remainingTimeMs.value = 0L
                    _mode.value = Mode.OFF
                    minutesChosen = 0
                    playbackController.pause()
                }
            }
        }
        handler.postDelayed(runnable!!, 250L)
    }

    /** Pause when the song or tape playing now ends. */
    fun startEndOfTrack() {
        cancelTimer()
        _mode.value = Mode.END_OF_TRACK
        PlaybackService.setPauseAtEnd(true)
        runnable = object : Runnable {
            override fun run() {
                val p = PlaybackService.activePlayer
                if (p != null) {
                    val dur = p.duration
                    val speed = p.playbackParameters.speed
                    _remainingTimeMs.value = if (dur > 0) SleepOptions.remainingAtSpeed(dur, p.currentPosition, speed) else 0L
                }
                handler.postDelayed(this, 500L)
            }
        }
        handler.post(runnable!!)
    }

    /** Called by the service once the player paused at the end of the track. */
    fun onEndOfTrackReached() {
        if (_mode.value != Mode.END_OF_TRACK) return
        cancelTimer()
    }

    fun cancelTimer() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        if (_mode.value == Mode.END_OF_TRACK) PlaybackService.setPauseAtEnd(false)
        _remainingTimeMs.value = 0L
        _mode.value = Mode.OFF
        minutesChosen = 0
    }

    val isRunning: Boolean
        get() = _mode.value != Mode.OFF
}
