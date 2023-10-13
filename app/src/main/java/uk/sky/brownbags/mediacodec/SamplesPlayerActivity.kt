package uk.sky.brownbags.mediacodec

import android.content.res.AssetManager
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer


class SamplesPlayerActivity : InmersiveActivity(), SurfaceHolder.Callback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
    }

    override fun onStop() {
        renderer.release()
        renderer.join()
        super.onStop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer = Renderer(
            holder.surface,
            resources.assets.open("frames.jc4", AssetManager.ACCESS_STREAMING))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }

    private companion object {
        private lateinit var renderer: Renderer

        val hexChars = "0123456789abcdef".toCharArray()
        fun ByteArray.toHex4(): String {
            val hex = CharArray(2 * this.size)
            this.forEachIndexed { i, byte ->
                val unsigned = 0xff and byte.toInt()
                hex[2 * i] = hexChars[unsigned / 16]
                hex[2 * i + 1] = hexChars[unsigned % 16]
            }

            return hex.joinToString("")
        }

        fun String.toByteArray(): ByteArray = chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private class Renderer(
        private val surface: Surface,
        private val frames: InputStream
    ) : Thread() {

        private var running = false
        private var duration = -1L

        fun release() {
            running = false
        }

        override fun run() {
            val lines = BufferedReader(InputStreamReader(frames)).readLines()

            var sample = 1

            createFormatAndDecoder(lines[0]) { format, decoder ->
                maybeReportDurationChanged(format)

                // Configure decoder
                decoder.configure(format, surface, null, 0)
                decoder.start()

                val startTime = System.nanoTime()
                running = true
                while (running) {
                    // ================
                    // feedInputBuffer
                    // ================
                    val inputIndex: Int = decoder.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        if (buffer != null) {
                            if (sample >= lines.size) {
                                // End of stream
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                val line = lines[sample]
                                val data = line.split(":")[1].trim().split(" ")

                                val mediaPresentationTime = data[0].trim().toLong()

                                val frame = data[1].trim().replaceNalStartUnit().toByteArray()
                                buffer.put(frame)

                                decoder.queueInputBuffer(inputIndex, 0, frame.size, mediaPresentationTime, 0)
                                sample++
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
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        }
                        else -> {
                            while (info.presentationTimeUs > ((System.nanoTime() - startTime) / 1_000L)) {
                                sleep(1L)
                            }

                            if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                if (running.not()) break

                                decoder.releaseOutputBuffer(outputIndex, false)
                                running = false
                                break
                            } else {
                                val render = duration >= info.presentationTimeUs
                                if (running.not()) break
                                decoder.releaseOutputBuffer(outputIndex, render)
                            }
                        }
                    }
                }

                // release decoder
                decoder.stop()
                decoder.release()
            }
        }

        private fun createFormatAndDecoder(info: String, callback: (MediaFormat, MediaCodec) -> Unit) {
            val data = info.split(":")[1].trim().split(",")
            val mediaFormat = MediaFormat()
            mediaFormat.setInteger("max-bitrate", data[0].toInt())
            mediaFormat.setInteger(MediaFormat.KEY_TRACK_ID, data[1].toInt())
            mediaFormat.setInteger(MediaFormat.KEY_LEVEL, data[2].toInt())
            mediaFormat.setString(MediaFormat.KEY_MIME, data[3])
            mediaFormat.setInteger(MediaFormat.KEY_PROFILE, data[4].toInt())
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, data[5].toInt())
            mediaFormat.setString(MediaFormat.KEY_LANGUAGE, data[6])
            mediaFormat.setInteger("display-width", data[7].toInt())
            mediaFormat.setInteger("display-height", data[8].toInt())
            mediaFormat.setInteger(MediaFormat.KEY_WIDTH, data[9].toInt())
            mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, data[10].toInt())
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, data[11].toInt())
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, data[12].toInt())
            mediaFormat.setLong(MediaFormat.KEY_DURATION, data[13].toLong())
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(data[14].toByteArray()))
            mediaFormat.setByteBuffer("csd-1",ByteBuffer.wrap(data[15].toByteArray()))

            val codecName = MediaCodecList(
                MediaCodecList.REGULAR_CODECS
            ).findDecoderForFormat(mediaFormat)
            val decoder = MediaCodec.createByCodecName(codecName)

            callback(mediaFormat, decoder)
        }

        private fun maybeReportDurationChanged(mediaFormat: MediaFormat) {
            if (mediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
                duration = mediaFormat.getLong(MediaFormat.KEY_DURATION)
            }
        }

        private fun String.replaceNalStartUnit(): String {
            var frame = this
            var idx = 0
            while (idx < frame.length) {
                val length = frame.substring(idx, idx + 8).toInt(16)
                frame = frame.replaceRange(idx, idx + 8, "00000001")
                idx += (length * 2) + 8
                println("jrc $frame")
            }
            return frame
        }
    }
}