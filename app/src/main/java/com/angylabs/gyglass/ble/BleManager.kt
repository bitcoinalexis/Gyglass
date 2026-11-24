package com.angylabs.gyglass.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.angylabs.gyglass.log.AppLog
import com.angylabs.gyglass.log.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleManager(context: Context) {

    enum class ConnState(val label: String) {
        DESCONECTADO("Desconectado"),
        CONECTANDO("Conectando..."),
        CONECTADO("Conectado (negociando MTU)"),
        DESCUBRIENDO("Conectado (descubriendo servicios)"),
        LISTO("Conectado y listo")
    }

    data class ScannedDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val manufacturer: String,
        val serviceUuids: List<String>,
        val isCandidate: Boolean,
        val connectable: Boolean,
        val bonded: Boolean,
        val type: String = ""
    )

    data class CharInfo(
        val serviceUuid: UUID,
        val uuid: UUID,
        val properties: Int,
        val descriptors: List<String>,
        val lastValue: String = "",
        val notifying: Boolean = false
    ) {
        val key: String get() = "$serviceUuid|$uuid"
        val canRead: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        val canWrite: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val canWriteNoResp: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val canNotify: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val canIndicate: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val anyWrite: Boolean get() = canWrite || canWriteNoResp
        val propsText: String get() = propertiesToText(properties)
    }

    data class ServiceInfo(val uuid: UUID, val chars: List<CharInfo>)

    private sealed class Op(val label: String)
    private class ReadOp(val c: BluetoothGattCharacteristic) : Op("READ ${c.uuid}")
    private class WriteOp(val c: BluetoothGattCharacteristic, val data: ByteArray, val type: Int) : Op("WRITE ${c.uuid}")
    private class DescWriteOp(val d: BluetoothGattDescriptor, val data: ByteArray) : Op("WRITE_DESC ${d.uuid} de ${d.characteristic.uuid}")
    private class DescReadOp(val d: BluetoothGattDescriptor) : Op("READ_DESC ${d.uuid} de ${d.characteristic.uuid}")

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices
    private val _state = MutableStateFlow(ConnState.DESCONECTADO)
    val state: StateFlow<ConnState> = _state
    private val _connectedDevice = MutableStateFlow<ScannedDevice?>(null)
    val connectedDevice: StateFlow<ScannedDevice?> = _connectedDevice
    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services
    private val _mtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = _mtu
    private val _rxCount = MutableStateFlow(0)
    val rxCount: StateFlow<Int> = _rxCount
    private val _lastRssi = MutableStateFlow<Int?>(null)
    val lastRssi: StateFlow<Int?> = _lastRssi

    var autoSubscribe = true
    var autoReadAll = true

    private val _bondState = MutableStateFlow(BluetoothDevice.BOND_NONE)
    val bondState: StateFlow<Int> = _bondState
    private val _bondedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val bondedDevices: StateFlow<List<ScannedDevice>> = _bondedDevices
    private val _btEnabled = MutableStateFlow(false)
    val btEnabled: StateFlow<Boolean> = _btEnabled
    private var bondTarget: String? = null
    private var connectAfterBond: String? = null
    private var receiverRegistered = false

    data class SavedDevice(val address: String, val name: String)

    private val prefs = context.applicationContext.getSharedPreferences("gyglass_ble", Context.MODE_PRIVATE)
    private val _savedDevice = MutableStateFlow(
        prefs.getString(PREF_ADDRESS, null)?.let { SavedDevice(it, prefs.getString(PREF_NAME, "") ?: "") }
    )
    val savedDevice: StateFlow<SavedDevice?> = _savedDevice
    var keepConnected: Boolean
        get() = prefs.getBoolean(PREF_KEEP, true)
        set(v) {
            prefs.edit().putBoolean(PREF_KEEP, v).apply()
        }
    var notifyListener: ((UUID, ByteArray) -> Unit)? = null
    var rawGlassesLog = false
    private var userDisconnect = false

    private val found = LinkedHashMap<String, ScannedDevice>()
    private var gatt: BluetoothGatt? = null
    private val charMap = HashMap<String, BluetoothGattCharacteristic>()
    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var discoveryStarted = false

    private val timeoutRunnable = Runnable {
        AppLog.log("GATT", "TIMEOUT en ${current?.label}")
        current = null
        next()
    }

    private val stopScanRunnable = Runnable { stopScan() }

    private val rssiRunnable = object : Runnable {
        override fun run() {
            gatt?.readRemoteRssi()
            if (gatt != null) handler.postDelayed(this, 5000)
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun startScan(durationMs: Long = 20000) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            AppLog.log("SCAN", "Bluetooth no disponible o apagado")
            return
        }
        if (_scanning.value) return
        found.clear()
        _devices.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            _scanning.value = true
            AppLog.log("SCAN", "Escaneo BLE iniciado (${durationMs / 1000}s)")
            handler.postDelayed(stopScanRunnable, durationMs)
        } catch (e: Exception) {
            AppLog.log("SCAN", "Error al iniciar escaneo: ${e.message}")
        }
        refreshBonded()
        _bondedDevices.value.forEach { d ->
            AppLog.log("SCAN", "Emparejado en sistema: ${d.name} ${d.address} tipo=${d.type}" + if (d.isCandidate) " <<< CANDIDATO GAFAS" else "")
        }
    }

    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (!_scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        _scanning.value = false
        AppLog.log("SCAN", "Escaneo detenido. Dispositivos: ${found.size}")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.log("SCAN", "Escaneo fallo, codigo=$errorCode")
            _scanning.value = false
        }
    }

    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord
        val device = result.device
        val name = record?.deviceName ?: device.name
        val manuf = StringBuilder()
        record?.manufacturerSpecificData?.let { arr ->
            for (i in 0 until arr.size()) {
                if (manuf.isNotEmpty()) manuf.append("; ")
                manuf.append("0x%04X: %s".format(arr.keyAt(i), Hex.toHex(arr.valueAt(i))))
            }
        }
        val uuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else true
        val sd = ScannedDevice(
            address = device.address,
            name = name,
            rssi = result.rssi,
            manufacturer = manuf.toString(),
            serviceUuids = uuids,
            isCandidate = isCandidate(name),
            connectable = connectable,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED
        )
        val isNew = !found.containsKey(device.address)
        found[device.address] = sd
        if (isNew) {
            AppLog.log(
                "SCAN",
                "Nuevo: ${name ?: "(sin nombre)"} ${device.address} rssi=${result.rssi} conectable=$connectable" +
                    (if (sd.isCandidate) " <<< CANDIDATO GAFAS" else "") +
                    (if (uuids.isNotEmpty()) " uuids=$uuids" else "") +
                    (if (manuf.isNotEmpty()) " manuf=[$manuf]" else "") +
                    (if (sd.isCandidate) " raw=[${Hex.toHex(record?.bytes)}]" else "")
            )
        }
        _devices.value = found.values.sortedWith(compareByDescending<ScannedDevice> { it.isCandidate }.thenByDescending { it.rssi })
    }

    fun connectSaved(): Boolean {
        val address = _savedDevice.value?.address ?: return false
        if (_state.value != ConnState.DESCONECTADO && _connectedDevice.value?.address == address) return true
        if (adapter?.isEnabled != true) {
            AppLog.log("GATT", "Bluetooth apagado, no se puede reconectar a las gafas guardadas")
            return false
        }
        AppLog.log("GATT", "Reconectando a gafas guardadas ${_savedDevice.value?.name} $address")
        connect(address, auto = true)
        return true
    }

    fun forgetSaved() {
        handler.removeCallbacks(reconnectRunnable)
        prefs.edit().remove(PREF_ADDRESS).remove(PREF_NAME).apply()
        _savedDevice.value = null
        AppLog.log("BOND", "Gafas guardadas olvidadas")
    }

    private fun saveDevice(address: String, name: String?) {
        if (_savedDevice.value?.address == address && _savedDevice.value?.name == name) return
        prefs.edit().putString(PREF_ADDRESS, address).putString(PREF_NAME, name ?: "").apply()
        _savedDevice.value = SavedDevice(address, name ?: "")
        AppLog.log("BOND", "Gafas guardadas: $name $address (se reconectaran automaticamente)")
    }

    private val reconnectRunnable = Runnable {
        if (_state.value == ConnState.DESCONECTADO && keepConnected && !userDisconnect) connectSaved()
    }

    fun connect(address: String, auto: Boolean = false) {
        stopScan()
        val a = adapter ?: return
        handler.removeCallbacks(reconnectRunnable)
        userDisconnect = false
        disconnectInternal()
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: Exception) {
            AppLog.log("GATT", "Direccion invalida $address")
            return
        }
        _connectedDevice.value = found[address] ?: ScannedDevice(address, device.name, 0, "", emptyList(), isCandidate(device.name), true, device.bondState == BluetoothDevice.BOND_BONDED)
        bondTarget = address
        _bondState.value = device.bondState
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            AppLog.log("BOND", "Aviso: $address no esta emparejado. Si las caracteristicas devuelven INSUFFICIENT_AUTH usa 'Emparejar y conectar'.")
        }
        _state.value = ConnState.CONECTANDO
        discoveryStarted = false
        AppLog.log("GATT", "Conectando a ${device.name} $address (autoConnect=$auto) ...")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, auto, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, auto, gattCallback)
        }
    }

    fun bond(address: String) {
        val device = adapter?.getRemoteDevice(address) ?: return
        bondTarget = address
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            _bondState.value = BluetoothDevice.BOND_BONDED
            AppLog.log("BOND", "$address ya esta emparejado")
            return
        }
        _bondState.value = BluetoothDevice.BOND_BONDING
        val ok = device.createBond()
        AppLog.log("BOND", "createBond($address) -> $ok, estado actual=${bondName(device.bondState)}")
        if (!ok) _bondState.value = device.bondState
    }

    fun pairAndConnect(address: String) {
        stopScan()
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        } ?: return
        bondTarget = address
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            AppLog.log("BOND", "$address ya emparejado, conectando")
            connect(address)
            return
        }
        connectAfterBond = address
        _bondState.value = BluetoothDevice.BOND_BONDING
        val ok = device.createBond()
        AppLog.log("BOND", "Emparejar y conectar: createBond($address) -> $ok")
        if (!ok) {
            connectAfterBond = null
            _bondState.value = device.bondState
            AppLog.log("BOND", "No se pudo iniciar el emparejamiento, se intenta conectar sin emparejar")
            connect(address)
        }
    }

    fun removeBond(address: String) {
        val device = adapter?.getRemoteDevice(address) ?: return
        val ok = try {
            device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            AppLog.log("BOND", "removeBond no disponible: ${e.message}")
            false
        }
        AppLog.log("BOND", "removeBond($address) -> $ok")
        refreshBonded()
    }

    fun refreshBonded() {
        _btEnabled.value = adapter?.isEnabled == true
        val list = try {
            adapter?.bondedDevices.orEmpty().map { d ->
                ScannedDevice(
                    address = d.address,
                    name = d.name,
                    rssi = 0,
                    manufacturer = "",
                    serviceUuids = emptyList(),
                    isCandidate = isCandidate(d.name),
                    connectable = true,
                    bonded = true,
                    type = typeName(d.type)
                )
            }.sortedByDescending { it.isCandidate }
        } catch (e: SecurityException) {
            AppLog.log("BOND", "Sin permiso BLUETOOTH_CONNECT para listar emparejados")
            emptyList()
        }
        _bondedDevices.value = list
    }

    fun registerReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(appContext, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        refreshBonded()
    }

    fun unregisterReceivers() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(btReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val address = device?.address
            val name = try {
                device?.name
            } catch (_: SecurityException) {
                null
            }
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    AppLog.log("BOND", "$name $address ${bondName(prev)} -> ${bondName(state)}")
                    val tracked = address != null && (address == bondTarget || address == _connectedDevice.value?.address)
                    if (tracked) _bondState.value = state
                    if (state == BluetoothDevice.BOND_BONDED && tracked && address != null) saveDevice(address, name)
                    if (state == BluetoothDevice.BOND_BONDED && address != null && address == connectAfterBond) {
                        connectAfterBond = null
                        AppLog.log("BOND", "Emparejado correctamente, conectando por BLE")
                        handler.postDelayed({ connect(address) }, 800)
                    }
                    if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING && tracked) {
                        AppLog.log("BOND", "Emparejamiento rechazado o fallido en $address")
                        if (address == connectAfterBond) {
                            connectAfterBond = null
                            AppLog.log("BOND", "Se intenta conectar sin emparejar")
                            handler.postDelayed({ connect(address) }, 800)
                        }
                    }
                    refreshBonded()
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                    AppLog.log("BOND", "Solicitud de emparejamiento de $name $address variante=$variant clave=$key (acepta el dialogo del sistema)")
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _btEnabled.value = s == BluetoothAdapter.STATE_ON
                    AppLog.log("BT", "Adaptador Bluetooth: ${if (s == BluetoothAdapter.STATE_ON) "ENCENDIDO" else if (s == BluetoothAdapter.STATE_OFF) "APAGADO" else s.toString()}")
                    if (s == BluetoothAdapter.STATE_ON) {
                        refreshBonded()
                        if (keepConnected && !userDisconnect && _savedDevice.value != null) {
                            handler.removeCallbacks(reconnectRunnable)
                            handler.postDelayed(reconnectRunnable, 1500)
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> AppLog.log("BT", "Enlace ACL conectado: $name $address")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> AppLog.log("BT", "Enlace ACL desconectado: $name $address")
            }
        }
    }

    fun disconnect() {
        AppLog.log("GATT", "Desconexion solicitada por el usuario")
        userDisconnect = true
        handler.removeCallbacks(reconnectRunnable)
        val g = gatt ?: run {
            _state.value = ConnState.DESCONECTADO
            return
        }
        g.disconnect()
        handler.postDelayed({
            if (gatt === g) {
                g.close()
                onDisconnected()
            }
        }, 2500)
    }

    private fun disconnectInternal() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        queue.clear()
        current = null
        charMap.clear()
    }

    private fun onDisconnected() {
        handler.removeCallbacks(rssiRunnable)
        handler.removeCallbacks(timeoutRunnable)
        gatt = null
        queue.clear()
        current = null
        charMap.clear()
        _state.value = ConnState.DESCONECTADO
        _services.value = emptyList()
        _lastRssi.value = null
        val saved = _savedDevice.value
        if (keepConnected && !userDisconnect && saved != null && saved.address == _connectedDevice.value?.address) {
            AppLog.log("GATT", "Conexion perdida con las gafas guardadas, reintento automatico en 3 s")
            handler.removeCallbacks(reconnectRunnable)
            handler.postDelayed(reconnectRunnable, 3000)
        }
    }

    fun rediscover() {
        val g = gatt ?: return
        _state.value = ConnState.DESCUBRIENDO
        AppLog.log("GATT", "Redescubriendo servicios -> ${g.discoverServices()}")
    }

    private fun startDiscovery() {
        if (discoveryStarted) return
        discoveryStarted = true
        _state.value = ConnState.DESCUBRIENDO
        handler.postDelayed({
            val ok = gatt?.discoverServices()
            AppLog.log("GATT", "discoverServices() -> $ok")
        }, 300)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                AppLog.log("GATT", "onConnectionStateChange status=$status newState=${stateName(newState)}")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt = g
                    _state.value = ConnState.CONECTADO
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    }
                    val mtuOk = g.requestMtu(517)
                    AppLog.log("GATT", "requestMtu(517) -> $mtuOk")
                    if (!mtuOk) startDiscovery()
                    handler.postDelayed({ startDiscovery() }, 3000)
                    handler.removeCallbacks(rssiRunnable)
                    handler.postDelayed(rssiRunnable, 2000)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    if (gatt === g || gatt == null) onDisconnected()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                AppLog.log("GATT", "MTU=$mtu status=$status")
                _mtu.value = mtu
                startDiscovery()
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _lastRssi.value = rssi
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                AppLog.log("GATT", "onServicesDiscovered status=$status servicios=${g.services.size}")
                buildServices(g)
                dumpTree()
                _state.value = ConnState.LISTO
                _connectedDevice.value?.let { saveDevice(it.address, it.name ?: g.device.name) }
                if (autoSubscribe) subscribeAll()
                if (autoReadAll) readAll()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handleRead(c, c.value, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(c, value, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            AppLog.log("TX", "ACK write ${c.uuid} status=${statusName(status)}")
            complete()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handleNotify(c, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            AppLog.log("GATT", "Descriptor ${d.uuid} de ${d.characteristic.uuid} escrito status=${statusName(status)}")
            if (d.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                updateChar(keyOf(d.characteristic)) { it.copy(notifying = true) }
            }
            complete()
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handleDescRead(d, d.value, status)
        }

        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) {
            handleDescRead(d, value, status)
        }
    }

    private fun handleRead(c: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        AppLog.log("READ", "${c.uuid} status=${statusName(status)} ${Hex.describe(value)}")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            updateChar(keyOf(c)) { it.copy(lastValue = "R: ${Hex.toHex(value)} | ${Hex.toAscii(value)}") }
        }
        complete()
    }

    private fun handleDescRead(d: BluetoothGattDescriptor, value: ByteArray?, status: Int) {
        AppLog.log("READ", "Descriptor ${d.uuid} de ${d.characteristic.uuid} status=${statusName(status)} ${Hex.describe(value)}")
        complete()
    }

    private fun handleNotify(c: BluetoothGattCharacteristic, value: ByteArray?) {
        _rxCount.value = _rxCount.value + 1
        if (value != null) {
            try {
                notifyListener?.invoke(c.uuid, value)
            } catch (e: Exception) {
                AppLog.log("RX", "Error procesando notificacion: ${e.message}")
            }
        }
        if (c.uuid == GLASSES_NOTIFY && !rawGlassesLog) return
        AppLog.log("RX", "NOTIFY ${c.uuid} ${Hex.describe(value)}")
        updateChar(keyOf(c)) { it.copy(lastValue = "N: ${Hex.toHex(value)} | ${Hex.toAscii(value)}") }
    }

    private fun buildServices(g: BluetoothGatt) {
        charMap.clear()
        _services.value = g.services.map { s ->
            ServiceInfo(s.uuid, s.characteristics.map { c ->
                charMap[keyOf(c)] = c
                CharInfo(
                    serviceUuid = s.uuid,
                    uuid = c.uuid,
                    properties = c.properties,
                    descriptors = c.descriptors.map { d -> "${d.uuid}${knownName(d.uuid)}" }
                )
            })
        }
    }

    private fun dumpTree() {
        val sb = StringBuilder("ARBOL GATT completo:\n")
        _services.value.forEach { s ->
            sb.append("  SERVICIO ${s.uuid}${knownName(s.uuid)}\n")
            s.chars.forEach { c ->
                sb.append("    CARACT ${c.uuid}${knownName(c.uuid)} props=[${c.propsText}]\n")
                c.descriptors.forEach { d -> sb.append("      DESC $d\n") }
            }
        }
        AppLog.log("GATT", sb.toString().trimEnd())
    }

    fun subscribeAll() {
        val g = gatt ?: return
        var count = 0
        charMap.values.forEach { c ->
            val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!notify && !indicate) return@forEach
            val local = g.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(CCCD)
            if (cccd == null) {
                AppLog.log("GATT", "Sin CCCD en ${c.uuid}, setCharacteristicNotification=$local")
                return@forEach
            }
            val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            enqueue(DescWriteOp(cccd, value))
            count++
        }
        AppLog.log("GATT", "Suscripcion solicitada en $count caracteristicas NOTIFY/INDICATE")
    }

    fun readAll() {
        var count = 0
        charMap.values.forEach { c ->
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                enqueue(ReadOp(c))
                count++
            }
            c.descriptors.filter { it.uuid != CCCD }.forEach { enqueue(DescReadOp(it)) }
        }
        AppLog.log("GATT", "Lectura solicitada de $count caracteristicas READ")
    }

    fun read(key: String) {
        val c = charMap[key] ?: return
        enqueue(ReadOp(c))
    }

    fun writeHex(key: String, hex: String, label: String = "CMD"): Boolean {
        val c = charMap[key]
        if (c == null) {
            AppLog.log("TX", "Caracteristica no seleccionada o no disponible")
            return false
        }
        val data = Hex.parse(hex)
        if (data == null) {
            AppLog.log("TX", "Hex invalido: \"$hex\"")
            return false
        }
        return write(c, data, label)
    }

    private fun write(c: BluetoothGattCharacteristic, data: ByteArray, label: String): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        AppLog.log("TX", "[$label] -> ${c.uuid} tipo=${if (type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) "WRITE" else "WRITE_NO_RESP"} ${Hex.describe(data)}")
        enqueue(WriteOp(c, data, type))
        return true
    }

    fun writeTo(charUuid: UUID, data: ByteArray, label: String): Boolean {
        val c = charMap.values.firstOrNull { it.uuid == charUuid }
        if (c == null) {
            AppLog.log("TX", "[$label] caracteristica $charUuid no disponible (gafas no conectadas?)")
            return false
        }
        AppLog.log("TX", "[$label] -> ${c.uuid} WRITE_NO_RESP ${Hex.describe(data)}")
        enqueue(WriteOp(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
        return true
    }

    fun writeToAllWritable(hex: String) {
        val data = Hex.parse(hex)
        if (data == null) {
            AppLog.log("TX", "Hex invalido: \"$hex\"")
            return
        }
        val targets = charMap.values.filter {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }
        AppLog.log("TX", "Probando escritura en ${targets.size} caracteristicas WRITE")
        targets.forEach { write(it, data, "PRUEBA") }
    }

    private fun enqueue(op: Op) {
        handler.post {
            queue.addLast(op)
            if (current == null) next()
        }
    }

    private fun complete() {
        handler.post {
            handler.removeCallbacks(timeoutRunnable)
            current = null
            next()
        }
    }

    private fun next() {
        handler.removeCallbacks(timeoutRunnable)
        if (current != null) return
        val g = gatt
        if (g == null) {
            queue.clear()
            return
        }
        val op = queue.removeFirstOrNull() ?: return
        current = op
        val ok = try {
            execute(g, op)
        } catch (e: Exception) {
            AppLog.log("GATT", "Excepcion en ${op.label}: ${e.message}")
            false
        }
        if (!ok) {
            AppLog.log("GATT", "Fallo al ejecutar ${op.label}")
            current = null
            handler.post { next() }
            return
        }
        handler.postDelayed(timeoutRunnable, 4000)
    }

    private fun execute(g: BluetoothGatt, op: Op): Boolean = when (op) {
        is ReadOp -> g.readCharacteristic(op.c)
        is DescReadOp -> g.readDescriptor(op.d)
        is WriteOp -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(op.c, op.data, op.type) == BluetoothStatusCodes.SUCCESS
        } else {
            op.c.writeType = op.type
            op.c.value = op.data
            g.writeCharacteristic(op.c)
        }
        is DescWriteOp -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(op.d, op.data) == BluetoothStatusCodes.SUCCESS
        } else {
            op.d.value = op.data
            g.writeDescriptor(op.d)
        }
    }

    private fun updateChar(key: String, transform: (CharInfo) -> CharInfo) {
        handler.post {
            _services.value = _services.value.map { s ->
                if (s.chars.none { it.key == key }) s else s.copy(chars = s.chars.map { if (it.key == key) transform(it) else it })
            }
        }
    }

    private fun keyOf(c: BluetoothGattCharacteristic) = "${c.service.uuid}|${c.uuid}"

    companion object {
        private val GLASSES_NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        private const val PREF_KEEP = "keep_connected"
        private const val PREF_ADDRESS = "saved_address"
        private const val PREF_NAME = "saved_name"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val CANDIDATE_PATTERNS = listOf("AIMB", "G2", "CYAN", "GLASS", "JL", "AI-G")

        fun isCandidate(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            val up = name.uppercase()
            return CANDIDATE_PATTERNS.any { up.contains(it) }
        }

        fun propertiesToText(p: Int): String {
            val list = mutableListOf<String>()
            if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) list += "BROADCAST"
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) list += "READ"
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list += "WRITE_NO_RESPONSE"
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list += "WRITE"
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list += "NOTIFY"
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list += "INDICATE"
            if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) list += "SIGNED_WRITE"
            if (p and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) list += "EXTENDED"
            return list.joinToString(", ")
        }

        fun knownName(uuid: UUID): String {
            val s = uuid.toString().lowercase()
            if (!s.endsWith("-0000-1000-8000-00805f9b34fb")) return ""
            val short = s.substring(4, 8)
            val name = when (short) {
                "1800" -> "Generic Access"
                "1801" -> "Generic Attribute"
                "180a" -> "Device Information"
                "180f" -> "Battery Service"
                "1812" -> "HID"
                "fe2c" -> "Google Fast Pair"
                "2a00" -> "Device Name"
                "2a01" -> "Appearance"
                "2a04" -> "Conn Params"
                "2a05" -> "Service Changed"
                "2a19" -> "Battery Level"
                "2a24" -> "Model Number"
                "2a25" -> "Serial Number"
                "2a26" -> "Firmware Rev"
                "2a27" -> "Hardware Rev"
                "2a28" -> "Software Rev"
                "2a29" -> "Manufacturer"
                "2a50" -> "PnP ID"
                "2902" -> "CCCD"
                "2901" -> "User Description"
                "ae00", "ae01", "ae02", "ae03", "ae04", "ae05", "ae10" -> "Jieli (posible RCSP)"
                else -> return " (0x$short)"
            }
            return " ($name)"
        }

        fun bondName(s: Int) = when (s) {
            BluetoothDevice.BOND_BONDED -> "EMPAREJADO"
            BluetoothDevice.BOND_BONDING -> "EMPAREJANDO"
            BluetoothDevice.BOND_NONE -> "NO_EMPAREJADO"
            else -> s.toString()
        }

        fun typeName(t: Int) = when (t) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Clasico"
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual (Clasico+BLE)"
            else -> "Desconocido"
        }

        private fun stateName(s: Int) = when (s) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> s.toString()
        }

        private fun statusName(s: Int) = when (s) {
            BluetoothGatt.GATT_SUCCESS -> "OK"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "READ_NOT_PERMITTED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "WRITE_NOT_PERMITTED"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFFICIENT_AUTH"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFFICIENT_ENCRYPTION"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "NOT_SUPPORTED"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "INVALID_LENGTH"
            else -> "0x%02X".format(s)
        }
    }
}
