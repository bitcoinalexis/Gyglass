package com.angylabs.gyglass.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.angylabs.gyglass.ai.AiProvider
import com.angylabs.gyglass.ai.AiService
import com.angylabs.gyglass.ai.AiSettings
import com.angylabs.gyglass.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlassesAssistant(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: AiSettings,
    private val ai: AiService,
    private val speak: (String) -> Unit
) : GlassesProtocol.Listener {

    data class Interaction(
        val time: String,
        val photo: ByteArray?,
        val question: String,
        val answer: String,
        val provider: String,
        val millis: Long
    )

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val photoDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "photos").apply { mkdirs() }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val _status = MutableStateFlow("Esperando botón IA de las gafas")
    val status: StateFlow<String> = _status
    private val _last = MutableStateFlow<Interaction?>(null)
    val last: StateFlow<Interaction?> = _last
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    var enabled: Boolean
        get() = settings.getString("assistant_enabled", "1") == "1"
        set(v) = settings.putString("assistant_enabled", if (v) "1" else "0")

    var provider: AiProvider
        get() = settings.defaultProvider
        set(v) {
            settings.defaultProvider = v
            AppLog.log("ASISTENTE", "Modelo predeterminado guardado: ${v.label} (${settings.model(v)})")
        }

    private var pendingPhoto: ByteArray? = null
    private var pendingPhotoAt = 0L
    private var recentPhoto: ByteArray? = null
    private var recentPhotoAt = 0L
    private var pendingQuestion: String? = null
        set(v) {
            field = v
            _awaitingPhoto.value = v != null
        }
    private val _awaitingPhoto = MutableStateFlow(false)
    val awaitingPhoto: StateFlow<Boolean> = _awaitingPhoto
    var photoRequester: (() -> Boolean)? = null

    private val photoRequestTimeout = Runnable {
        if (pendingQuestion != null) {
            pendingQuestion = null
            AppLog.log("ASISTENTE", "Las gafas no enviaron la foto a tiempo")
            _status.value = "Las gafas no enviaron la foto"
            speak("Las gafas no enviaron la foto. Inténtalo de nuevo.")
        }
    }

    fun handleSpokenCommand(question: String) {
        android.util.Log.i("GyGlassDebug", "handleSpokenCommand q=$question enabled=$enabled busy=${_busy.value} pending=$pendingQuestion")
        if (!enabled) return
        if (_busy.value || pendingQuestion != null) {
            AppLog.log("ASISTENTE", "Ocupado, se ignora: \"$question\"")
            return
        }
        AppLog.log("ASISTENTE", "Comando de voz: \"$question\"")
        if (isVisualQuestion(question)) requestPhotoFor(question) else process(null, null, question)
    }

    fun askFromApp(question: String) {
        if (_busy.value || pendingQuestion != null) {
            speak("Un momento, sigo con la consulta anterior.")
            return
        }
        AppLog.log("ASISTENTE", "Pregunta desde la app: \"$question\"")
        if (isVisualQuestion(question)) requestPhotoFor(question) else process(null, null, question)
    }

    fun askWithPhoto(question: String) {
        if (_busy.value || pendingQuestion != null) {
            speak("Un momento, sigo con la consulta anterior.")
            return
        }
        AppLog.log("ASISTENTE", "Accion con foto: \"$question\"")
        requestPhotoFor(question)
    }

    fun onWakeWord() {
        _status.value = "Te escucho..."
        speak("Dime")
    }

    private fun requestPhotoFor(question: String) {
        pendingQuestion = question
        _status.value = "Tomando foto con las gafas..."
        speak("Tomando foto")
        val ok = photoRequester?.invoke() ?: false
        if (!ok) {
            pendingQuestion = null
            _status.value = "No se pudo pedir la foto a las gafas"
            speak("No pude pedir la foto a las gafas. Revisa que estén conectadas.")
            return
        }
        handler.removeCallbacks(photoRequestTimeout)
        handler.postDelayed(photoRequestTimeout, 15000)
    }
    private var audioInProgress = false

    private val photoOnlyRunnable = Runnable {
        val photo = pendingPhoto ?: return@Runnable
        if (!audioInProgress) {
            pendingPhoto = null
            process(photo, null)
        }
    }

    override fun onPhotoAnnounced(id: Int) {
        if (!enabled) return
        _status.value = "Recibiendo foto de las gafas..."
    }

    override fun onPhoto(id: Int, jpeg: ByteArray) {
        val file = File(photoDir, "gafas_${fileFormat.format(Date())}.jpg")
        try {
            file.writeBytes(jpeg)
            AppLog.log("ASISTENTE", "Foto guardada en ${file.absolutePath}")
        } catch (e: Exception) {
            AppLog.log("ASISTENTE", "No se pudo guardar la foto: ${e.message}")
        }
        _last.value = Interaction(timeFormat.format(Date()), jpeg, "", "", "", 0)
        recentPhoto = jpeg
        recentPhotoAt = System.currentTimeMillis()
        val waiting = pendingQuestion
        if (waiting != null) {
            pendingQuestion = null
            handler.removeCallbacks(photoRequestTimeout)
            handler.removeCallbacks(photoOnlyRunnable)
            AppLog.log("ASISTENTE", "Foto solicitada recibida para: \"$waiting\"")
            process(jpeg, null, waiting)
            return
        }
        if (!enabled) return
        pendingPhoto = jpeg
        pendingPhotoAt = System.currentTimeMillis()
        _status.value = "Foto recibida. Puedes hacer tu pregunta..."
        handler.removeCallbacks(photoOnlyRunnable)
        handler.postDelayed(photoOnlyRunnable, 3500)
    }

    override fun onPhotoFailed() {
        if (pendingQuestion != null) {
            pendingQuestion = null
            handler.removeCallbacks(photoRequestTimeout)
            _status.value = "La foto llegó incompleta"
            speak("La foto llegó incompleta. Inténtalo de nuevo.")
        }
    }

    override fun onAudioStart() {
        if (!enabled) return
        audioInProgress = true
        handler.removeCallbacks(photoOnlyRunnable)
        _status.value = "Escuchando tu pregunta..."
    }

    override fun onAudioEnd(packets: List<ByteArray>) {
        audioInProgress = false
        if (!enabled) return
        val photo = pendingPhoto?.takeIf { System.currentTimeMillis() - pendingPhotoAt < 30000 }
        pendingPhoto = null
        handler.removeCallbacks(photoOnlyRunnable)
        scope.launch {
            val decoded = withContext(Dispatchers.Default) { OpusDecoder.decodeToPcm(packets) }?.takeIf { it.second.isNotEmpty() }
            val wav = decoded?.let { OpusDecoder.wav(it.second, it.first, 1) }
            if (wav != null) {
                try {
                    File(photoDir, "voz_${fileFormat.format(Date())}.wav").writeBytes(wav)
                } catch (_: Exception) {
                }
            }
            if (wav == null && photo == null) {
                _status.value = "No se pudo decodificar el audio"
                speak("No pude entender el audio")
                return@launch
            }
            process(photo, wav, null, decoded)
        }
    }

    fun replayLastPhoto() {
        val file = photoDir.listFiles { f -> f.name.startsWith("gafas_") && f.name.endsWith(".jpg") }?.maxByOrNull { it.lastModified() }
        if (file == null) {
            AppLog.log("ASISTENTE", "No hay fotos para reprocesar")
            return
        }
        AppLog.log("ASISTENTE", "Reprocesando foto ${file.name}")
        process(file.readBytes(), null)
    }

    fun replayLastVoice() {
        val file = photoDir.listFiles { f -> f.name.startsWith("voz_") && f.name.endsWith(".wav") }?.maxByOrNull { it.lastModified() }
        if (file == null) {
            AppLog.log("ASISTENTE", "No hay grabaciones de voz para reproducir")
            return
        }
        val wav = file.readBytes()
        if (wav.size <= 44) return
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
        val pcm = wav.copyOfRange(44, wav.size)
        AppLog.log("ASISTENTE", "Reprocesando ${file.name} (${pcm.size} bytes a $rate Hz)")
        process(null, wav, null, rate to pcm)
    }

    fun askManual(photo: ByteArray?, question: String) {
        process(photo, null, question)
    }

    private fun process(photo: ByteArray?, wav: ByteArray?, typedQuestion: String? = null, pcm: Pair<Int, ByteArray>? = null) {
        if (_busy.value) {
            AppLog.log("ASISTENTE", "Ocupado, se ignora la nueva solicitud")
            return
        }
        _busy.value = true
        val chosen = provider
        speak("Un momento")
        scope.launch {
            val start = System.currentTimeMillis()
            var question = typedQuestion ?: ""
            var image = photo
            if (wav != null && question.isEmpty()) {
                _status.value = "Transcribiendo tu voz..."
                question = pcm?.let { LocalTranscriber.transcribe(appContext, it.second, it.first) }?.trim() ?: ""
                if (question.isEmpty()) {
                    _status.value = "Transcribiendo tu voz con Gemini..."
                    question = ai.transcribe(wav).getOrDefault("").trim()
                }
                if (question.isEmpty()) {
                    finish(photo, "(no se entendió el audio)", "No pude entender lo que dijiste. Inténtalo de nuevo hablando cerca de las gafas.", "", start)
                    return@launch
                }
            }
            if (image == null && question.isNotEmpty() && isVisualQuestion(question)) {
                val recent = recentPhoto?.takeIf { System.currentTimeMillis() - recentPhotoAt < RECENT_PHOTO_MS }
                if (recent != null) {
                    AppLog.log("ASISTENTE", "Pregunta visual, se usa la foto de hace ${(System.currentTimeMillis() - recentPhotoAt) / 1000} s")
                    image = recent
                } else {
                    _busy.value = false
                    handler.post { requestPhotoFor(question) }
                    return@launch
                }
            }
            val p = if (image != null && !chosen.supportsImages) {
                val fallback = listOf(AiProvider.GEMINI, AiProvider.CLAUDE).firstOrNull { settings.key(it).isNotBlank() } ?: AiProvider.GEMINI
                AppLog.log("ASISTENTE", "${chosen.label} no acepta imagenes, se usa ${fallback.label} para la foto")
                fallback
            } else {
                chosen
            }
            _status.value = "Pensando con ${p.label}..."
            val prompt = buildPrompt(image != null, false, question)
            var usedLabel = p.label
            var result = ai.ask(p, prompt, image, null)
            if (result.isFailure && image != null && p != AiProvider.GEMINI && settings.key(AiProvider.GEMINI).isNotBlank()) {
                AppLog.log("ASISTENTE", "${p.label} no pudo procesar la foto (${result.exceptionOrNull()?.message?.take(150)}), se usa Gemini como respaldo")
                _status.value = "${p.label} no acepta la foto, usando Gemini..."
                result = ai.ask(AiProvider.GEMINI, prompt, image, null)
                usedLabel = AiProvider.GEMINI.label + " (respaldo)"
            }
            val answer = result.fold({ clean(it) }, { "Lo siento, hubo un error con la inteligencia artificial: ${it.message?.take(120)}" })
            finish(image, question.ifEmpty { "(sin pregunta, descripción automática)" }, answer, usedLabel, start)
        }
    }

    private fun finish(photo: ByteArray?, question: String, answer: String, providerLabel: String, start: Long) {
        val elapsed = System.currentTimeMillis() - start
        _last.value = Interaction(timeFormat.format(Date()), photo, question, answer, providerLabel, elapsed)
        AppLog.log("ASISTENTE", "Respuesta (${elapsed} ms): $answer")
        _status.value = "Respuesta lista en ${elapsed / 1000} s"
        speak(answer)
        _busy.value = false
    }

    private fun isVisualQuestion(q: String): Boolean {
        val t = q.lowercase(Locale.ROOT)
        return VISUAL_WORDS.any { t.contains(it) }
    }

    private fun buildPrompt(hasPhoto: Boolean, hasAudio: Boolean, question: String): String = when {
        hasPhoto && hasAudio ->
            "La imagen es lo que tengo delante, tomada con la cámara de mis gafas. En el audio te hago una pregunta sobre ella. " +
                "Respóndela. Si el audio no contiene una pregunta clara, describe la escena."
        hasPhoto && question.isNotEmpty() ->
            "La imagen es lo que tengo delante, tomada con la cámara de mis gafas. Mi pregunta es: $question"
        hasPhoto ->
            "La imagen es lo que tengo delante, tomada con la cámara de mis gafas. Descríbela para orientarme."
        hasAudio ->
            "Te hablo desde mis gafas. Responde a lo que digo en el audio."
        question.isNotEmpty() -> question
        else -> "Saluda brevemente."
    }

    companion object {
        private const val RECENT_PHOTO_MS = 120_000L
        private val VISUAL_WORDS = listOf(
            "ver", "veo", "viendo", "mira", "enfrente", "delante", "frente", "qué hay", "que hay",
            "describe", "lee", "leer", "letrero", "color", "foto", "imagen", "cámara", "camara", "quién", "quien"
        )
    }

    private fun clean(text: String): String =
        text.replace(Regex("[*#_`>]+"), "").replace(Regex("\\s+"), " ").trim()
}
