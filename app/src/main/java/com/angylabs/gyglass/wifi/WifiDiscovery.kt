package com.angylabs.gyglass.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import com.angylabs.gyglass.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class WifiDiscovery(context: Context, private val scope: CoroutineScope) {

    data class WifiNet(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val frequency: Int,
        val capabilities: String,
        val suspect: Boolean
    )

    data class RemoteFile(val name: String, val url: String)

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)

    private val _networks = MutableStateFlow<List<WifiNet>>(emptyList())
    val networks: StateFlow<List<WifiNet>> = _networks
    private val _results = MutableStateFlow<List<String>>(emptyList())
    val results: StateFlow<List<String>> = _results
    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _netStatus = MutableStateFlow("Sin red dedicada")
    val netStatus: StateFlow<String> = _netStatus

    private var boundNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            publishScan()
        }
    }

    fun startScan() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
        if (wifi?.isWifiEnabled != true) {
            AppLog.log("WIFI", "WiFi apagado")
        }
        val ok = wifi?.startScan() ?: false
        AppLog.log("WIFI", "startScan() -> $ok (Android limita a 4 escaneos cada 2 min)")
        publishScan()
    }

    private fun publishScan() {
        val list = try {
            wifi?.scanResults.orEmpty()
        } catch (e: SecurityException) {
            AppLog.log("WIFI", "Sin permiso para leer resultados: ${e.message}")
            emptyList()
        }
        val previous = _networks.value.map { it.bssid }.toSet()
        val nets = list.map { r ->
            val ssid = r.SSID ?: ""
            WifiNet(ssid, r.BSSID ?: "", r.level, r.frequency, r.capabilities ?: "", isSuspect(ssid))
        }.sortedWith(compareByDescending<WifiNet> { it.suspect }.thenByDescending { it.level })
        nets.filter { it.bssid !in previous }.forEach {
            AppLog.log("WIFI", "Red: \"${it.ssid}\" ${it.bssid} ${it.level}dBm ${it.frequency}MHz ${it.capabilities}" + if (it.suspect) " <<< POSIBLE HOTSPOT GAFAS" else "")
        }
        _networks.value = nets
    }

    fun connect(ssid: String, password: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLog.log("WIFI", "Conexion dedicada requiere Android 10+. Conecta manualmente desde Ajustes y usa 'Sondear gateway'.")
            return
        }
        release()
        val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                boundNetwork = network
                _netStatus.value = "Conectado a \"$ssid\""
                AppLog.log("WIFI", "Red \"$ssid\" disponible. ${describe(network)}")
            }

            override fun onUnavailable() {
                _netStatus.value = "No se pudo conectar a \"$ssid\""
                AppLog.log("WIFI", "Red \"$ssid\" no disponible")
            }

            override fun onLost(network: Network) {
                if (boundNetwork == network) boundNetwork = null
                _netStatus.value = "Red \"$ssid\" perdida"
                AppLog.log("WIFI", "Red \"$ssid\" perdida")
            }
        }
        netCallback = cb
        _netStatus.value = "Solicitando \"$ssid\"..."
        AppLog.log("WIFI", "Solicitando conexion a \"$ssid\"")
        cm?.requestNetwork(request, cb)
    }

    fun release() {
        netCallback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        netCallback = null
        boundNetwork = null
        _netStatus.value = "Sin red dedicada"
    }

    private fun activeNetwork(): Network? = boundNetwork ?: cm?.activeNetwork

    fun describe(network: Network? = activeNetwork()): String {
        if (network == null) return "Sin red activa"
        val lp = cm?.getLinkProperties(network)
        val ip = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        val gw = gatewayOf(network)
        val dns = lp?.dnsServers?.joinToString { it.hostAddress ?: "" }
        val info = wifi?.connectionInfo
        return "iface=${lp?.interfaceName} ip=$ip gateway=$gw dns=$dns ssid=${info?.ssid} bssid=${info?.bssid} linkSpeed=${info?.linkSpeed}Mbps"
    }

    fun logNetworkInfo() {
        AppLog.log("WIFI", "Info red: ${describe()}")
        addResult("Red: ${describe()}")
    }

    private fun gatewayOf(network: Network?): String? {
        val lp = network?.let { cm?.getLinkProperties(it) }
        val fromRoutes = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        if (!fromRoutes.isNullOrEmpty() && fromRoutes != "0.0.0.0") return fromRoutes
        val g = wifi?.dhcpInfo?.gateway ?: 0
        if (g == 0) return null
        return "${g and 0xFF}.${g shr 8 and 0xFF}.${g shr 16 and 0xFF}.${g shr 24 and 0xFF}"
    }

    private fun ownIp(network: Network?): String? {
        val lp = network?.let { cm?.getLinkProperties(it) }
        return lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    }

    fun probeGateway() {
        val gw = gatewayOf(activeNetwork())
        if (gw == null) {
            AppLog.log("WIFI", "No se encontro gateway")
            addResult("No se encontro gateway")
            return
        }
        probeHost(gw)
    }

    fun probeHost(host: String) {
        if (_busy.value) return
        scope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                doProbeHost(host)
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun doProbeHost(host: String) {
        AppLog.log("WIFI", "Sondeando $host en ${PORTS.size} puertos")
        addResult("Sondeando $host ...")
        val net = activeNetwork()
        val open = scope.async(Dispatchers.IO) {
            PORTS.map { p -> scope.async(Dispatchers.IO) { if (isOpen(net, host, p, 800)) p else null } }.awaitAll().filterNotNull()
        }.await()
        addResult("$host puertos abiertos: ${if (open.isEmpty()) "ninguno" else open.joinToString()}")
        AppLog.log("WIFI", "$host puertos abiertos: $open")
        open.filter { it in BANNER_PORTS }.forEach { p ->
            val banner = readBanner(net, host, p)
            addResult("$host:$p banner: $banner")
            AppLog.log("WIFI", "$host:$p banner: $banner")
        }
        open.filter { it !in BANNER_PORTS && it != 443 }.forEach { p ->
            HTTP_PATHS.forEach { path ->
                val url = "http://$host:$p$path"
                val r = httpGetInternal(net, url, 4000)
                if (r != null) {
                    addResult("GET $url -> ${r.first} (${r.second.length} bytes)")
                    if (r.first in 200..299) {
                        extractLinks(url, r.second)
                    }
                }
            }
        }
    }

    fun sweepSubnet() {
        if (_busy.value) return
        scope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val net = activeNetwork()
                val ip = ownIp(net) ?: gatewayOf(net)
                if (ip == null) {
                    addResult("Sin IP local para barrer")
                    return@launch
                }
                val prefix = ip.substringBeforeLast('.')
                AppLog.log("WIFI", "Barriendo $prefix.1-254 en puertos $SWEEP_PORTS")
                addResult("Barriendo $prefix.0/24 ...")
                val sem = Semaphore(48)
                val hits = (1..254).map { i ->
                    scope.async(Dispatchers.IO) {
                        sem.withPermit {
                            val host = "$prefix.$i"
                            if (host == ip) null else {
                                val ports = SWEEP_PORTS.filter { isOpen(net, host, it, 300) }
                                if (ports.isEmpty()) null else host to ports
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
                if (hits.isEmpty()) addResult("Barrido: ningun host con servicios")
                hits.forEach { (h, ports) ->
                    addResult("Host activo $h puertos=$ports")
                    AppLog.log("WIFI", "Host activo $h puertos=$ports")
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun httpGet(url: String) {
        scope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val r = httpGetInternal(activeNetwork(), url, 8000)
                if (r == null) {
                    addResult("GET $url -> error")
                } else {
                    addResult("GET $url -> ${r.first}\n${r.second.take(1500)}")
                    if (r.first in 200..299) extractLinks(url, r.second)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun httpGetInternal(net: Network?, url: String, timeout: Int): Pair<Int, String>? = try {
        val conn = (net?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        val headers = conn.headerFields.filterKeys { it != null }.entries.joinToString { "${it.key}=${it.value.joinToString(",")}" }
        val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.use { readLimited(it, 64 * 1024) } ?: ""
        AppLog.log("HTTP", "GET $url -> $code headers=[$headers] body=${body.take(600).replace("\n", "\\n")}")
        conn.disconnect()
        code to body
    } catch (e: Exception) {
        AppLog.log("HTTP", "GET $url -> ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun readLimited(input: InputStream, max: Int): String {
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < max) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun extractLinks(baseUrl: String, body: String) {
        val base = URL(baseUrl)
        val links = HREF.findAll(body).map { it.groupValues[1] }
            .filter { !it.startsWith("#") && !it.startsWith("javascript", true) && it != "../" }
            .mapNotNull { href -> try { RemoteFile(href.trimEnd('/').substringAfterLast('/').ifEmpty { href }, URL(base, href).toString()) } catch (_: Exception) { null } }
            .toList()
        val jsonFiles = FILE_NAME.findAll(body).map { it.value }.distinct().map { RemoteFile(it, URL(base, it).toString()) }.toList()
        val all = (links + jsonFiles).distinctBy { it.url }
        if (all.isNotEmpty()) {
            AppLog.log("WIFI", "Archivos/enlaces en $baseUrl: ${all.joinToString { it.name }}")
            _files.value = (_files.value + all).distinctBy { it.url }
        }
    }

    private fun isOpen(net: Network?, host: String, port: Int, timeout: Int): Boolean = try {
        val s: Socket = net?.socketFactory?.createSocket() ?: Socket()
        s.use { it.connect(InetSocketAddress(host, port), timeout) }
        true
    } catch (_: Exception) {
        false
    }

    private fun readBanner(net: Network?, host: String, port: Int): String = try {
        val s: Socket = net?.socketFactory?.createSocket() ?: Socket()
        s.use {
            it.connect(InetSocketAddress(host, port), 1000)
            it.soTimeout = 1500
            if (port == 554) it.getOutputStream().write("OPTIONS rtsp://$host:554/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            val buf = ByteArray(512)
            val n = it.getInputStream().read(buf)
            if (n > 0) String(buf, 0, n).replace("\r", "").replace("\n", " | ") else "(sin datos)"
        }
    } catch (e: Exception) {
        "(${e.javaClass.simpleName})"
    }

    private fun addResult(line: String) {
        _results.value = (_results.value + line).takeLast(300)
    }

    fun clearResults() {
        _results.value = emptyList()
        _files.value = emptyList()
    }

    fun dispose() {
        release()
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    companion object {
        val PORTS = listOf(21, 23, 80, 443, 554, 3333, 5000, 5555, 6666, 7000, 8000, 8080, 8081, 8088, 8554, 8888, 8899, 9000, 9999, 10000)
        val BANNER_PORTS = setOf(21, 23, 554, 8554)
        val SWEEP_PORTS = listOf(80, 8080, 8000, 554)
        val HTTP_PATHS = listOf(
            "/", "/index.html", "/DCIM/", "/DCIM", "/files", "/file", "/list", "/filelist", "/getfilelist",
            "/api/list", "/api/files", "/media", "/photo", "/photos", "/video", "/videos", "/sd", "/sdcard",
            "/mnt", "/mnt/sdcard", "/cgi-bin/", "/status", "/info", "/device"
        )
        private val HREF = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val FILE_NAME = Regex("[\\w\\-./]+\\.(?:jpg|jpeg|png|mp4|mov|avi|wav|mp3|aac|opus|h264|265|bin|txt|json)", RegexOption.IGNORE_CASE)
        private val SUSPECT_PATTERNS = listOf("AIMB", "G2", "CYAN", "GLASS", "JL", "V821", "CAM", "DIRECT-")

        fun isSuspect(ssid: String): Boolean {
            val up = ssid.uppercase()
            return SUSPECT_PATTERNS.any { up.contains(it) }
        }
    }
}
