package uk.sky.brownbags.mediacodec

import android.media.MediaFormat
import androidx.annotation.IntDef

interface PlayerListener {
    fun onPositionChanged(position: Long) {}
    fun onDurationChanged(duration: Long) {}
    fun onPlaybackStateChanged(@PlaybackState state: Int) {}
    fun onFormatChanged(format: MediaFormat) {}
}

const val STATE_IDLE = 0
const val STATE_PLAYING = 1
const val STATE_SEEKING = 2
const val STATE_STOPPED = 3

@IntDef(value = intArrayOf(STATE_IDLE, STATE_PLAYING, STATE_SEEKING, STATE_STOPPED))
@Retention(AnnotationRetention.SOURCE)
annotation class PlaybackState

const val REPEAT_MODE_OFF = 0
const val REPEAT_MODE_ON = 1

@IntDef(value = intArrayOf(REPEAT_MODE_OFF, REPEAT_MODE_ON))
@Retention(AnnotationRetention.SOURCE)
annotation class RepeatMode

const val SPEED_NORMAL = 0
const val SPEED_SLOW = 1
const val SPEED_FAST = 2

@IntDef(value = intArrayOf(SPEED_NORMAL, SPEED_SLOW, SPEED_FAST))
@Retention(AnnotationRetention.SOURCE)
annotation class SpeedMode