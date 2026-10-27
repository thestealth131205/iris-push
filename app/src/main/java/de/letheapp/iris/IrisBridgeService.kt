package de.letheapp.iris

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Counterpart to [IrisPushService] for OS builds where the native `irisd`
 * daemon (see /daemon in this repo, OS_INTEGRATION_PLAN.md Tier 2) holds the
 * actual TLS/WebSocket connection at init-daemon privilege. This service
 * never touches the network - it only connects, as a plain client, to the
 * daemon's local control socket ("iris_daemon") and turns each delivered
 * line into the same signature-protected de.letheapp.iris.PUSH broadcast
 * that [IrisPushService] sends when it's the one holding the connection.
 *
 * Only ever useful on a device where irisd is actually running. On a normal
 * sideload (no OS integration) the socket simply doesn't exist, which is
 * exactly the signal [IrisDelivery] uses to fall back to [IrisPushService].
 */
class IrisBridgeService : Service() {

    companion object {
        const val SOCKET_NAME = "iris_daemon"
        private const val CHANNEL_ID = "iris_bridge"
        private const val NOTIFICATION_ID = 2
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val PROBE_TIMEOUT_MS = 500

        /** Quick, cheap check for whether the native daemon is present and listening. */
        fun isDaemonAvailable(): Boolean {
            return try {
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED))
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var connectLoopJob: Job? = null
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        IrisLog.log(this, "IrisBridgeService", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Verbinde mit Systemdienst…"))
        } catch (e: Exception) {
            // dataSync FGS time quota (Android 15+) already exhausted for today -
            // retry later instead of crashing (see ForegroundServiceRestart).
            IrisLog.log(this, "IrisBridgeService", "onStartCommand: startForeground abgelehnt (${e.javaClass.simpleName}), Neustart in 30min")
            ForegroundServiceRestart.scheduleRetry(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (connectLoopJob?.isActive != true) {
            connectLoopJob = scope.launch { connectLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        IrisLog.log(this, "IrisBridgeService", "onDestroy")
        connectLoopJob?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ gives a dataSync FGS a chance to clean up before being
     * force-stopped once its 6h/24h quota runs out. Without this override
     * the system kills the process and reports
     * ForegroundServiceDidNotStopInTimeException as a crash.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        IrisLog.log(this, "IrisBridgeService", "onTimeout: FGS-Zeitlimit erreicht (type=$fgsType), Neustart in 30min")
        ForegroundServiceRestart.scheduleRetry(this)
        stopSelf()
    }

    private suspend fun connectLoop() {
        while (scope.isActive) {
            try {
                readFromDaemon()
            } catch (e: Exception) {
                IrisLog.log(this, "IrisBridgeService", "Verbindung zum Systemdienst verloren: ${e.javaClass.simpleName}: ${e.message}")
            }
            updateNotification("Getrennt vom Systemdienst – erneuter Versuch in ${RECONNECT_DELAY_MS / 1000}s…")
            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun readFromDaemon() {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED))
        socket.use {
            updateNotification("Verbunden mit Systemdienst")
            // Replay registrations / hand over a newer bundled irisd (own connection, off the push reader)
            scope.launch { IrisDaemonSync.onBridgeConnected(this@IrisBridgeService) }
            val reader = BufferedReader(InputStreamReader(it.inputStream))
            while (scope.isActive) {
                val line = reader.readLine() ?: break
                handleLine(line)
            }
        }
    }

    private fun handleLine(line: String) {
        val obj = try {
            gson.fromJson(line, JsonObject::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        val targetPackage = obj.get("package_name")?.asString ?: return
        val title = obj.get("title")?.asString ?: ""
        val body = obj.get("body")?.asString ?: ""
        val dataJson = obj.get("data_json")?.toString() ?: "{}"

        sendBroadcast(
            Intent(IrisPushService.ACTION_PUSH).apply {
                setPackage(targetPackage)
                putExtra(IrisPushService.EXTRA_PACKAGE_NAME, targetPackage)
                putExtra(IrisPushService.EXTRA_TITLE, title)
                putExtra(IrisPushService.EXTRA_BODY, body)
                putExtra(IrisPushService.EXTRA_DATA_JSON, dataJson)
            },
            IrisPushService.PERMISSION_RECEIVE_PUSH
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Iris-Systemdienst-Bruecke", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Leitet Pushes vom irisd-Systemdienst an die Zielapp weiter."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Iris Push")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
