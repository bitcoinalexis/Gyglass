package com.angylabs.gyglass

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.angylabs.gyglass.glasses.WakeWordEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import com.angylabs.gyglass.ai.AiService
import com.angylabs.gyglass.ai.AiSettings
import com.angylabs.gyglass.ble.BleManager
import com.angylabs.gyglass.glasses.GlassesAssistant
import com.angylabs.gyglass.glasses.GlassesProtocol
import com.angylabs.gyglass.log.AppLog
import com.angylabs.gyglass.service.GlassesService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class GyGlassApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var ble: BleManager
        private set
    lateinit var aiSettings: AiSettings
        private set
    lateinit var aiService: AiService
        private set
    lateinit var assistant: GlassesAssistant
        private set
    lateinit var protocol: GlassesProtocol
        private set
    private val batteryPoll = object : Runnable {
        override fun run() {
            if (ble.state.value == BleManager.ConnState.LISTO) {
                ble.writeTo(GlassesProtocol.WRITE, GlassesProtocol.frame(GlassesProtocol.CMD_BATTERY, GlassesProtocol.BATTERY_PAYLOAD), "BATERIA")
                mainHandler.postDelayed(this, 60000)
            }
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    lateinit var wake: WakeWordEngine
        private set
    private val speaking = MutableStateFlow(false)
    val uiListening = MutableStateFlow(false)

    var wakeEnabled: Boolean
        get() = aiSettings.getString("wake_enabled", "0") == "1"
        set(v) {
            aiSettings.putString("wake_enabled", if (v) "1" else "0")
            AppLog.log("CLEO", "Escucha de \"Hey CLEO\": ${if (v) "activada" else "desactivada"}")
            if (v) {
                GlassesService.start(this)
            } else {
                wake.stop()
            }
        }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        ble = BleManager(this)
        ble.registerReceivers()
        aiSettings = AiSettings(this)
        aiService = AiService(aiSettings)
        assistant = GlassesAssistant(this, appScope, aiSettings, aiService) { speak(it) }
        protocol = GlassesProtocol(assistant)
        ble.notifyListener = { uuid, value -> if (uuid == GlassesProtocol.NOTIFY) protocol.feed(value) }
        protocol.sender = { data, label -> ble.writeTo(GlassesProtocol.WRITE, data, label) }
        assistant.photoRequester = {
            ble.writeTo(GlassesProtocol.WRITE, GlassesProtocol.frame(GlassesProtocol.CMD_CONTROL, GlassesProtocol.AI_PHOTO_PAYLOAD), "FOTO IA")
        }
        wake = WakeWordEngine(this, appScope, { q -> assistant.handleSpokenCommand(q) }, { assistant.onWakeWord() })
        appScope.launch {
            combine(speaking, assistant.busy, assistant.awaitingPhoto, protocol.transferring, uiListening) { s, b, a, t, u -> s || b || a || t || u }
                .collect { wake.paused = it }
        }
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.forLanguageTag("es-ES")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    speaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speaking.value = false
                }
            })
        }
        appScope.launch {
            var previous: BleManager.ConnState? = null
            ble.state.collect { s ->
                mainHandler.removeCallbacks(batteryPoll)
                if (s == BleManager.ConnState.LISTO) mainHandler.postDelayed(batteryPoll, 2500)
                if (s == BleManager.ConnState.CONECTANDO || s == BleManager.ConnState.LISTO) {
                    if (ble.keepConnected && !GlassesService.running) GlassesService.start(this@GyGlassApp)
                }
                if (previous != null && previous != s) {
                    when (s) {
                        BleManager.ConnState.LISTO -> speak("Gafas conectadas")
                        BleManager.ConnState.DESCONECTADO -> if (previous == BleManager.ConnState.LISTO) speak("Gafas desconectadas")
                        else -> {}
                    }
                }
                previous = s
            }
        }
    }

    fun speak(text: String) {
        if (ttsReady) {
            speaking.value = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gyglass_${System.nanoTime()}")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }
}
