package de.letheapp.iris

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
        private const val CHANNEL_ID = "iris_connection"
        private const val NOTIFICATION_ID = 1
        private const val PING_INTERVAL_MS = 45_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val CLOSE_TOKEN_REJECTED = 4001

        private val _connectionStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())

        @Volatile private var running: IrisPushService? = null

        /**
         * Picks up added/removed registrations in an already running service without a
         * new startForegroundService() call - that call is refused from the background
         * (Android 12+) unless Iris is exempt from battery optimization. Returns false
         * if the service isn't running.
         */
        fun isRunning(): Boolean = running != null

        fun resyncIfRunning(): Boolean {
            val service = running ?: return false
            android.os.Handler(android.os.Looper.getMainLooper()).post { service.syncConnections() }
            return true
        }

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
        running = this
        IrisLog.log(this, "IrisPushService", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Verbinde…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (e: Exception) {
            // Safety net (e.g. background-start restrictions, or a stale dataSync
            // quota from <=1.3.1) - retry later instead of crashing.
            IrisLog.log(this, "IrisPushService", "onStartCommand: startForeground abgelehnt (${e.javaClass.simpleName}), Neustart in 30min")
            ForegroundServiceRestart.scheduleRetry(this)
            stopSelf()
            return START_NOT_STICKY
        }
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
        if (running === this) running = null
        for ((pkg, job) in jobs) {
            job.cancel()
            setStatus(pkg, null)
        }
        jobs.clear()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Safety net only: the service is specialUse-typed now (no time limit),
     * but Android 15+ calls this for time-limited FGS types (dataSync, as
     * used up to 1.3.1) before force-stopping them. Without this override
     * the system kills the process and reports
     * ForegroundServiceDidNotStopInTimeException as a crash.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        IrisLog.log(this, "IrisPushService", "onTimeout: FGS-Zeitlimit erreicht (type=$fgsType), Neustart in 30min")
        ForegroundServiceRestart.scheduleRetry(this)
        stopSelf()
    }

    /** Starts a connectLoop for every newly-registered app, cancels one for every removed app. */
    fun syncConnections() {
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
                handleIncoming(reg, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                IrisLog.log(this@IrisPushService, "IrisPushService", "${reg.packageName}: Verbindung geschlossen ($code $reason)")
                if (code == CLOSE_TOKEN_REJECTED) onTokenRejected(reg)
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                IrisLog.log(this@IrisPushService, "IrisPushService", "${reg.packageName}: Verbindung fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
                // Older servers reject unknown tokens before the handshake (HTTP 403).
                if (response?.code == 403) onTokenRejected(reg)
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

    private fun handleIncoming(reg: IrisRegistration, text: String) {
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

        PushDelivery.deliver(this, reg.packageName, reg.token, title, body, dataJson)
    }

    /**
     * Server closed with 4001: token unknown/revoked (e.g. replaced by a newer one for
     * the same account). Drop it so connectLoop stops retrying a dead token, and tell
     * the app - it fetches a new token and registers again.
     */
    private fun onTokenRejected(reg: IrisRegistration) {
        IrisLog.log(this, "IrisPushService", "${reg.packageName}: Token vom Server abgelehnt – Registrierung entfernt, App informiert")
        if (IrisConfig.getRegistrations(this).any { it.packageName == reg.packageName && it.token == reg.token }) {
            IrisConfig.removeRegistration(this, reg.packageName)
        }
        PushDelivery.notifyUnregistered(this, reg.packageName, reg.token)
        scope.launch(Dispatchers.Main) {
            if (IrisConfig.isConfigured(this@IrisPushService)) syncConnections() else stopSelf()
        }
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
