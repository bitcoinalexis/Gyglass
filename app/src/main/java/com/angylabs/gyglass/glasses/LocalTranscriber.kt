package com.angylabs.gyglass.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.angylabs.gyglass.log.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LocalTranscriber {

    private const val TARGET_RATE = 16000
    private val GOOGLE_APP_RECOGNIZER = ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    )
    private var downloadRequested = false

    private fun errorName(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "falta permiso de microfono"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "idioma no soportado"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "idioma no descargado"
        SpeechRecognizer.ERROR_NO_MATCH -> "no se entendio"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "red"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "servicio desconectado"
        else -> "codigo $e"
    }

    suspend fun transcribe(context: Context, pcm: ByteArray, sampleRate: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AppLog.log("VOZ", "Transcripcion local requiere Android 13+")
            return null
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            AppLog.log("VOZ", "No hay reconocedor de voz en el telefono")
            return null
        }
        val audio = withContext(Dispatchers.Default) { downsample(pcm, sampleRate) }
        val start = System.currentTimeMillis()
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val attempts = buildList {
            if (onDevice) {
                add(Triple(true, "es-MX", null))
                add(Triple(true, "es-ES", null))
            }
            add(Triple(false, "es-MX", GOOGLE_APP_RECOGNIZER))
            add(Triple(false, "es-MX", null))
        }
        var result: String? = null
        for ((device, lang, component) in attempts) {
            result = withContext(Dispatchers.Main) {
                withTimeoutOrNull(15000) { recognize(context, audio, device, lang, component) }
            }
            if (!result.isNullOrBlank()) break
        }
        AppLog.log("VOZ", "Transcripcion local en ${System.currentTimeMillis() - start} ms: \"${result ?: "(sin resultado)"}\"")
        return result?.takeIf { it.isNotBlank() }
    }

    private suspend fun recognize(context: Context, audio: ByteArray, useOnDevice: Boolean, language: String, component: ComponentName?): String? {
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = pipe[0]
        val writer = pipe[1]
        val done = CompletableDeferred<String?>()
        val recognizer = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else if (component != null) {
            try {
                SpeechRecognizer.createSpeechRecognizer(context, component)
            } catch (e: Exception) {
                AppLog.log("VOZ", "No disponible ${component.packageName}: ${e.message}")
                return null
            }
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        AppLog.log("VOZ", "Reconocedor ${if (useOnDevice) "en el dispositivo" else component?.packageName ?: "del sistema"} ($language), ${audio.size} bytes a $TARGET_RATE Hz")
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                AppLog.log("VOZ", "Error del reconocedor: $error (${errorName(error)})")
                if (useOnDevice && error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && !downloadRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    downloadRequested = true
                    try {
                        val dl = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        dl.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language))
                        AppLog.log("VOZ", "Solicitada descarga del paquete de voz $language para reconocimiento sin conexion")
                    } catch (e: Exception) {
                        AppLog.log("VOZ", "No se pudo solicitar la descarga del idioma: ${e.message}")
                    }
                }
                done.complete(null)
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                AppLog.log("VOZ", "Resultados: ${list ?: "(nulo)"}")
                done.complete(list?.firstOrNull())
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, reader)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
        }
        try {
            recognizer.startListening(intent)
            Thread {
                try {
                    FileOutputStream(writer.fileDescriptor).use { out ->
                        var offset = 0
                        while (offset < audio.size && !done.isCompleted) {
                            val n = minOf(3200, audio.size - offset)
                            out.write(audio, offset, n)
                            offset += n
                        }
                        if (!done.isCompleted) out.write(ByteArray(TARGET_RATE))
                        out.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        writer.close()
                    } catch (_: Exception) {
                    }
                }
            }.start()
            return done.await()
        } catch (e: Exception) {
            AppLog.log("VOZ", "Fallo el reconocedor: ${e.message}")
            return null
        } finally {
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
            try {
                reader.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun downsample(pcm: ByteArray, rate: Int): ByteArray {
        if (rate == TARGET_RATE) return pcm
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = input.remaining()
        val ratio = rate.toDouble() / TARGET_RATE
        val outCount = (n / ratio).toInt()
        val out = ByteBuffer.allocate(outCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        val step = ratio.toInt().coerceAtLeast(1)
        for (i in 0 until outCount) {
            val startIdx = (i * ratio).toInt()
            var sum = 0
            var count = 0
            for (k in 0 until step) {
                val idx = startIdx + k
                if (idx < n) {
                    sum += input.get(idx).toInt()
                    count++
                }
            }
            out.putShort((if (count > 0) sum / count else 0).toShort())
        }
        return out.array()
    }
}
