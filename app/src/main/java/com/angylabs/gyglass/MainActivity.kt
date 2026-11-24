                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      package com.angylabs.gyglass

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import com.angylabs.gyglass.ble.BleManager
import com.angylabs.gyglass.service.GlassesService
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.angylabs.gyglass.log.AppLog
import com.angylabs.gyglass.ui.MainScreen
import com.angylabs.gyglass.ui.friendly.DeveloperTopBar
import com.angylabs.gyglass.ui.friendly.FriendlyApp
import com.angylabs.gyglass.ui.friendly.Palette
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.angylabs.gyglass.ui.theme.GyGlassTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREF_DEV_MODE = "dev_mode"
    }

    private val vm: MainViewModel by viewModels()

    private var pendingAction: (() -> Unit)? = null
    private var askedOnce = false
    private var askedOptional = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        AppLog.log("APP", "Permisos: " + result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        askedOnce = true
        refreshPermStatus()
        val denied = result.filterValues { !it }.keys.filter { it in blePermissions() }
        if (denied.isEmpty()) {
            continueBluetoothFlow()
        } else {
            pendingAction = null
            val msg = "Sin permisos de Bluetooth no se pueden buscar ni emparejar las gafas"
            AppLog.log("APP", msg)
            vm.speak(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPermStatus()
        if (vm.ble.isBluetoothEnabled()) {
            AppLog.log("BT", "Bluetooth activado por el usuario")
            continueBluetoothFlow()
        } else {
            pendingAction = null
            AppLog.log("BT", "El usuario no activo Bluetooth")
            Toast.makeText(this, "Bluetooth debe estar activado", Toast.LENGTH_LONG).show()
        }
    }

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (application as GyGlassApp).uiListening.value = false
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) {
            AppLog.log("APP", "Pregunta dictada: \"$text\"")
            vm.assistant.askFromApp(text)
        }
    }

    private fun startVoiceQuestion() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres saber?")
        }
        try {
            (application as GyGlassApp).uiListening.value = true
            vm.stopSpeaking()
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            (application as GyGlassApp).uiListening.value = false
            AppLog.log("APP", "No hay reconocedor de voz: ${e.message}")
            vm.speak("No encontré el reconocedor de voz del teléfono.")
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadImage(uri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent?) {
        android.util.Log.i("GyGlassDebug", "handleDebugIntent extras=${intent?.extras?.keySet()?.joinToString()}")
        if (intent?.getBooleanExtra("replay_voice", false) == true) vm.assistant.replayLastVoice()
        if (intent?.getBooleanExtra("replay_photo", false) == true) vm.assistant.replayLastPhoto()
        intent?.getStringExtra("voice_command")?.let { vm.assistant.handleSpokenCommand(it) }
        intent?.removeExtra("voice_command")
        if (intent?.hasExtra("wake") == true) vm.wakeEnabled = intent.getBooleanExtra("wake", false)
        intent?.removeExtra("wake")
        intent?.removeExtra("replay_voice")
        intent?.removeExtra("replay_photo")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDebugIntent(intent)
        AppLog.init(applicationContext)
        enableEdgeToEdge()
        vm.ble.registerReceivers()
        ensureBluetooth {
            vm.ble.refreshBonded()
            if (vm.ble.savedDevice.value != null && vm.ble.keepConnected) {
                GlassesService.start(this)
                val pm = getSystemService(PowerManager::class.java)
                if (pm?.isIgnoringBatteryOptimizations(packageName) == false) {
                    AppLog.log("APP", "Solicitando exclusion del ahorro de bateria para mantener la conexion")
                    requestIgnoreBattery()
                }
            }
        }
        setContent {
            var devMode by remember { mutableStateOf(vm.settings.getString(PREF_DEV_MODE, "0") == "1") }
            var showDeveloper by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showDeveloper) { showDeveloper = false }
            if (showDeveloper && devMode) {
                GyGlassTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Column(Modifier.padding(innerPadding)) {
                            DeveloperTopBar(onBack = { showDeveloper = false })
                            MainScreen(
                                vm = vm,
                                onPickImage = {
                                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onShareLog = { shareLog() },
                                ensureBluetooth = { action -> ensureBluetooth(action) },
                                onOpenAppSettings = { openAppSettings() },
                                onOpenBluetoothSettings = { openBluetoothSettings() },
                                onBatteryOptimization = { requestIgnoreBattery() },
                                onKeepConnected = { enabled -> setKeepConnected(enabled) }
                            )
                        }
                    }
                }
            } else {
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Palette.Background) { innerPadding ->
                    FriendlyApp(
                        vm = vm,
                        devModeEnabled = devMode,
                        onDevModeChange = { enabled ->
                            devMode = enabled
                            vm.settings.putString(PREF_DEV_MODE, if (enabled) "1" else "0")
                        },
                        onOpenDeveloper = { showDeveloper = true },
                        onTalk = { startVoiceQuestion() },
                        ensureBluetooth = { action -> ensureBluetooth(action) },
                        onBatteryOptimization = { requestIgnoreBattery() },
                        onKeepConnected = { enabled -> setKeepConnected(enabled) },
                        onOpenBluetoothSettings = { openBluetoothSettings() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
        if (missingPermissions(blePermissions()).isEmpty()) vm.ble.refreshBonded()
    }

    private fun blePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    private fun allPermissions(): List<String> {
        val list = blePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        list += Manifest.permission.RECORD_AUDIO
        return list
    }

    private fun missingPermissions(perms: List<String>): List<String> =
        perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun locationRequired(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    private fun locationEnabled(): Boolean {
        val lm = getSystemService(LocationManager::class.java) ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun refreshPermStatus() {
        val missing = missingPermissions(blePermissions())
        val permanently = askedOnce && missing.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        vm.setPermStatus(
            MainViewModel.PermStatus(
                missing = missing.map { it.substringAfterLast('.') },
                bluetoothOn = vm.ble.isBluetoothEnabled(),
                locationOn = locationEnabled(),
                locationRequired = locationRequired(),
                permanentlyDenied = permanently
            )
        )
    }

    private fun ensureBluetooth(action: () -> Unit) {
        pendingAction = action
        continueBluetoothFlow()
    }

    private fun continueBluetoothFlow() {
        refreshPermStatus()
        val missing = missingPermissions(allPermissions())
        val missingBle = missing.filter { it in blePermissions() }
        if (missingBle.isEmpty() && missing.isNotEmpty() && !askedOptional) {
            askedOptional = true
            AppLog.log("APP", "Solicitando permisos adicionales: ${missing.joinToString { it.substringAfterLast('.') }}")
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (missingBle.isNotEmpty()) {
            if (vm.permStatus.value.permanentlyDenied) {
                pendingAction = null
                val msg = "Permisos de Bluetooth denegados. Actívalos en Ajustes de la app, en Permisos, Dispositivos cercanos."
                AppLog.log("APP", msg)
                vm.speak(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                openAppSettings()
                return
            }
            AppLog.log("APP", "Solicitando permisos: ${missing.joinToString { it.substringAfterLast('.') }}")
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!vm.ble.isBluetoothEnabled()) {
            AppLog.log("BT", "Bluetooth apagado, solicitando activacion")
            try {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                AppLog.log("BT", "No se pudo pedir activar Bluetooth: ${e.message}")
                pendingAction = null
            }
            return
        }
        if (locationRequired() && !locationEnabled()) {
            pendingAction = null
            val msg = "En esta versión de Android la ubicación debe estar activada para buscar las gafas"
            AppLog.log("APP", msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun setKeepConnected(enabled: Boolean) {
        vm.ble.keepConnected = enabled
        if (enabled) {
            if (vm.ble.savedDevice.value != null || vm.ble.state.value != BleManager.ConnState.DESCONECTADO) GlassesService.start(this)
        } else {
            GlassesService.stop(this)
        }
        AppLog.log("SERVICIO", "Mantener conectado en segundo plano: $enabled")
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            Toast.makeText(this, "La app ya está excluida del ahorro de batería", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun shareLog() {
        val file = AppLog.file ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir log"))
    }
}
