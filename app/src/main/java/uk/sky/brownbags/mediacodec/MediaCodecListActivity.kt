package uk.sky.brownbags.mediacodec

import android.media.AudioTrack
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import java.nio.ByteBuffer
import java.util.Arrays


class MediaCodecListActivity : InmersiveActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.forEach { codecInfo ->
            println("MediaCodecList: codecName: ${codecInfo.name}")
            println("MediaCodecList:    isEncoder: ${codecInfo.isEncoder}")
            println("MediaCodecList:    supportedTypes: ${Arrays.toString(codecInfo.supportedTypes)}")
            val capabilities = codecInfo.getCapabilitiesForType(codecInfo.supportedTypes[0])
        }


        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, "video/avc")
        val name: String = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
        println("MediaFormat : $name")
    }
}