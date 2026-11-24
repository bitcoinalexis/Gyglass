package com.angylabs.gyglass.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.bluetooth.BluetoothDevice
import com.angylabs.gyglass.MainViewModel
import com.angylabs.gyglass.ai.AiProvider
import com.angylabs.gyglass.ble.BleManager
import com.angylabs.gyglass.log.AppLog

private val tabs = listOf("Gafas BLE", "GATT", "Comandos", "WiFi", "IA", "Log")

@Composable
fun MainScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit,
    onShareLog: () -> Unit,
    ensureBluetooth: (() -> Unit) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onBatteryOptimization: () -> Unit,
    onKeepConnected: (Boolean) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        ConnectionBanner(vm)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            when (tab) {
                0 -> ScanTab(vm, ensureBluetooth, onOpenAppSettings, onOpenBluetoothSettings, onBatteryOptimization, onKeepConnected)
                1 -> GattTab(vm)
                2 -> CommandsTab(vm)
                3 -> WifiTab(vm)
                4 -> AiTab(vm, onPickImage)
                5 -> LogTab(onShareLog)
            }
        }
    }
}

@Composable
private fun ConnectionBanner(vm: MainViewModel) {
    val state by vm.ble.state.collectAsState()
    val device by vm.ble.connectedDevice.collectAsState()
    val services by vm.ble.services.collectAsState()
    val mtu by vm.ble.mtu.collectAsState()
    val rx by vm.ble.rxCount.collectAsState()
    val rssi by vm.ble.lastRssi.collectAsState()
    val aiStatus by vm.aiStatus.collectAsState()
    val bond by vm.ble.bondState.collectAsState()
    val perm by vm.permStatus.collectAsState()
    val color = when (state) {
        BleManager.ConnState.LISTO -> Color(0xFF1B5E20)
        BleManager.ConnState.DESCONECTADO -> Color(0xFFB71C1C)
        else -> Color(0xFFE65100)
    }
    val title = when (state) {
        BleManager.ConnState.DESCONECTADO -> "Gafas NO conectadas"
        BleManager.ConnState.LISTO -> "Gafas CONECTADAS: ${device?.name ?: "sin nombre"}"
        else -> "${state.label} ${device?.name ?: ""}"
    }
    val notifying = services.sumOf { s -> s.chars.count { it.notifying } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(color)
            .padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (state != BleManager.ConnState.DESCONECTADO) {
            Text(
                "${device?.address ?: ""}  MTU $mtu  RSSI ${rssi ?: "-"}  servicios ${services.size}  notif $notifying  RX $rx",
                color = Color.White, fontSize = 12.sp
            )
        }
        val bondText = when (bond) {
            BluetoothDevice.BOND_BONDED -> "Emparejadas: Sí"
            BluetoothDevice.BOND_BONDING -> "Emparejando..."
            else -> "Emparejadas: No"
        }
        val btText = when {
            perm.missing.isNotEmpty() -> "Permisos Bluetooth: FALTAN"
            !perm.bluetoothOn -> "Bluetooth: APAGADO"
            else -> "Bluetooth: OK"
        }
        val battery by vm.battery.collectAsState()
        val batteryText = battery?.let { "   Batería gafas: ${it.level}%${if (it.charging) " (cargando)" else ""}" } ?: ""
        Text("$btText   $bondText$batteryText", color = Color.White, fontSize = 12.sp)
        val ai = AiProvider.entries.joinToString("  ") { p ->
            val s = aiStatus[p]
            val mark = when {
                s == null -> "?"
                s.startsWith("OK") -> "OK"
                s.startsWith("ERROR") -> "ERR"
                else -> "..."
            }
            "${p.name}:$mark"
        }
        Text("IA  $ai", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}

@Composable
private fun ScanTab(
    vm: MainViewModel,
    ensureBluetooth: (() -> Unit) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onBatteryOptimization: () -> Unit,
    onKeepConnected: (Boolean) -> Unit
) {
    val saved by vm.ble.savedDevice.collectAsState()
    val scanning by vm.ble.scanning.collectAsState()
    val devices by vm.ble.devices.collectAsState()
    val bonded by vm.ble.bondedDevices.collectAsState()
    val state by vm.ble.state.collectAsState()
    val perm by vm.permStatus.collectAsState()
    var onlyGlasses by rememberSaveable { mutableStateOf(false) }
    val shown = if (onlyGlasses) devices.filter { it.isCandidate } else devices
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(8.dp))
            PermissionCard(perm, ensureBluetooth, onOpenAppSettings, onOpenBluetoothSettings)
            Spacer(Modifier.height(8.dp))
            SavedGlassesCard(vm, saved, state, ensureBluetooth, onBatteryOptimization, onKeepConnected)
            Text(
                "Pasos: 1) Conceder permisos y encender Bluetooth. 2) Escanear. 3) En las gafas pulsa 'Emparejar y conectar' y acepta el diálogo de emparejamiento del sistema.",
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            ButtonRow {
                Button(onClick = { if (scanning) vm.ble.stopScan() else ensureBluetooth { vm.ble.startScan() } }) {
                    Text(if (scanning) "Detener escaneo" else "Escanear BLE")
                }
                if (state != BleManager.ConnState.DESCONECTADO) {
                    OutlinedButton(onClick = { vm.ble.disconnect() }) { Text("Desconectar") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = onlyGlasses, onCheckedChange = { onlyGlasses = it })
                Text("Solo nombres con AIMB / G2 / Cyan / Glass / JL")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var auto by remember { mutableStateOf(vm.ble.autoSubscribe) }
                Checkbox(checked = auto, onCheckedChange = { auto = it; vm.ble.autoSubscribe = it; vm.ble.autoReadAll = it })
                Text("Al conectar: suscribir NOTIFY/INDICATE y leer todo")
            }
            if (bonded.isNotEmpty()) SectionTitle("Emparejados en el sistema (${bonded.size})")
        }
        items(bonded, key = { "b" + it.address }) { d ->
            DeviceCard(d, vm, ensureBluetooth, showUnpair = true)
        }
        item {
            SectionTitle("Encontrados por escaneo: ${shown.size} de ${devices.size}")
        }
        items(shown, key = { it.address }) { d ->
            DeviceCard(d, vm, ensureBluetooth, showUnpair = false)
        }
    }
}

@Composable
private fun SavedGlassesCard(
    vm: MainViewModel,
    saved: BleManager.SavedDevice?,
    state: BleManager.ConnState,
    ensureBluetooth: (() -> Unit) -> Unit,
    onBatteryOptimization: () -> Unit,
    onKeepConnected: (Boolean) -> Unit
) {
    var keep by remember { mutableStateOf(vm.ble.keepConnected) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("Gafas guardadas", fontWeight = FontWeight.Bold)
            if (saved == null) {
                Text("Ninguna. Se guardan automáticamente al emparejar o conectar.", fontSize = 12.sp)
            } else {
                Text("${saved.name.ifBlank { "(sin nombre)" }}  ${saved.address}", fontSize = 12.sp)
                ButtonRow {
                    if (state == BleManager.ConnState.DESCONECTADO) {
                        Button(onClick = { ensureBluetooth { vm.ble.connectSaved() } }) { Text("Reconectar") }
                    }
                    OutlinedButton(onClick = { vm.ble.forgetSaved() }) { Text("Olvidar gafas") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = keep, onCheckedChange = { keep = it; onKeepConnected(it) })
                Text("Mantener conectado en segundo plano y reconectar automáticamente")
            }
            OutlinedButton(onClick = onBatteryOptimization) { Text("Excluir del ahorro de batería") }
        }
    }
}

@Composable
private fun PermissionCard(
    perm: MainViewModel.PermStatus,
    ensureBluetooth: (() -> Unit) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val ok = perm.ready
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(10.dp)) {
            Text(
                if (ok) "Bluetooth listo" else "Bluetooth requiere atención",
                fontWeight = FontWeight.Bold,
                color = if (ok) Color(0xFF1B5E20) else Color(0xFFB71C1C)
            )
            Text(
                if (perm.missing.isEmpty()) "Permisos: concedidos" else "Permisos faltantes: ${perm.missing.joinToString()}",
                fontSize = 12.sp
            )
            Text("Bluetooth: ${if (perm.bluetoothOn) "encendido" else "apagado"}", fontSize = 12.sp)
            if (perm.locationRequired) Text("Ubicación: ${if (perm.locationOn) "activada" else "desactivada"}", fontSize = 12.sp)
            ButtonRow {
                if (!ok) Button(onClick = { ensureBluetooth { } }) { Text("Solicitar permisos Bluetooth") }
                if (perm.permanentlyDenied) OutlinedButton(onClick = onOpenAppSettings) { Text("Ajustes de la app") }
                OutlinedButton(onClick = onOpenBluetoothSettings) { Text("Ajustes Bluetooth") }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    d: BleManager.ScannedDevice,
    vm: MainViewModel,
    ensureBluetooth: (() -> Unit) -> Unit,
    showUnpair: Boolean
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(
                (d.name ?: "(sin nombre)") + if (d.isCandidate) "  [CANDIDATO]" else "",
                fontWeight = FontWeight.Bold,
                color = if (d.isCandidate) Color(0xFF1B5E20) else Color.Unspecified
            )
            Text(
                "MAC ${d.address}" +
                    (if (d.rssi != 0) "   RSSI ${d.rssi} dBm" else "") +
                    (if (d.type.isNotEmpty()) "   ${d.type}" else "") +
                    (if (!d.connectable) "   no conectable" else "") +
                    (if (d.bonded) "   emparejado" else "   sin emparejar"),
                fontSize = 12.sp
            )
            if (d.manufacturer.isNotEmpty()) Mono("Manufacturer: ${d.manufacturer}")
            if (d.serviceUuids.isNotEmpty()) Mono("UUIDs anunciados: ${d.serviceUuids.joinToString()}")
            ButtonRow {
                Button(onClick = { ensureBluetooth { vm.ble.pairAndConnect(d.address) } }) {
                    Text(if (d.bonded) "Conectar" else "Emparejar y conectar")
                }
                if (!d.bonded) OutlinedButton(onClick = { ensureBluetooth { vm.ble.connect(d.address) } }) { Text("Conectar sin emparejar") }
                if (!d.bonded) OutlinedButton(onClick = { ensureBluetooth { vm.ble.bond(d.address) } }) { Text("Solo emparejar") }
                if (showUnpair) OutlinedButton(onClick = { ensureBluetooth { vm.ble.removeBond(d.address) } }) { Text("Olvidar") }
            }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
private fun GattTab(vm: MainViewModel) {
    val services by vm.ble.services.collectAsState()
    val target by vm.targetKey.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(8.dp))
            ButtonRow {
                Button(onClick = { vm.ble.subscribeAll() }) { Text("Suscribir todo") }
                Button(onClick = { vm.ble.readAll() }) { Text("Leer todo") }
                OutlinedButton(onClick = { vm.ble.rediscover() }) { Text("Redescubrir") }
            }
            if (services.isEmpty()) Text("Sin servicios. Conecta las gafas en la pestaña Gafas BLE.", Modifier.padding(top = 12.dp))
        }
        services.forEach { s ->
            item(key = s.uuid.toString()) {
                SectionTitle("Servicio ${s.uuid}${BleManager.knownName(s.uuid)}")
            }
            items(s.chars, key = { it.key }) { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Mono("${c.uuid}${BleManager.knownName(c.uuid)}")
                        Text("Propiedades: ${c.propsText}${if (c.notifying) "  (suscrito)" else ""}", fontSize = 12.sp)
                        c.descriptors.forEach { Mono("  desc $it") }
                        if (c.lastValue.isNotEmpty()) Mono("Valor: ${c.lastValue}")
                        ButtonRow {
                            if (c.canRead) OutlinedButton(onClick = { vm.ble.read(c.key) }) { Text("Leer") }
                            if (c.anyWrite) {
                                if (target == c.key) Button(onClick = {}) { Text("Destino actual") }
                                else OutlinedButton(onClick = { vm.setTarget(c.key) }) { Text("Usar como destino") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandsTab(vm: MainViewModel) {
    val services by vm.ble.services.collectAsState()
    val target by vm.targetKey.collectAsState()
    val hexes by vm.commandHex.collectAsState()
    val lines by AppLog.lines.collectAsState()
    val writable = services.flatMap { it.chars }.filter { it.anyWrite }
    var custom by rememberSaveable { mutableStateOf("") }
    var fuzz by rememberSaveable { mutableStateOf("00") }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("Característica destino (WRITE)") }
        if (writable.isEmpty()) item { Text("No hay características con escritura. Conecta las gafas primero.") }
        items(writable, key = { "w" + it.key }) { c ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.setTarget(c.key) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = target == c.key, onClick = { vm.setTarget(c.key) })
                Column {
                    Mono(c.uuid.toString())
                    Text("servicio ${c.serviceUuid.toString().take(8)}  [${c.propsText}]", fontSize = 11.sp)
                }
            }
        }
        item {
            SectionTitle("Comandos de cámara")
            Text("El protocolo es desconocido: edita el payload hex y observa las respuestas RX.", fontSize = 12.sp)
        }
        items(vm.commands, key = { it.id }) { cmd ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hexes[cmd.id] ?: "",
                    onValueChange = { vm.setCommandHex(cmd.id, it) },
                    label = { Text("Hex ${cmd.label}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.sendCommand(cmd.id) }, enabled = target.isNotEmpty()) { Text(cmd.label) }
            }
        }
        item {
            SectionTitle("Envío manual")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(custom, { custom = it }, label = { Text("Hex libre, ej. FE DC BA 00") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.sendCustom(custom) }, enabled = target.isNotEmpty()) { Text("Enviar") }
            }
            SectionTitle("Probar en TODAS las características WRITE")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(fuzz, { fuzz = it }, label = { Text("Payload hex") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.ble.writeToAllWritable(fuzz) }, enabled = writable.isNotEmpty()) { Text("Probar todas") }
            }
            SectionTitle("Tráfico reciente (TX / RX)")
        }
        items(lines.filter { it.contains("[TX]") || it.contains("[RX]") || it.contains("[READ]") }.takeLast(40).reversed()) {
            Mono(it)
        }
    }
}

@Composable
private fun WifiTab(vm: MainViewModel) {
    val nets by vm.wifi.networks.collectAsState()
    val results by vm.wifi.results.collectAsState()
    val files by vm.wifi.files.collectAsState()
    val busy by vm.wifi.busy.collectAsState()
    val netStatus by vm.wifi.netStatus.collectAsState()
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("http://192.168.1.1/") }
    var host by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Envía primero el comando BLE que activa la transferencia y luego escanea: el hotspot de las gafas suele aparecer solo en ese momento.", fontSize = 12.sp)
            ButtonRow {
                Button(onClick = { vm.wifi.startScan() }) { Text("Escanear WiFi") }
                OutlinedButton(onClick = { vm.wifi.logNetworkInfo() }) { Text("Info red") }
                OutlinedButton(onClick = { vm.wifi.clearResults() }) { Text("Limpiar") }
            }
            SectionTitle("Redes (${nets.size})")
        }
        items(nets, key = { it.bssid + it.ssid }) { n ->
            Column(Modifier.fillMaxWidth().clickable { ssid = n.ssid }.padding(vertical = 4.dp)) {
                Text(
                    "${n.ssid.ifEmpty { "(oculta)" }}${if (n.suspect) "  [POSIBLE GAFAS]" else ""}",
                    fontWeight = if (n.suspect) FontWeight.Bold else FontWeight.Normal,
                    color = if (n.suspect) Color(0xFF1B5E20) else Color.Unspecified
                )
                Text("${n.bssid}  ${n.level} dBm  ${n.frequency} MHz  ${n.capabilities}", fontSize = 11.sp)
                HorizontalDivider()
            }
        }
        item {
            SectionTitle("Conectar al hotspot")
            OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Contraseña (vacía si es abierta)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(netStatus, fontSize = 12.sp)
            ButtonRow {
                Button(onClick = { vm.wifi.connect(ssid, pass) }, enabled = ssid.isNotBlank()) { Text("Conectar") }
                OutlinedButton(onClick = { vm.wifi.release() }) { Text("Liberar") }
            }
            SectionTitle("Descubrir endpoint de transferencia")
            ButtonRow {
                Button(onClick = { vm.wifi.probeGateway() }, enabled = !busy) { Text("Sondear gateway") }
                Button(onClick = { vm.wifi.sweepSubnet() }, enabled = !busy) { Text("Barrer subred") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(host, { host = it }, label = { Text("IP manual") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.wifi.probeHost(host.trim()) }, enabled = !busy && host.isNotBlank()) { Text("Sondear") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.wifi.httpGet(url.trim()) }) { Text("GET") }
            }
            if (busy) Text("Trabajando...", color = Color(0xFFE65100))
            if (files.isNotEmpty()) SectionTitle("Archivos / enlaces encontrados (${files.size})")
        }
        items(files, key = { "f" + it.url }) { f ->
            Text(
                "${f.name}\n${f.url}",
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { url = f.url; vm.wifi.httpGet(f.url) }.padding(vertical = 4.dp)
            )
        }
        item { SectionTitle("Resultados") }
        items(results.reversed()) { Mono(it) }
    }
}

