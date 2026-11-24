package com.angylabs.gyglass.glasses

import android.os.Handler
import android.os.Looper
import com.angylabs.gyglass.log.AppLog
import com.angylabs.gyglass.log.Hex
import java.io.ByteArrayOutputStream
import java.util.UUID

class GlassesProtocol(private val listener: Listener) {

    interface Listener {
        fun onPhotoAnnounced(id: Int)
        fun onPhoto(id: Int, jpeg: ByteArray)
        fun onAudioStart()
        fun onAudioEnd(packets: List<ByteArray>)
        fun onFrame(cmd: Int, payload: ByteArray) {}
        fun onPhotoFailed() {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private val buffer = ByteArrayOutputStream()
    private var pending = ByteArray(0)

    private var photoId = -1
    private val photoChunks = sortedMapOf<Int, ByteArray>()
    private var lastChunkSeen = false
    private var photoTotal = 0
    private var pullMode = false
    var sender: ((ByteArray, String) -> Boolean)? = null

    private var audioActive = false
    private val audioPackets = mutableListOf<ByteArray>()

    data class Battery(val level: Int, val charging: Boolean)

    private val _battery = kotlinx.coroutines.flow.MutableStateFlow<Battery?>(null)
    val battery: kotlinx.coroutines.flow.StateFlow<Battery?> = _battery

    private var retries = 0
    private val _transferring = kotlinx.coroutines.flow.MutableStateFlow(false)
    val transferring: kotlinx.coroutines.flow.StateFlow<Boolean> = _transferring

    private val photoTimeout = Runnable {
        if (photoTotal > 0 && photoChunks.size < photoTotal && retries < MAX_RETRIES) {
            val missing = (0 until photoTotal).first { it !in photoChunks.keys }
            retries++
            AppLog.log("GAFAS", "Falta el fragmento $missing (${photoChunks.size}/$photoTotal), reintento $retries")
            pullMode = true
            requestChunk(missing)
            handler.postDelayed(photoTimeoutRef, 2500)
        } else {
            finishPhoto("timeout")
        }
    }
    private val photoTimeoutRef: Runnable get() = photoTimeout
    private val audioTimeout = Runnable {
        if (audioActive) {
            AppLog.log("GAFAS", "Audio sin evento de fin, se cierra por tiempo")
            endAudio()
        }
    }

    @Synchronized
    fun feed(data: ByteArray) {
        pending += data
        while (true) {
            val start = pending.indexOf(HEADER)
            if (start < 0) {
                if (pending.size > 4096) pending = ByteArray(0)
                return
            }
            if (start > 0) pending = pending.copyOfRange(start, pending.size)
            if (pending.size < 6) return
            val len = (pending[2].toInt() and 0xFF) or ((pending[3].toInt() and 0xFF) shl 8)
            if (len > 4096) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < 6 + len) return
            val cmd = pending[1].toInt() and 0xFF
            val payload = pending.copyOfRange(6, 6 + len)
            pending = pending.copyOfRange(6 + len, pending.size)
            val frameCmd = cmd
            handler.post { handleFrame(frameCmd, payload) }
        }
    }

    private fun handleFrame(cmd: Int, payload: ByteArray) {
        when (cmd) {
            CMD_AUDIO -> {
                if (!audioActive) {
                    audioActive = true
                    audioPackets.clear()
                    listener.onAudioStart()
                }
                audioPackets += payload
                handler.removeCallbacks(audioTimeout)
                handler.postDelayed(audioTimeout, 2500)
                return
            }
            CMD_PHOTO -> {
                handlePhotoChunk(payload)
                return
            }
        }
        AppLog.log("GAFAS", "Trama cmd=0x%02X payload=[%s]".format(cmd, Hex.toHex(payload)))
        listener.onFrame(cmd, payload)
        if (cmd == CMD_BATTERY && payload.size >= 2) {
            val level = payload[0].toInt() and 0xFF
            if (level in 0..100) {
                _battery.value = Battery(level, payload[1].toInt() == 1)
                AppLog.log("GAFAS", "Bateria: $level%${if (payload[1].toInt() == 1) " (cargando)" else ""}")
            }
        }
        if (cmd == CMD_EVENT && payload.isNotEmpty()) {
            when {
                payload.size >= 3 && payload[0] == 0x02.toByte() && payload[1] == 0x00.toByte() -> {
                    val id = payload[2].toInt() and 0xFF
                    val mode = if (payload.size >= 4) payload[3].toInt() and 0xFF else 2
                    startPhoto(id)
                    if (mode == 1) {
                        pullMode = true
                        AppLog.log("GAFAS", "Foto en modo bajo demanda, se solicitan los fragmentos")
                        handler.postDelayed({ requestChunk(0) }, 150)
                    } else {
                        pullMode = false
                    }
                }
                payload.size == 2 && payload[0] == 0x03.toByte() -> {
                    audioActive = true
                    audioPackets.clear()
                    AppLog.log("GAFAS", "Inicio de escucha (boton IA)")
                    listener.onAudioStart()
                    handler.removeCallbacks(audioTimeout)
                    handler.postDelayed(audioTimeout, 20000)
                }
                payload.size == 2 && payload[0] == 0x0A.toByte() -> {
                    AppLog.log("GAFAS", "Fin de escucha")
                    endAudio()
                }
            }
        }
    }

