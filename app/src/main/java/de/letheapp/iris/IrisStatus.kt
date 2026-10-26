package de.letheapp.iris

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class DaemonStatus(
    val available: Boolean,
    val version: String? = null,
    val perAppConnected: Map<String, Boolean> = emptyMap()
)

/**
 * Combines the two possible sources of per-app connection state into one
 * list the UI can render: the native irisd daemon (queried live via its
 * STATUS protocol, see daemon/README.md) when it's present, or this app's
 * own in-process [IrisPushService] connections when it's holding them
 * itself (sideload / no OS integration).
 */
object IrisStatus {
    private const val PROBE_TIMEOUT_MS = 1500
    private val gson = Gson()

    /** Connects once, sends STATUS, reads one reply line, closes. Cheap, safe to poll. */
    suspend fun queryDaemon(): DaemonStatus = withContext(Dispatchers.IO) {
        try {
            val socket = LocalSocket()
            socket.connect(
                LocalSocketAddress(IrisBridgeService.SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED)
            )
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.use {
                it.outputStream.write("STATUS\n".toByteArray())
                it.outputStream.flush()
                val reader = BufferedReader(InputStreamReader(it.inputStream))
                val line = reader.readLine() ?: return@withContext DaemonStatus(available = true)
                parseStatusLine(line)
            }
        } catch (_: Exception) {
            DaemonStatus(available = false)
        }
    }

    private fun parseStatusLine(line: String): DaemonStatus {
        val obj = try {
            gson.fromJson(line, JsonObject::class.java)
        } catch (_: Exception) {
            null
        } ?: return DaemonStatus(available = true)

        val version = obj.get("daemon_version")?.asString
        val perApp = mutableMapOf<String, Boolean>()
        obj.getAsJsonArray("apps")?.forEach { el ->
            val appObj = el.asJsonObject
            val pkg = appObj.get("package_name")?.asString ?: return@forEach
            val connected = appObj.get("connected")?.asBoolean ?: false
            perApp[pkg] = connected
        }
        return DaemonStatus(available = true, version = version, perAppConnected = perApp)
    }

    /**
     * Per-package connected state for every configured registration, using
     * the daemon's live report when it's running (source of truth for
     * bridge mode), falling back to [IrisPushService]'s own in-memory
     * connection map otherwise. Packages with no entry either way are
     * reported as not connected rather than omitted, so the UI always has
     * one row per configured app.
     */
    suspend fun appConnectionStatus(context: Context): Map<String, Boolean> {
        val registrations = IrisConfig.getRegistrations(context)
        if (registrations.isEmpty()) return emptyMap()

        val daemon = queryDaemon()
        val source = if (daemon.available) daemon.perAppConnected else IrisPushService.connectionStatus.value

        return registrations.associate { it.packageName to (source[it.packageName] ?: false) }
    }
}