@Composable
private fun AiTab(vm: MainViewModel, onPickImage: () -> Unit) {
    val status by vm.aiStatus.collectAsState()
    val answer by vm.aiAnswer.collectAsState()
    val busy by vm.aiBusy.collectAsState()
    val image by vm.image.collectAsState()
    var provider by remember { mutableStateOf(vm.settings.defaultProvider) }
    var prompt by rememberSaveable { mutableStateOf("Describe lo que tengo delante.") }
    LazyColumn(Modifier.fillMaxSize()) {
        item { AssistantCard(vm) }
        items(AiProvider.entries, key = { it.name }) { p ->
            var key by remember(p) { mutableStateOf(vm.settings.key(p)) }
            var model by remember(p) { mutableStateOf(vm.settings.model(p)) }
            val s = status[p]
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(p.label, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        key, { key = it },
                        label = { Text(if (p == AiProvider.GEMINI) "API key de Gemini" else "Token / API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(model, { model = it }, label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ButtonRow {
                        OutlinedButton(onClick = { vm.saveAi(p, key, model) }) { Text("Guardar") }
                        Button(onClick = { vm.saveAi(p, key, model); vm.testAi(p) }) { Text("Probar conexión") }
                    }
                    if (s != null) {
                        Text(
                            s,
                            fontSize = 12.sp,
                            color = when {
                                s.startsWith("OK") -> Color(0xFF1B5E20)
                                s.startsWith("ERROR") -> Color(0xFFB71C1C)
                                else -> Color.Unspecified
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
        }
        item {
            SectionTitle("Prueba de consulta")
            AiProvider.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { provider = p }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = provider == p, onClick = { provider = p })
                    Text(p.label + if (!p.supportsImages) " (solo texto)" else "")
                }
            }
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Pregunta") }, modifier = Modifier.fillMaxWidth())
            ButtonRow {
                OutlinedButton(onClick = onPickImage) { Text(if (image == null) "Adjuntar imagen" else "Cambiar imagen") }
                if (image != null) OutlinedButton(onClick = { vm.clearImage() }) { Text("Quitar imagen") }
                Button(onClick = { vm.ask(provider, prompt) }, enabled = !busy) { Text(if (busy) "Consultando..." else "Enviar") }
            }
            if (image != null) Text("Imagen adjunta: ${image!!.size / 1024} KB", fontSize = 12.sp)
            if (answer.isNotEmpty()) {
                SectionTitle("Respuesta")
                Text(
                    answer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
                ButtonRow {
                    Button(onClick = { vm.speak(answer) }) { Text("Leer en voz alta") }
                    OutlinedButton(onClick = { vm.stopSpeaking() }) { Text("Detener voz") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AssistantCard(vm: MainViewModel) {
    val assistant = vm.assistant
    val status by assistant.status.collectAsState()
    val last by assistant.last.collectAsState()
    val busy by assistant.busy.collectAsState()
    var enabled by remember { mutableStateOf(assistant.enabled) }
    var provider by remember { mutableStateOf(assistant.provider) }
    val bitmap = remember(last?.photo) {
        last?.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("Asistente de las gafas", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            Text(
                "Botón trasero 2 clicks: foto y pregunta por voz. Botón trasero 1 click: solo pregunta por voz.",
                fontSize = 12.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enabled, onCheckedChange = { enabled = it; assistant.enabled = it })
                Text("Responder automáticamente al botón IA")
            }
            val wakeStatus by vm.wakeStatus.collectAsState()
            var wake by remember { mutableStateOf(vm.wakeEnabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = wake, onCheckedChange = { wake = it; vm.wakeEnabled = it })
                Column {
                    Text("Escuchar \"Hey CLEO\" (manos libres)")
                    Text("Ejemplo: \"Hey CLEO, ¿qué hay delante de mí?\" toma la foto y responde. Gasta más batería de las gafas.", fontSize = 11.sp)
                }
            }
            Text("Estado CLEO: $wakeStatus", fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Modelo predeterminado (se guarda y se usa en todo):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            AiProvider.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { provider = p; assistant.provider = p }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = provider == p, onClick = { provider = p; assistant.provider = p })
                    Text(p.label + "  [" + vm.settings.model(p) + "]" + if (provider == p) "  (predeterminado)" else "")
                }
            }
            Text(
                status,
                color = if (busy) Color(0xFFE65100) else Color.Unspecified,
                modifier = Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
            last?.let { l ->
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Última foto tomada por las gafas",
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    )
                }
                Text("Hora: ${l.time}${if (l.provider.isNotEmpty()) "   ${l.provider}   ${l.millis / 1000.0} s" else ""}", fontSize = 12.sp)
                if (l.question.isNotEmpty()) Text("Pregunta: ${l.question}", fontSize = 13.sp)
                if (l.answer.isNotEmpty()) Text("Respuesta: ${l.answer}")
                ButtonRow {
                    if (l.answer.isNotEmpty()) Button(onClick = { vm.speak(l.answer) }) { Text("Repetir respuesta") }
                    if (l.photo != null) OutlinedButton(onClick = { assistant.askManual(l.photo, "") }, enabled = !busy) { Text("Describir de nuevo") }
                }
            }
        }
    }
}

@Composable
private fun LogTab(onShareLog: () -> Unit) {
    val lines by AppLog.lines.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = if (filter.isBlank()) lines else lines.filter { it.contains(filter, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Archivo: ${AppLog.file?.absolutePath ?: "-"}", fontSize = 11.sp)
            ButtonRow {
                Button(onClick = onShareLog) { Text("Compartir log") }
                OutlinedButton(onClick = { AppLog.clearView() }) { Text("Limpiar vista") }
            }
            OutlinedTextField(filter, { filter = it }, label = { Text("Filtrar (RX, TX, GATT, WIFI, IA...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("${shown.size} líneas", fontSize = 11.sp)
        }
        items(shown.takeLast(800).reversed()) { Mono(it) }
    }
}
