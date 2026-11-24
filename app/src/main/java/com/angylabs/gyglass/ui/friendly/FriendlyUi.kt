package com.angylabs.gyglass.ui.friendly

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.angylabs.gyglass.MainViewModel
import com.angylabs.gyglass.ai.AiProvider
import com.angylabs.gyglass.ble.BleManager

object Palette {
    val Background = Color(0xFF0A0D12)
    val Surface = Color(0xFF151A21)
    val SurfaceHigh = Color(0xFF1D232C)
    val Outline = Color(0xFF2A313C)
    val Green = Color(0xFF34D399)
    val GreenSoft = Color(0xFF0F2F25)
    val Blue = Color(0xFF60A5FA)
    val BlueSoft = Color(0xFF0F2340)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFFA7B0BE)
}

enum class FriendlyTab { INICIO, AJUSTES }

private const val READ_TEXT_PROMPT =
    "Lee en voz alta todo el texto que se vea en la imagen, en orden de arriba hacia abajo y sin resumir. " +
        "Si no hay texto legible, dilo y describe brevemente qué hay."
private const val DESCRIBE_PROMPT =
    "Describe lo que tengo delante para orientarme: primero obstáculos y peligros, luego personas y objetos importantes."

@Composable
fun FriendlyApp(
    vm: MainViewModel,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    onOpenDeveloper: () -> Unit,
    onTalk: () -> Unit,
    ensureBluetooth: (() -> Unit) -> Unit,
    onBatteryOptimization: () -> Unit,
    onKeepConnected: (Boolean) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableStateOf(FriendlyTab.INICIO) }
    Column(
        modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                FriendlyTab.INICIO -> HomeScreen(vm, onTalk, ensureBluetooth, onOpenSettings = { tab = FriendlyTab.AJUSTES })
                FriendlyTab.AJUSTES -> SettingsScreen(
                    vm, devModeEnabled, onDevModeChange, onOpenDeveloper, ensureBluetooth,
                    onBatteryOptimization, onKeepConnected, onOpenBluetoothSettings
                )
            }
        }
        BottomBar(tab, devModeEnabled, onSelect = { tab = it }, onOpenDeveloper = onOpenDeveloper)
    }
}

