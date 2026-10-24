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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Holds exactly one persistent WebSocket connection to an Iris server and
 * broadcasts any incoming push payload to the locally configured target app
 * via a signature-protected broadcast. See PROTOCOL.md for the wire format.
 *
 * Reconnects with a capped linear backoff on disconnect/failure; sends a
 * "ping" text frame every 45s to keep the connection (and any intermediate
 * reverse proxy) alive.
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
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var socket: WebSocket? = null
    private var connectLoopJob: Job? = null
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // we send our own text "ping", see PING_INTERVAL_MS
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Verbinde…"))
        if (!IrisConfig.isConfigured(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (IrisBridgeService.isDaemonAvailable()) {
            // A native irisd daemon showed up since this was last started
            // (e.g. an OS/OTA update added it) - defer to IrisBridgeService
            // instead of also holding our own socket.
            IrisDelivery.start(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (connectLoopJob?.isActive != true) {
            connectLoopJob = scope.launch { connectLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connectLoopJob?.cancel()
        socket?.close(1000, "service_stopped")
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun connectLoop() {
        val token = IrisConfig.getToken(this) ?: return
        while (scope.isActive) {
            try {
                connectOnce(token)
            } catch (_: Exception) {
                // fall through to backoff + retry
            }
            updateNotification("Getrennt – erneuter Versuch in ${RECONNECT_DELAY_MS / 1000}s…")
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** Suspends until the socket closes or fails, then returns so connectLoop can retry. */
    private suspend fun connectOnce(token: String) {
        val url = IrisConfig.websocketUrl(this, token)
        val request = Request.Builder().url(url).build()
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateNotification("Verbunden")
                scope.launch { pingLoop(webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                handleIncoming(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Unit)
            }
        }

        socket = client.newWebSocket(request, listener)
        closed.await()
    }

    private suspend fun pingLoop(webSocket: WebSocket) {
        while (scope.isActive && socket === webSocket) {
            delay(PING_INTERVAL_MS)
            val sent = webSocket.send("ping")
            if (!sent) break
        }
    }

    private fun handleIncoming(text: String) {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = try {
            gson.fromJson(text, type)
        } catch (_: Exception) {
            return
        }
        if (payload["type"] != "push") return
        val targetPackage = IrisConfig.getTargetPackageName(this) ?: return

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
            description = "Haelt die Push-Verbindung zum Iris-Server aufrecht."
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
