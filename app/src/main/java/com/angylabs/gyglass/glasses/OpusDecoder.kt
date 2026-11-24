package com.angylabs.gyglass.glasses

import android.media.MediaCodec
import android.media.MediaFormat
import com.angylabs.gyglass.log.AppLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OpusDecoder {

    private const val OUTPUT_RATE = 16000

    fun decodeToWav(packets: List<ByteArray>): ByteArray? {
        if (packets.isEmpty()) return null
        val pcm = decodeToPcm(packets) ?: return null
        if (pcm.second.isEmpty()) return null
        return wav(pcm.second, pcm.first, 1)
    }

    fun decodeToPcm(packets: List<ByteArray>): Pair<Int, ByteArray>? {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (e: Exception) {
            AppLog.log("AUDIO", "No hay decodificador Opus: ${e.message}")
            return null
        }
        val out = ByteArrayOutputStream()
        var sampleRate = OUTPUT_RATE
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OUTPUT_RATE, 1)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            format.setByteBuffer("csd-1", longBuffer(0L))
            format.setByteBuffer("csd-2", longBuffer(80_000_000L))
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pts = 0L
            var inputIndex = 0
            var inputDone = false
            var outputDone = false
            var guard = 0
            while (!outputDone && guard < packets.size * 20 + 500) {
                guard++
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(5000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        if (inputIndex < packets.size) {
                            val p = packets[inputIndex++]
                            buf.put(p)
                            codec.queueInputBuffer(idx, 0, p.size, pts, 0)
                            pts += 20_000
                        } else {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            val chunk = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(chunk)
                            out.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.log("AUDIO", "Error decodificando Opus: ${e.javaClass.simpleName} ${e.message}")
            return null
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
        val pcm = out.toByteArray()
        AppLog.log("AUDIO", "Opus decodificado: ${packets.size} paquetes -> ${pcm.size} bytes PCM a $sampleRate Hz (${pcm.size * 500L / sampleRate} ms), nivel=${level(pcm)}")
        return sampleRate to pcm
    }

    private fun level(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val n = sb.remaining()
        for (i in 0 until n) {
            val v = sb.get(i).toDouble()
            sum += v * v
        }
        return Math.sqrt(sum / n).toInt()
    }

    private fun opusHead(): ByteArray {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray())
        b.put(1)
        b.put(1)
        b.putShort(0)
        b.putInt(16000)
        b.putShort(0)
        b.put(0)
        return b.array()
    }

    private fun longBuffer(v: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(v).apply { flip() }

    fun wav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
