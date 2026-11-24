package com.angylabs.gyglass

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.angylabs.gyglass.ai.AiProvider
import com.angylabs.gyglass.ai.AiService
import com.angylabs.gyglass.ai.AiSettings
import com.angylabs.gyglass.ble.BleManager
import com.angylabs.gyglass.log.AppLog
import com.angylabs.gyglass.wifi.WifiDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class Command(val id: String, val label: String, val defaultHex: String)

    data class PermStatus(
        val missing: List<String> = emptyList(),
        val bluetoothOn: Boolean = false,
        val locationOn: Boolean = true,
        val locationRequired: Boolean = false,
        val permanentlyDenied: Boolean = false
    ) {
        val ready: Boolean get() = missing.isEmpty() && bluetoothOn && (!locationRequired || locationOn)
    }

    private val _permStatus = MutableStateFlow(PermStatus())
    val permStatus: StateFlow<PermStatus> = _permStatus

    fun setPermStatus(s: PermStatus) {
        _permStatus.value = s
    }

    private val gyApp = app as GyGlassApp
    val ble: BleManager = gyApp.ble
    val wifi = WifiDiscovery(app, viewModelScope)
    val settings: AiSettings = gyApp.aiSettings
    private val ai: AiService = gyApp.aiService
    val assistant = gyApp.assistant
    val wakeStatus = gyApp.wake.status
    val battery = gyApp.protocol.battery

    var wakeEnabled: Boolean
        get() = gyApp.wakeEnabled
        set(v) {
            gyApp.wakeEnabled = v
        }

    val commands = listOf(
        Command("photo", "Tomar Foto", "01"),
        Command("video_start", "Iniciar Video", "02"),
        Command("video_stop", "Detener Video", "03")
    )

    private val _commandHex = MutableStateFlow(commands.associate { it.id to settings.getString("cmd_${it.id}", it.defaultHex) })
    val commandHex: StateFlow<Map<String, String>> = _commandHex
    private val _targetKey = MutableStateFlow(settings.getString("target_char", ""))
    val targetKey: StateFlow<String> = _targetKey

    private val _aiStatus = MutableStateFlow<Map<AiProvider, String>>(emptyMap())
    val aiStatus: StateFlow<Map<AiProvider, String>> = _aiStatus
    private val _aiAnswer = MutableStateFlow("")
    val aiAnswer: StateFlow<String> = _aiAnswer
    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy
    private val _image = MutableStateFlow<ByteArray?>(null)
    val image: StateFlow<ByteArray?> = _image

    fun setTarget(key: String) {
        _targetKey.value = key
        settings.putString("target_char", key)
    }

    fun setCommandHex(id: String, hex: String) {
        _commandHex.value = _commandHex.value + (id to hex)
        settings.putString("cmd_$id", hex)
    }

    fun sendCommand(id: String) {
        val cmd = commands.first { it.id == id }
        ble.writeHex(_targetKey.value, _commandHex.value[id] ?: "", cmd.label)
    }

    fun sendCustom(hex: String) {
        ble.writeHex(_targetKey.value, hex, "MANUAL")
    }

    fun saveAi(p: AiProvider, key: String, model: String) {
        settings.save(p, key, model)
        _aiStatus.value = _aiStatus.value + (p to "Guardado")
    }

    fun testAi(p: AiProvider) {
        _aiStatus.value = _aiStatus.value + (p to "Probando...")
        viewModelScope.launch {
            val r = ai.test(p)
            _aiStatus.value = _aiStatus.value + (p to r.fold({ "OK - $it" }, { "ERROR - ${it.message}" }))
        }
    }

    fun ask(p: AiProvider, prompt: String) {
        if (_aiBusy.value) return
        _aiBusy.value = true
        _aiAnswer.value = "Consultando ${p.label}..."
        viewModelScope.launch {
            val r = ai.ask(p, prompt.ifBlank { "Describe lo que ves." }, _image.value)
            _aiAnswer.value = r.fold({ it }, { "Error: ${it.message}" })
            _aiBusy.value = false
        }
    }

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            _image.value = withContext(Dispatchers.IO) { compressImage(uri) }
            AppLog.log("IA", "Imagen cargada: ${_image.value?.size ?: 0} bytes")
        }
    }

    fun clearImage() {
        _image.value = null
    }

    private fun compressImage(uri: Uri): ByteArray? = try {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 3000 || bounds.outHeight / sample > 3000) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bmp?.let {
            val maxSide = 1568f
            val scale = minOf(1f, maxSide / maxOf(it.width, it.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true) else it
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
    } catch (e: Exception) {
        AppLog.log("IA", "Error al cargar imagen: ${e.message}")
        null
    }

    fun speak(text: String) {
        gyApp.speak(text)
    }

    fun stopSpeaking() {
        gyApp.stopSpeaking()
    }

    override fun onCleared() {
        ble.stopScan()
        wifi.dispose()
        super.onCleared()
    }
}