    private fun endAudio() {
        handler.removeCallbacks(audioTimeout)
        if (!audioActive) return
        audioActive = false
        val packets = audioPackets.toList()
        audioPackets.clear()
        AppLog.log("GAFAS", "Audio recibido: ${packets.size} paquetes (${packets.size * 20} ms aprox)")
        listener.onAudioEnd(packets)
    }

    private fun startPhoto(id: Int) {
        photoId = id
        photoChunks.clear()
        photoTotal = 0
        retries = 0
        _transferring.value = true
        lastChunkSeen = false
        AppLog.log("GAFAS", "Foto anunciada id=0x%02X".format(id))
        listener.onPhotoAnnounced(id)
        handler.removeCallbacks(photoTimeout)
        handler.postDelayed(photoTimeout, 8000)
    }

    private fun requestChunk(index: Int) {
        val payload = byteArrayOf(1, (index and 0xFF).toByte(), ((index shr 8) and 0xFF).toByte())
        val ok = sender?.invoke(frame(CMD_PHOTO, payload), "PEDIR FRAGMENTO $index") ?: false
        if (!ok) AppLog.log("GAFAS", "No se pudo pedir el fragmento $index")
    }

    private fun handlePhotoChunk(payload: ByteArray) {
        if (payload.size < 5) return
        val total = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        val index = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)
        if (index == 0 || total != photoTotal) {
            if (index == 0) photoChunks.clear()
            photoTotal = total
        }
        photoChunks[index] = payload.copyOfRange(5, payload.size)
        lastChunkSeen = index + 1 >= total
        handler.removeCallbacks(photoTimeout)
        if (photoChunks.size >= total && total > 0) {
            pullMode = false
            finishPhoto("completa")
        } else {
            if (pullMode) {
                val next = (index + 1 until total).firstOrNull { it !in photoChunks.keys }
                    ?: (0 until total).firstOrNull { it !in photoChunks.keys }
                if (next != null) requestChunk(next)
            }
            handler.postDelayed(photoTimeout, if (lastChunkSeen) 800 else 3000)
        }
    }

    private fun finishPhoto(reason: String) {
        handler.removeCallbacks(photoTimeout)
        _transferring.value = false
        pullMode = false
        if (photoChunks.isEmpty()) return
        if (photoTotal > 0 && photoChunks.size < photoTotal) {
            AppLog.log("GAFAS", "Foto incompleta descartada: ${photoChunks.size}/$photoTotal fragmentos")
            photoChunks.clear()
            listener.onPhotoFailed()
            return
        }
        val out = ByteArrayOutputStream()
        val expected = maxOf(photoTotal, photoChunks.lastKey() + 1)
        photoChunks.values.forEach { out.write(it) }
        val raw = out.toByteArray()
        val eoi = indexOfEoi(raw)
        val jpeg = if (eoi > 0) raw.copyOfRange(0, eoi + 2) else raw
        AppLog.log("GAFAS", "Foto id=0x%02X %s: %d/%d fragmentos, %d bytes JPEG, fin=%s".format(photoId, reason, photoChunks.size, expected, jpeg.size, eoi > 0))
        val id = photoId
        photoChunks.clear()
        lastChunkSeen = false
        if (jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) listener.onPhoto(id, jpeg)
    }

    private fun indexOfEoi(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) return i
        }
        return -1
    }

    private fun ByteArray.indexOf(b: Byte): Int {
        for (i in indices) if (this[i] == b) return i
        return -1
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        val NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        val WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        const val HEADER: Byte = 0xBC.toByte()
        const val CMD_EVENT = 0x73
        const val CMD_PHOTO = 0xFD
        const val CMD_AUDIO = 0x59
        const val FULL_PHOTO_PAYLOAD = 1018
        const val MAX_RETRIES = 8
        const val CMD_BATTERY = 0x42
        val BATTERY_PAYLOAD = byteArrayOf(0, 0)
        const val CMD_CONTROL = 0x41
        val AI_PHOTO_PAYLOAD = byteArrayOf(2, 1, 6, 2, 2)
        val TAKE_PHOTO_PAYLOAD = byteArrayOf(2, 1, 1)

        fun crc16(data: ByteArray): Int {
            if (data.isEmpty()) return 0xFFFF
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                }
            }
            return crc and 0xFFFF
        }

        fun frame(cmd: Int, payload: ByteArray): ByteArray {
            val out = ByteArray(payload.size + 6)
            out[0] = HEADER
            out[1] = cmd.toByte()
            if (payload.isEmpty()) {
                out[4] = 0xFF.toByte()
                out[5] = 0xFF.toByte()
                return out
            }
            out[2] = (payload.size and 0xFF).toByte()
            out[3] = ((payload.size shr 8) and 0xFF).toByte()
            val crc = crc16(payload)
            out[4] = (crc and 0xFF).toByte()
            out[5] = ((crc shr 8) and 0xFF).toByte()
            System.arraycopy(payload, 0, out, 6, payload.size)
            return out
        }
    }
}
