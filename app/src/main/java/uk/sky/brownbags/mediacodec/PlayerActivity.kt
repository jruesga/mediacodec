package uk.sky.brownbags.mediacodec

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnSliderTouchListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

class PlayerActivity : InmersiveActivity(), SurfaceHolder.Callback, PlayerListener {

    private lateinit var renderer: Renderer

    private val timeView by lazy { findViewById<TextView>(R.id.time) }
    private val durationView by lazy { findViewById<TextView>(R.id.duration) }
    private val progressView by lazy { findViewById<Slider>(R.id.progress) }
    private val playPauseView by lazy { findViewById<ImageView>(R.id.playPause) }
    private val forwardView by lazy { findViewById<ImageView>(R.id.forward) }
    private val backwardView by lazy { findViewById<ImageView>(R.id.backward) }
    private val repeatView by lazy { findViewById<ImageView>(R.id.repeat) }
    private val speedView by lazy { findViewById<ImageView>(R.id.speed) }
    private val speedOptionsView by lazy { findViewById<ViewGroup>(R.id.speed_options) }

    private var userIsSeeking = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_player)

        val surfaceView = findViewById<SurfaceView>(R.id.player)
        surfaceView.holder.addCallback(this)

        playPauseView.setOnClickListener {
            when (renderer.state()) {
                STATE_IDLE -> {
                    renderer.play()
                    createScreenWakeLock()
                }
                STATE_PLAYING -> {
                    renderer.pause()
                    releaseScreenWakeLock()
                }
                STATE_SEEKING -> {}
                STATE_STOPPED -> {
                    // Safe previous renderer properties and setup back to the new renderer
                    val speedMode = renderer.speedMode
                    val repeatMode = renderer.repeatMode
                    renderer = Renderer(surfaceView.holder.surface, assets.openFd(TEST_ASSET), this)
                    renderer.speedMode = speedMode
                    renderer.repeatMode = repeatMode
                    renderer.start()
                    enableSeekControls(true)
                }
            }
        }
        repeatView.setOnClickListener {
            repeatView.isSelected = repeatView.isSelected.not()
            renderer.repeatMode = when (repeatView.isSelected) {
                true -> REPEAT_MODE_ON
                else -> REPEAT_MODE_OFF
            }
        }
        speedView.setOnClickListener {
            speedOptionsView.isVisible = speedOptionsView.isVisible.not()
        }
        arrayOf(
            R.id.speed_normal to R.drawable.speed_normal,
            R.id.speed_slow to R.drawable.speed_slow,
            R.id.speed_fast to R.drawable.speed_fast
        ).forEachIndexed { index, resources ->
            findViewById<ImageView>(resources.first).setOnClickListener {
                speedOptionsView.isVisible = false
                speedView.setImageDrawable(ContextCompat.getDrawable(this, resources.second))
                renderer.speedMode = index
            }
        }

        backwardView.setOnClickListener {
            seekTo(renderer.position() - 5_000L)
        }
        forwardView.setOnClickListener {
            seekTo(renderer.position() + 5_000L)
        }
        progressView.addOnSliderTouchListener(object : OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userIsSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userIsSeeking = false
                seekTo(slider.value.toLong())
            }
        })
    }

    override fun onStop() {
        renderer.release()
        renderer.join()
        releaseScreenWakeLock()
        super.onStop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer = Renderer(holder.surface, assets.openFd(TEST_ASSET), this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.start()
        createScreenWakeLock()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }

    private fun createScreenWakeLock() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releaseScreenWakeLock() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun seekTo(position: Long) {
        renderer.seekTo(position)
    }

    override fun onPositionChanged(position: Long) {
        timeView.text = position.toDurationTime()
        if (userIsSeeking.not()) {
            progressView.value = position.toFloat()
        }
    }

    override fun onDurationChanged(duration: Long) {
        durationView.text = duration.toDurationTime()
        progressView.valueTo = duration.toFloat()
    }

    override fun onPlaybackStateChanged(@PlaybackState state: Int) {
        @DrawableRes val resId = when (state) {
            STATE_PLAYING -> R.drawable.pause
            STATE_IDLE -> R.drawable.play
            STATE_SEEKING -> 0
            else -> {
                enableSeekControls(false)
                releaseScreenWakeLock()
                R.drawable.play
            }
        }

        if (resId != 0) {
            playPauseView.setImageDrawable(ContextCompat.getDrawable(this, resId))
        }
    }

    override fun onFormatChanged(format: MediaFormat) {
        println("onFormatChanged $format")
    }

    private fun enableSeekControls(enable: Boolean) {
        forwardView.isVisible = enable
        backwardView.isVisible = enable
        progressView.isEnabled = enable
    }

    private companion object {
        private const val TEST_ASSET = "sample2.mp4"

        private fun Long.toDurationTime() =
            String.format("%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(this) % TimeUnit.HOURS.toMinutes(1),
                TimeUnit.MILLISECONDS.toSeconds(this) % TimeUnit.MINUTES.toSeconds(1)
            )
    }

    private class Renderer(
        private val surface: Surface,
        private val asset: AssetFileDescriptor,
        private val listener: PlayerListener
    ) : Thread() {

        private lateinit var extractor: MediaExtractor

        private val handler = Handler(Looper.getMainLooper())
        private var startTime: Long = -1L
        private var running = false

        private val lock: ReentrantLock = ReentrantLock()
        private val cond: Condition = lock.newCondition()

        @PlaybackState
        private var lastState = STATE_STOPPED
        private var lastPosition = -1L
        private var duration = -1L
        @RepeatMode
        var repeatMode = REPEAT_MODE_OFF
        @SpeedMode
        var speedMode = SPEED_NORMAL
            set(value) {
                adjustStartTime(value, lastPosition)
                releaseNextFrame = true
                field = value
            }


        private var pendingSeekPosition = -1L
        private var pendingSeekState = STATE_IDLE
        private var processingSeeking = false
        private var releaseNextFrame = false

        @PlaybackState
        fun state(): Int = lastState

        fun position(): Long = lastPosition

        fun seekTo(position: Long) {
            lock.lock()
            pendingSeekPosition = position
            pendingSeekState = lastState
            lastState = STATE_SEEKING
            cond.signal()
            lock.unlock()
        }

        fun release() {
            running = false

            lock.lock()
            cond.signal()
            lock.unlock()
        }

        fun pause() {
            lock.lock()
            lastState = STATE_IDLE
            lock.unlock()
        }

        fun play() {
            lock.lock()
            if (lastState == STATE_IDLE) {
                adjustStartTime(speedMode, lastPosition)
                notifyState(STATE_PLAYING)
            }
            cond.signal()
            lock.unlock()
        }

        private fun adjustStartTime(@SpeedMode speedMode: Int, position: Long) {
            startTime = System.nanoTime() - (position / speedMode.toSpeed()).toLong().toNano()
        }

        override fun run() {
            extractor = MediaExtractor()
            extractor.setDataSource(asset)
            createFormatAndDecoder { format, decoder ->
                maybeReportDurationChanged(format)
                notifyState(STATE_PLAYING)

                // Configure decoder
                decoder.configure(format, surface, null, 0)
                decoder.start()

                startTime = System.nanoTime()
                running = true
                while (running) {
                    maybeProcessNewState(format, decoder)
                    if (running.not()) break

                    // ================
                    // feedInputBuffer
                    // ================
                    val inputIndex: Int = decoder.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        if (buffer != null) {
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                // End of stream
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                val mediaPresentationTime = extractor.sampleTime
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, mediaPresentationTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    if (running.not()) break

                    // ================
                    // drainOutputBuffer
                    // ================
                    val info = MediaCodec.BufferInfo()
                    val outputIndex = decoder.dequeueOutputBuffer(info, 0)
                    when (outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            notifyEvent {
                                listener.onFormatChanged(outputFormat)
                            }
                            maybeReportDurationChanged(outputFormat)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        else -> {
                            // Calculate player speed
                            val speed = (1_000L / speedMode.toSpeed()).toLong()
                            while (releaseNextFrame.not() &&
                                info.presentationTimeUs > ((System.nanoTime() - startTime) / speed)) {
                                sleep(1L)
                            }
                            releaseNextFrame = false
                            processingSeeking = false
                            if (running.not()) break

                            // Has codec ended?
                            if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                decoder.releaseOutputBuffer(outputIndex, false)
                                if (repeatMode == REPEAT_MODE_ON) {
                                    reset(format, decoder)
                                    continue
                                }

                                running = false
                                break
                            } else {
                                // Display the frame
                                val render = duration >= info.presentationTimeUs
                                decoder.releaseOutputBuffer(outputIndex, render)
                                if (render) {
                                    maybeReportPositionChanged(info)
                                }
                            }
                        }
                    }
                }

                // release decoder
                notifyState(STATE_STOPPED)
                decoder.stop()
                decoder.safeRelease()
            }
            extractor.release()
            asset.close()
        }

        private fun createFormatAndDecoder(callback: (MediaFormat, MediaCodec) -> Unit) {
            for (i in 0 until extractor.trackCount) {
                // Get the format for the track
                val format: MediaFormat = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    // Select the track
                    extractor.selectTrack(i)

                    // Create a decoder for the format
                    val codecName = MediaCodecList(
                        MediaCodecList.REGULAR_CODECS
                    ).findDecoderForFormat(format)
                    val decoder = MediaCodec.createByCodecName(codecName)

                    callback(format, decoder)
                }
            }
        }

        private fun maybeReportDurationChanged(mediaFormat: MediaFormat) {
            if (mediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
                duration = mediaFormat.getLong(MediaFormat.KEY_DURATION)
                notifyEvent {
                    listener.onDurationChanged(duration.toMs())
                }
            }
        }

        private fun maybeReportPositionChanged(info: MediaCodec.BufferInfo) {
            val newPosition = info.presentationTimeUs.toMs()
            if (newPosition != lastPosition && lastState != STATE_SEEKING) {
                notifyEvent {
                    listener.onPositionChanged(newPosition)
                }
            }
            lastPosition = newPosition
        }

        private fun maybeProcessNewState(format: MediaFormat, decoder: MediaCodec) {
            lock.lock()
            when {
                // Seek
                lastState == STATE_SEEKING -> processSeek(format, decoder)

                // Pause
                lastState == STATE_IDLE && processingSeeking.not() -> {
                    notifyState(STATE_IDLE)
                    cond.await()
                }
            }
            lock.unlock()
        }

        private fun processSeek(format: MediaFormat, decoder: MediaCodec) {
            notifyState(STATE_SEEKING)

            reset(format, decoder, pendingSeekPosition)

            notifyState(pendingSeekState)
            processingSeeking = true
        }

        private fun reset(format: MediaFormat, decoder: MediaCodec, position: Long = 0L) {
            decoder.flush()
            decoder.stop()
            decoder.reset()

            extractor.seekTo(
                position.toUs(),
                MediaExtractor.SEEK_TO_CLOSEST_SYNC
            )
            adjustStartTime(speedMode, position)

            decoder.configure(format, surface, null, 0)
            decoder.start()
        }

        private fun notifyState(@PlaybackState state: Int) {
            lastState = state
            notifyEvent {
                listener.onPlaybackStateChanged(state)
            }
        }

        private fun notifyEvent(block: () -> Unit) {
            handler.post {
                block()
            }
        }

        private companion object {
            private fun Long.toMs() = this / 1_000L
            private fun Long.toUs() = this * 1_000L
            private fun Long.toNano() = toUs() * 1_000L

            private fun Int.toSpeed() = when (this) {
                SPEED_FAST -> 2f
                SPEED_SLOW -> 0.5f
                else -> 1f
            }

            private fun MediaCodec.safeRelease() {
                try {
                    release()
                } catch (ex: IllegalStateException) {
                    // ignore
                }
            }
        }
    }
}