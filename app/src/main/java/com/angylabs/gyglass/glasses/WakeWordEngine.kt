package com.angylabs.gyglass.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.angylabs.gyglass.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.zip.ZipInputStream

class WakeWordEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val onCommand: (String) -> Unit,
    private val onWakeOnly: () -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val modelDir = File(appContext.filesDir, "vosk-es")

    private val _status = MutableStateFlow("Apagado")
    val status: StateFlow<String> = _status

    @Volatile
    private var running = false

    @Volatile
    var paused = false
        set(v) {
            field = v
            if (running) _status.value = if (v) "En pausa (hablando o pensando)" else "Escuchando \"Hey CLEO\""
        }

    private var model: Model? = null
    private var thread: Thread? = null
    private var awaitingQuestionUntil = 0L

    fun isModelReady(): Boolean = File(modelDir, "am").exists() || File(modelDir, "conf").exists()

    fun start() {
        if (running) return
        running = true
        scope.launch {
            try {
                if (!isModelReady()) {
                    _status.value = "Descargando modelo de voz en español (40 MB)..."
                    withContext(Dispatchers.IO) { downloadModel() }
                }
                if (!running) return@launch
                _status.value = "Cargando modelo de voz..."
                val m = withContext(Dispatchers.IO) { model ?: Model(modelDir.absolutePath).also { model = it } }
                if (!running) return@launch
                thread = Thread({ loop(m) }, "cleo-wake").also { it.start() }
            } catch (e: Exception) {
                AppLog.log("CLEO", "No se pudo iniciar: ${e.javaClass.simpleName} ${e.message}")
                _status.value = "Error: ${e.message}"
                running = false
            }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        _status.value = "Apagado"
    }

    private fun downloadModel() {
        val tmp = File(appContext.cacheDir, "vosk-es.zip")
        AppLog.log("CLEO", "Descargando modelo $MODEL_URL")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            _status.value = "Descargando modelo de voz: $pct%"
                        }
                    }
                }
            }
        }
        _status.value = "Descomprimiendo modelo..."
        val staging = File(appContext.filesDir, "vosk-es-tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val target = File(staging, name)
                if (!target.canonicalPath.startsWith(staging.canonicalPath)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        modelDir.deleteRecursively()
        staging.renameTo(modelDir)
        tmp.delete()
        AppLog.log("CLEO", "Modelo de voz listo en ${modelDir.absolutePath}")
    }

    @SuppressLint("MissingPermission")
    private fun loop(m: Model) {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = ShortArray(rate / 8)
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        var scoActive = false
        try {
            recognizer = Recognizer(m, rate.toFloat())
            while (running && !Thread.currentThread().isInterrupted) {
                if (paused) {
                    if (record != null) {
                        record.stop()
                        record.release()
                        record = null
                    }
                    if (scoActive) {
                        releaseSco()
                        scoActive = false
                    }
                    Thread.sleep(200)
                    continue
                }
                if (record == null) {
                    scoActive = routeToGlassesMic()
                    record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
                    scoInputDevice()?.let { record.setPreferredDevice(it) }
                    record.startRecording()
                    recognizer.reset()
                    _status.value = if (scoActive) "Escuchando \"Hey CLEO\" (micrófono de las gafas)" else "Escuchando \"Hey CLEO\" (micrófono del teléfono)"
                    AppLog.log("CLEO", "Escucha iniciada, SCO=$scoActive")
                }
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) {
                    Thread.sleep(50)
                    continue
                }
                if (recognizer.acceptWaveForm(buffer, n)) {
                    val text = JSONObject(recognizer.result).optString("text")
                    if (text.isNotBlank()) handleText(text)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            AppLog.log("CLEO", "Error en la escucha: ${e.javaClass.simpleName} ${e.message}")
            _status.value = "Error en la escucha: ${e.message}"
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
            if (scoActive) releaseSco()
            recognizer?.close()
            running = false
            AppLog.log("CLEO", "Escucha detenida")
        }
    }

    private fun handleText(raw: String) {
        val text = normalize(raw)
        AppLog.log("CLEO", "Oído: \"$raw\"")
        val now = System.currentTimeMillis()
        if (awaitingQuestionUntil > now) {
            awaitingQuestionUntil = 0
            AppLog.log("CLEO", "Pregunta tras activación: \"$raw\"")
            onCommand(raw.trim())
            return
        }
        val match = WAKE.find(text) ?: return
        val question = raw.trim().split(Regex("\\s+")).drop(match.value.trim().split(Regex("\\s+")).size + countWordsBefore(text, match.range.first)).joinToString(" ")
        AppLog.log("CLEO", "Palabra de activación detectada (\"${match.value}\"), pregunta=\"$question\"")
        if (question.split(" ").count { it.isNotBlank() } >= 2) {
            onCommand(question)
        } else {
            awaitingQuestionUntil = now + 10000
            onWakeOnly()
        }
    }

    private fun countWordsBefore(text: String, index: Int): Int =
        text.substring(0, index).trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    private fun scoInputDevice(): AudioDeviceInfo? =
        audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    @Suppress("DEPRECATION")
    private fun routeToGlassesMic(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (dev == null) {
                    AppLog.log("CLEO", "No hay dispositivo SCO de las gafas disponible")
                    false
                } else {
                    am.setCommunicationDevice(dev)
                }
            } else {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                true
            }
        } catch (e: Exception) {
            AppLog.log("CLEO", "No se pudo activar SCO: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseSco() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        private val WAKE = Regex("\\b(?:(?:hey|ey|ei|hei|oye|oiga|hola)\\s+(?:cleo|clio|kleo|cleu|leo|creo|clero|cle)|cleo|clio|kleo)\\b")
    }
}
