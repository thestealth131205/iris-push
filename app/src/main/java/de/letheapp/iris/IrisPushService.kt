package de.letheapp.iris

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Holds one persistent WebSocket connection per registered app (see
 * [IrisConfig.getRegistrations]) and broadcasts incoming push payloads to
 * the corresponding target app via a signature-protected broadcast. See
 * PROTOCOL.md for the wire format.
 *
 * Each connection reconnects independently with a fixed backoff and sends a
 * "ping" text frame every 45s. [onStartCommand] is safe to call repeatedly
 * (e.g. after a registration was added/removed) - it reconciles the set of
 * running connections against the current registration list instead of
 * assuming it's only ever started once.
 */
class IrisPushService : Service() {

    companion object {
        const val ACTION_PUSH = "de.letheapp.iris.PUSH"
        const val PERMISSION_RECEIVE_PUSH = "de.letheapp.iris.RECEIVE_PUSH"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_DATA_JSON = "data_json"

        private const val CHANNEL_ID = "iris_connection"
        private const val NOTIFICATION_ID = 1
        private const val PING_INTERVAL_MS = 45_000L
        private const val RECONNECT_DELAY_MS = 5_000L

        private val _connectionStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())

        /** package_name -> currently connected, for every app this service is handling. */
        val connectionStatus: StateFlow<Map<String, Boolean>> = _connectionStatus.asStateFlow()

        private fun setStatus(packageName: String, connected: Boolean?) {
            val updated = _connectionStatus.value.toMutableMap()
            if (connected == null) updated.remove(packageName) else updated[packageName] = connected
            _connectionStatus.value = updated
        }
    }

    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val jobs = mutableMapOf<String, Job>() // package_name -> connectLoop job
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // we send our own text "ping", see PING_INTERVAL_MS
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        IrisLog.log(this, "IrisPushService", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Verbinde…"))
        if (!IrisConfig.isConfigured(this)) {
            IrisLog.log(this, "IrisPushService", "onStartCommand: keine Registrierung, stoppe")
            stopSelf()
            return START_NOT_STICKY
        }
        if (IrisBridgeService.isDaemonAvailable()) {
            // A native irisd daemon showed up since this was last started
            // (e.g. an OS/OTA update added it) - defer to IrisBridgeService
            // instead of also holding our own sockets.
            IrisLog.log(this, "IrisPushService", "onStartCommand: irisd erkannt, wechsle zu IrisBridgeService")
            IrisDelivery.start(this)
            stopSelf()
            return START_NOT_STICKY
        }
        syncConnections()
        return START_STICKY
    }

    override fun onDestroy() {
        IrisLog.log(this, "IrisPushService", "onDestroy (${jobs.size} aktive Verbindung(en))")
        for ((pkg, job) in jobs) {
            job.cancel()
            setStatus(pkg, null)
        }
        jobs.clear()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Starts a connectLoop for every newly-registered app, cancels one for every removed app. */
    private fun syncConnections() {
        val registrations = IrisConfig.getRegistrations(this).associateBy { it.packageName }

        for (pkg in jobs.keys.toList()) {
            if (pkg !in registrations) {
                jobs.remove(pkg)?.cancel()
                setStatus(pkg, null)
            }
        }
        for ((pkg, reg) in registrations) {
            if (pkg !in jobs) {
                jobs[pkg] = scope.launch { connectLoop(reg) }
            }
        }
        updateNotification(summaryText())
    }

    private fun summaryText(): String {
        val total = jobs.size
        val connected = _connectionStatus.value.values.count { it }
        return "$connected/$total App(s) verbunden"
    }

    private suspend fun connectLoop(reg: IrisRegistration) {
        while (scope.isActive) {
            try {
                connectOnce(reg)
            } catch (e: Exception) {
                IrisLog.log(this, "IrisPushService", "Verbindung zu ${reg.packageName} fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            }
            setStatus(reg.packageName, false)
            updateNotification(summaryText())
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** Suspends until the socket closes or fails, then returns so connectLoop can retry. */
    private suspend fun connectOnce(reg: IrisRegistration) {
        val url = IrisConfig.websocketUrl(this, reg.token)
        val request = Request.Builder().url(url).build()
        val closed = CompletableDeferred<Unit>()
        var activeSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                setStatus(reg.packageName, true)
                updateNotification(summaryText())
                scope.launch { pingLoop(webSocket) { activeSocket === webSocket } }
                scope.launch { IrisApi.reportInstalled(IrisConfig.getServerUrl(this@IrisPushService), reg.token) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                handleIncoming(reg.packageName, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                IrisLog.log(this@IrisPushService, "IrisPushService", "${reg.packageName}: Verbindung geschlossen ($code $reason)")
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                IrisLog.log(this@IrisPushService, "IrisPushService", "${reg.packageName}: Verbindung fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
                closed.complete(Unit)
            }
        }

        activeSocket = client.newWebSocket(request, listener)
        closed.await()
    }

    private suspend fun pingLoop(webSocket: WebSocket, stillActive: () -> Boolean) {
        while (scope.isActive && stillActive()) {
            delay(PING_INTERVAL_MS)
            if (!stillActive()) break
            val sent = webSocket.send("ping")
            if (!sent) break
        }
    }

    private fun handleIncoming(targetPackage: String, text: String) {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = try {
            gson.fromJson(text, type)
        } catch (_: Exception) {
            return
        }
        if (payload["type"] != "push") return

        val title = payload["title"] as? String ?: ""
        val body = payload["body"] as? String ?: ""
        val data = payload["data"]
        val dataJson = if (data != null) gson.toJson(data) else "{}"

        sendBroadcast(
            Intent(ACTION_PUSH).apply {
                setPackage(targetPackage)
                putExtra(EXTRA_PACKAGE_NAME, targetPackage)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_DATA_JSON, dataJson)
            },
            PERMISSION_RECEIVE_PUSH
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Iris-Verbindung", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Haelt die Push-Verbindung(en) zum Iris-Server aufrecht."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Iris Push")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
