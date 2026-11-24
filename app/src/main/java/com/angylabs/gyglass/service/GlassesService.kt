package com.angylabs.gyglass.service

import android.app.AlarmManager
import android.app.Notification
import android.os.SystemClock
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.angylabs.gyglass.GyGlassApp
import com.angylabs.gyglass.MainActivity
import com.angylabs.gyglass.R
import com.angylabs.gyglass.ble.BleManager
import com.angylabs.gyglass.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class GlassesService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ble: BleManager get() = (application as GyGlassApp).ble

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startInForeground(buildNotification(ble.state.value, ble.connectedDevice.value?.name))
        AppLog.log("SERVICIO", "Servicio en segundo plano iniciado")
        scope.launch {
            combine(ble.state, ble.connectedDevice) { s, d -> s to d }.collect { (s, d) ->
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(s, d?.name ?: ble.savedDevice.value?.name))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.log("SERVICIO", "Detenido desde la notificacion")
                ble.keepConnected = false
                ble.disconnect()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ble.keepConnected = true
                if (ble.state.value == BleManager.ConnState.DESCONECTADO) ble.connectSaved()
                val app = application as GyGlassApp
                if (app.wakeEnabled) {
                    startInForeground(buildNotification(ble.state.value, ble.connectedDevice.value?.name))
                    app.wake.start()
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.log("SERVICIO", "App quitada de recientes, la conexion con las gafas continua")
        if (ble.keepConnected) scheduleRestart(this, 1500)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        (application as GyGlassApp).wake.stop()
        scope.cancel()
        AppLog.log("SERVICIO", "Servicio destruido")
        if (ble.keepConnected) scheduleRestart(this, 3000)
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val wantMic = (application as GyGlassApp).wakeEnabled &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val withMic = if (wantMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else base
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, withMic)
        } catch (e: Exception) {
            AppLog.log("SERVICIO", "Primer plano con microfono no permitido (${e.message}), se usa solo Bluetooth")
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, base)
            } catch (e2: Exception) {
                AppLog.log("SERVICIO", "No se pudo iniciar en primer plano: ${e2.message}")
                stopSelf()
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Conexión con las gafas", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantiene la conexión Bluetooth con las gafas en segundo plano"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: BleManager.ConnState, name: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GlassesService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when (state) {
            BleManager.ConnState.LISTO -> "Gafas conectadas"
            BleManager.ConnState.DESCONECTADO -> "Gafas desconectadas, esperando reconexión"
            else -> "Conectando con las gafas..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText((name?.ifBlank { null } ?: "GyGlass") + ((application as GyGlassApp).protocol.battery.value?.let { "  ·  Batería ${it.level}%" } ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Desconectar", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gyglass_connection"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.angylabs.gyglass.STOP"
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, GlassesService::class.java))
            } catch (e: Exception) {
                AppLog.log("SERVICIO", "No se pudo arrancar el servicio: ${e.message}")
            }
        }

        fun scheduleRestart(context: Context, delayMs: Long) {
            try {
                val intent = Intent(context, GlassesService::class.java)
                val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                } else {
                    PendingIntent.getService(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                }
                val am = context.getSystemService(AlarmManager::class.java)
                am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
                AppLog.log("SERVICIO", "Reinicio del servicio programado en ${delayMs} ms")
            } catch (e: Exception) {
                AppLog.log("SERVICIO", "No se pudo programar el reinicio: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlassesService::class.java))
        }
    }
}
