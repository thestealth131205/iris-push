package de.letheapp.iris

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** What the running irisd reports about itself (STATUS). */
data class DaemonStatus(
    val daemonVersion: String,
    /** 1 = pre-command daemon (push fan-out + STATUS only), 2+ = understands REGISTER/INSTALL/... */
    val protocolVersion: Int,
    val isOverride: Boolean,
)

/**
 * Command side of the irisd control socket (see daemon/CONTROL_PROTOCOL.md).
 * Each call is a short-lived connection that first sends UNSUBSCRIBE so the
 * daemon doesn't also fan push lines out to it; the long-lived push reader
 * stays in [IrisBridgeService].
 *
 * Replies are matched by type/cmd, anything else on the line (a push that
 * slipped in before UNSUBSCRIBE took effect, the UNSUBSCRIBE ack) is skipped.
 * A daemon too old to know a command ignores it, so callers check
 * [status].protocolVersion before sending anything newer than STATUS.
 */
object DaemonClient {
    private const val IO_TIMEOUT_MS = 5_000
    private val gson = Gson()

    fun status(): DaemonStatus? {
        val reply = request("STATUS", null) { it.get("type")?.asString == "status" } ?: return null
        return DaemonStatus(
            daemonVersion = reply.get("daemon_version")?.asString ?: return null,
            protocolVersion = reply.get("protocol_version")?.asInt ?: 1,
            isOverride = reply.get("override")?.asBoolean ?: false,
        )
    }

    /** Returns null on success, otherwise the daemon's error string (or "unreachable"). */
    fun command(line: String, payload: ByteArray? = null): String? {
        val cmd = line.substringBefore(' ')
        val reply = request(line, payload) {
            it.get("type")?.asString == "ack" && it.get("cmd")?.asString == cmd
        } ?: return "unreachable"
        return if (reply.get("ok")?.asBoolean == true) null else (reply.get("error")?.asString ?: "error")
    }

    private fun request(line: String, payload: ByteArray?, isReply: (JsonObject) -> Boolean): JsonObject? {
        return try {
            val socket = LocalSocket()
            socket.use {
                it.connect(LocalSocketAddress(IrisBridgeService.SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED))
                it.soTimeout = IO_TIMEOUT_MS
                val out = it.outputStream
                out.write("UNSUBSCRIBE\n$line\n".toByteArray())
                if (payload != null) out.write(payload)
                out.flush()

                val reader = BufferedReader(InputStreamReader(it.inputStream))
                var found: JsonObject? = null
                while (found == null) {
                    val text = reader.readLine() ?: break
                    val obj = try {
                        gson.fromJson(text, JsonObject::class.java)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (isReply(obj)) found = obj
                }
                found
            }
        } catch (_: Exception) {
            null
        }
    }
}
