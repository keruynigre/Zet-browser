package dev.zet.browser

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Ayrı indirilen görüntü (MP4/H.264) ve ses (M4A/AAC) dosyalarını tek MP4'te birleştirir. */
object Muxer {
    fun merge(video: File, audio: File, out: File) {
        val extractors = listOf(video, audio).map { f ->
            MediaExtractor().apply { setDataSource(f.path) }
        }
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            var bufSize = 4 * 1024 * 1024
            val tracks = extractors.map { ex ->
                ex.selectTrack(0)
                val format = ex.getTrackFormat(0)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufSize = maxOf(bufSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                muxer.addTrack(format)
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { i, ex ->
                while (true) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    info.set(0, size, ex.sampleTime, flags)
                    muxer.writeSampleData(tracks[i], buffer, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractors.forEach { it.release() }
        }
    }
}
