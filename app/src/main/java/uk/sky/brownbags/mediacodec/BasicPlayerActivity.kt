package uk.sky.brownbags.mediacodec

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

class BasicPlayerActivity : InmersiveActivity(), SurfaceHolder.Callback {

    private lateinit var renderer: Renderer

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
        renderer = Renderer(holder.surface, assets.openFd(TEST_ASSET))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }

    private companion object {
        private const val TEST_ASSET = "sample2.mp4"
    }

    private class Renderer(
        private val surface: Surface,
        private val asset: AssetFileDescriptor
    ) : Thread() {

        private lateinit var extractor: MediaExtractor

        private var running = false
        private var duration = -1L

        fun release() {
            running = false
        }

        override fun run() {
            extractor = MediaExtractor()
            extractor.setDataSource(asset)
            createFormatAndDecoder { format, decoder ->
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
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
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
            }
        }
    }
}