@Composable
private fun BottomBar(tab: FriendlyTab, devMode: Boolean, onSelect: (FriendlyTab) -> Unit, onOpenDeveloper: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Pill("Inicio", Icons.Filled.Home, tab == FriendlyTab.INICIO, Modifier.weight(1f)) { onSelect(FriendlyTab.INICIO) }
        Pill("Ajustes", Icons.Filled.Settings, tab == FriendlyTab.AJUSTES, Modifier.weight(1f)) { onSelect(FriendlyTab.AJUSTES) }
        if (devMode) Pill("Desarrollo", Icons.Filled.Code, false, Modifier.weight(1f), onOpenDeveloper)
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) Palette.TextPrimary else Palette.SurfaceHigh)
            .selectable(selected = selected, role = Role.Tab) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Palette.Background else Palette.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) Palette.Background else Palette.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onTalk: () -> Unit,
    ensureBluetooth: (() -> Unit) -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by vm.ble.state.collectAsState()
    val device by vm.ble.connectedDevice.collectAsState()
    val saved by vm.ble.savedDevice.collectAsState()
    val battery by vm.battery.collectAsState()
    val status by vm.assistant.status.collectAsState()
    val busy by vm.assistant.busy.collectAsState()
    val awaiting by vm.assistant.awaitingPhoto.collectAsState()
    val last by vm.assistant.last.collectAsState()
    val wakeStatus by vm.wakeStatus.collectAsState()
    val connected = state == BleManager.ConnState.LISTO
    val active = busy || awaiting

    Column(
        Modifier
            .fillMaxSize()
            .semantics { paneTitle = "Inicio" }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusHeader(state, battery?.level, battery?.charging == true)
        Spacer(Modifier.height(18.dp))
        Text(
            "GyGlass",
            color = Palette.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            if (connected) "Conectado a ${device?.name ?: saved?.name ?: "tus gafas"}" else "Tu asistente visual",
            color = Palette.TextSecondary,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(14.dp))
        BatteryCard(connected, battery?.level, battery?.charging == true)
        Spacer(Modifier.height(20.dp))

        if (!connected) {
            ConnectCard(vm, state, saved, ensureBluetooth, onOpenSettings)
            Spacer(Modifier.height(20.dp))
        }

        TalkButton(active = active, onClick = onTalk)
        Spacer(Modifier.height(16.dp))
        Text(
            if (active) "Trabajando..." else "Toca para hablar con la IA",
            color = Palette.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            status,
            color = Palette.TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionCard(
                title = "Leer texto",
                subtitle = "Carteles, documentos, etiquetas",
                icon = Icons.Filled.TextSnippet,
                accent = Palette.Blue,
                soft = Palette.BlueSoft,
                enabled = connected && !active,
                modifier = Modifier.weight(1f)
            ) { vm.assistant.askWithPhoto(READ_TEXT_PROMPT) }
            ActionCard(
                title = "Describir entorno",
                subtitle = "Qué hay delante de ti",
                icon = Icons.Filled.Visibility,
                accent = Palette.Green,
                soft = Palette.GreenSoft,
                enabled = connected && !active,
                modifier = Modifier.weight(1f)
            ) { vm.assistant.askWithPhoto(DESCRIBE_PROMPT) }
        }
        Spacer(Modifier.height(20.dp))

        last?.takeIf { it.answer.isNotEmpty() }?.let { l ->
            AnswerCard(l.answer, l.question, l.photo, l.time, onRepeat = { vm.speak(l.answer) }, onStop = { vm.stopSpeaking() })
            Spacer(Modifier.height(16.dp))
        }

        HintCard(
            if (vm.wakeEnabled) "Manos libres: di \"Hey CLEO, ¿qué hay delante de mí?\". Estado: $wakeStatus"
            else "Consejo: activa \"Hey CLEO\" en Ajustes para preguntar sin tocar el teléfono. También puedes pulsar dos veces el botón trasero de las gafas."
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusHeader(state: BleManager.ConnState, battery: Int?, charging: Boolean) {
    val (color, text) = when (state) {
        BleManager.ConnState.LISTO -> Palette.Green to "Gafas conectadas"
        BleManager.ConnState.DESCONECTADO -> Palette.Red to "Gafas desconectadas"
        else -> Palette.Amber to "Conectando..."
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        if (battery != null) {
            BatteryIndicator(battery, charging)
        }
    }
}

fun batteryText(connected: Boolean, level: Int?, charging: Boolean): String = when {
    !connected -> "Batería de las gafas: sin conexión"
    level == null -> "Batería de las gafas: consultando..."
    charging -> "Batería de las gafas: $level % (cargando)"
    else -> "Batería de las gafas: $level %"
}

@Composable
private fun BatteryCard(connected: Boolean, level: Int?, charging: Boolean) {
    val color = when {
        !connected || level == null -> Palette.TextSecondary
        charging -> Palette.Green
        level <= 15 -> Palette.Red
        level <= 30 -> Palette.Amber
        else -> Palette.TextPrimary
    }
    val extra = when {
        connected && level != null && level <= 15 && !charging -> " Carga las gafas pronto."
        else -> ""
    }
    Text(
        batteryText(connected, level, charging) + extra,
        color = color,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface)
            .border(BorderStroke(1.dp, Palette.Outline), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun BatteryIndicator(level: Int, charging: Boolean) {
    val color = when {
        charging -> Palette.Green
        level <= 15 -> Palette.Red
        level <= 30 -> Palette.Amber
        else -> Palette.TextPrimary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { }
    ) {
        Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
            val stroke = 2.dp.toPx()
            val bodyW = size.width - 3.dp.toPx()
            drawRoundRect(color, size = androidx.compose.ui.geometry.Size(bodyW, size.height), style = Stroke(stroke), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(bodyW, size.height * 0.3f), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height * 0.4f))
            val inset = stroke * 1.6f
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size((bodyW - inset * 2) * level / 100f, size.height - inset * 2))
        }
        Spacer(Modifier.width(6.dp))
        Text("$level%${if (charging) " cargando" else ""}", color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TalkButton(active: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (active) 1100 else 2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    val ringColor = if (active) Palette.Green else Palette.TextSecondary
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.30f
            for (i in 0 until 3) {
                val p = (pulse + i / 3f) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1f - p) * (if (active) 0.55f else 0.25f)),
                    radius = base + (size.minDimension / 2f - base) * p,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        Box(
            Modifier
                .size(150.dp)
                .scale(if (active) 1f + 0.03f * kotlin.math.sin(pulse * 6.28f) else 1f)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Palette.SurfaceHigh, Palette.Surface)))
                .border(BorderStroke(2.dp, if (active) Palette.Green else Palette.Outline), CircleShape)
                .clickable(onClickLabel = "dictar una pregunta", role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .semantics {
                    contentDescription = if (active) "Hablar con la inteligencia artificial. Trabajando en la consulta anterior" else "Hablar con la inteligencia artificial"
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = if (active) Palette.Green else Palette.TextPrimary, modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    soft: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Surface)
            .border(BorderStroke(1.5.dp, if (enabled) accent.copy(alpha = 0.7f) else Palette.Outline), RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClickLabel = "tomar foto y ${title.lowercase()}", role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "$title. $subtitle"
                if (!enabled) stateDescription = "No disponible: conecta las gafas o espera a que termine la consulta"
                if (enabled) onClick(label = "tomar foto y ${title.lowercase()}") {
                    onClick()
                    true
                }
            }
            .padding(16.dp)
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(soft),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) accent else Palette.TextSecondary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(title, color = if (enabled) Palette.TextPrimary else Palette.TextSecondary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Palette.TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun AnswerCard(answer: String, question: String, photo: ByteArray?, time: String, onRepeat: () -> Unit, onStop: () -> Unit) {
    val bitmap = remember(photo) { photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Surface)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Repetir respuesta") { onRepeat(); true },
                    CustomAccessibilityAction("Detener voz") { onStop(); true }
                )
            }
            .padding(16.dp)
    ) {
        Text("Última respuesta  ·  $time", color = Palette.TextSecondary, fontSize = 13.sp, modifier = Modifier.semantics { heading() })
        if (question.isNotEmpty() && !question.startsWith("(")) {
            Text("Pregunta: $question", color = Palette.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 8.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(answer, color = Palette.TextPrimary, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("Repetir", Icons.Filled.Replay, Modifier.weight(1f), onRepeat)
            SmallAction("Detener voz", Icons.Filled.StopCircle, Modifier.weight(1f), onStop)
        }
    }
}

@Composable
private fun SmallAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.SurfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = Palette.TextPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HintCard(text: String) {
    Text(
        text,
        color = Palette.TextSecondary,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface)
            .padding(14.dp)
    )
}

@Composable
private fun ConnectCard(
    vm: MainViewModel,
    state: BleManager.ConnState,
    saved: BleManager.SavedDevice?,
    ensureBluetooth: (() -> Unit) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Surface)
            .border(BorderStroke(1.5.dp, Palette.Amber.copy(alpha = 0.6f)), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Text(
            if (state == BleManager.ConnState.DESCONECTADO) "Tus gafas no están conectadas" else "Conectando con tus gafas...",
            color = Palette.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(10.dp))
        if (saved != null) {
            BigButton("Reconectar ${saved.name.ifBlank { "gafas" }}", Icons.Filled.Bluetooth, Palette.Green) {
                ensureBluetooth { vm.ble.connectSaved() }
            }
        } else {
            BigButton("Buscar y conectar mis gafas", Icons.Filled.BluetoothSearching, Palette.Green) { onOpenSettings() }
        }
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(accent)
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Background, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Palette.Background, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsScreen(
    vm: MainViewModel,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    onOpenDeveloper: () -> Unit,
    ensureBluetooth: (() -> Unit) -> Unit,
    onBatteryOptimization: () -> Unit,
    onKeepConnected: (Boolean) -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val state by vm.ble.state.collectAsState()
    val saved by vm.ble.savedDevice.collectAsState()
    val scanning by vm.ble.scanning.collectAsState()
    val devices by vm.ble.devices.collectAsState()
    val bonded by vm.ble.bondedDevices.collectAsState()
    val wakeStatus by vm.wakeStatus.collectAsState()
    var wake by remember { mutableStateOf(vm.wakeEnabled) }
    var autoButton by remember { mutableStateOf(vm.assistant.enabled) }
    var keep by remember { mutableStateOf(vm.ble.keepConnected) }
    var provider by remember { mutableStateOf(vm.settings.defaultProvider) }
    val candidates = (bonded + devices).filter { it.isCandidate }.distinctBy { it.address }

    val battery by vm.battery.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .semantics { paneTitle = "Ajustes" }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Ajustes", color = Palette.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))

        Section("Mis gafas") {
            if (saved != null) {
                Text(saved!!.name.ifBlank { "Gafas guardadas" }, color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (state) {
                        BleManager.ConnState.LISTO -> "Conectadas"
                        BleManager.ConnState.DESCONECTADO -> "Desconectadas"
                        else -> "Conectando..."
                    },
                    color = if (state == BleManager.ConnState.LISTO) Palette.Green else Palette.TextSecondary,
                    fontSize = 14.sp
                )
                Text(
                    batteryText(state == BleManager.ConnState.LISTO, battery?.level, battery?.charging == true),
                    color = Palette.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
                if (state == BleManager.ConnState.DESCONECTADO) {
                    BigButton("Reconectar", Icons.Filled.Bluetooth, Palette.Green) { ensureBluetooth { vm.ble.connectSaved() } }
                    Spacer(Modifier.height(8.dp))
                }
            }
            BigButton(if (scanning) "Buscando gafas..." else "Buscar gafas cercanas", Icons.Filled.BluetoothSearching, Palette.Blue) {
                if (scanning) vm.ble.stopScan() else ensureBluetooth { vm.ble.startScan() }
            }
            candidates.forEach { d ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.SurfaceHigh)
                        .clickable(onClickLabel = "conectar", role = Role.Button) { ensureBluetooth { vm.ble.pairAndConnect(d.address) } }
                        .semantics(mergeDescendants = true) { }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name ?: "Gafas", color = Palette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (d.bonded) "Emparejadas" else "Sin emparejar", color = Palette.TextSecondary, fontSize = 13.sp)
                    }
                    Text("Conectar", color = Palette.Green, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextLink("Abrir ajustes de Bluetooth del teléfono", onOpenBluetoothSettings)
            if (saved != null) TextLink("Olvidar estas gafas") { vm.ble.forgetSaved() }
        }

        Section("Asistente") {
            ToggleRow("Escuchar \"Hey CLEO\"", "Pregunta sin tocar nada. Gasta más batería de las gafas. $wakeStatus", wake) {
                wake = it
                vm.wakeEnabled = it
            }
            ToggleRow("Responder al botón de las gafas", "Botón trasero: 1 clic pregunta por voz, 2 clics describe con foto.", autoButton) {
                autoButton = it
                vm.assistant.enabled = it
            }
            ToggleRow("Mantener conectado en segundo plano", "Las gafas siguen funcionando aunque cierres la app.", keep) {
                keep = it
                onKeepConnected(it)
            }
        }

        Section("Modelo de inteligencia artificial") {
            AiProvider.entries.forEach { p ->
                val hasKey = vm.settings.key(p).isNotBlank()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = provider == p, role = Role.RadioButton) {
                            provider = p
                            vm.assistant.provider = p
                        }
                        .semantics(mergeDescendants = true) { }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = provider == p,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Palette.Green, unselectedColor = Palette.TextSecondary)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(p.label, color = Palette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            vm.settings.model(p) + if (hasKey) "" else "  ·  sin clave configurada",
                            color = if (hasKey) Palette.TextSecondary else Palette.Amber,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Section("Batería del teléfono") {
            BigButton("Evitar que Android cierre la app", Icons.Filled.Settings, Palette.Blue, onClick = onBatteryOptimization)
        }

        Section("Avanzado") {
            ToggleRow("Modo desarrollador", "Muestra las herramientas técnicas: Bluetooth, comandos, WiFi, claves de IA y registro.", devModeEnabled, onDevModeChange)
            if (devModeEnabled) {
                Spacer(Modifier.height(8.dp))
                BigButton("Abrir modo desarrollador", Icons.Filled.Code, Palette.TextPrimary, onClick = onOpenDeveloper)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Surface)
            .padding(16.dp)
    ) {
        Text(title, color = Palette.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Palette.TextSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Background,
                checkedTrackColor = Palette.Green,
                uncheckedThumbColor = Palette.TextSecondary,
                uncheckedTrackColor = Palette.SurfaceHigh,
                uncheckedBorderColor = Palette.Outline
            )
        )
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.Blue,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

@Composable
fun DeveloperTopBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Pill("Volver", Icons.Filled.Home, false, Modifier, onBack)
        Spacer(Modifier.width(12.dp))
        Text("Modo desarrollador", color = Palette.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
