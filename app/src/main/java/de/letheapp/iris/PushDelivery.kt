package de.letheapp.iris

import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * Hands one push to its target app as an explicit broadcast (setPackage), the
 * same mechanism GMS uses to wake FCM clients: Android starts the target's
 * process for its manifest receiver, so the target app never has to keep a
 * service or connection of its own running.
 *
 * No receiver permission: apps signed with a different key (e.g. Lethe
 * Messenger from F-Droid/Play) could never hold a signature permission. The
 * broadcast is explicit, so only the target package receives it, and it
 * carries the token the target registered with - the target drops any
 * PUSH whose token isn't its own.
 */
object PushDelivery {
    const val ACTION_PUSH = "de.letheapp.iris.PUSH"
    const val ACTION_REGISTERED = "de.letheapp.iris.action.REGISTERED"
    const val ACTION_UNREGISTERED = "de.letheapp.iris.action.UNREGISTERED"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_DATA_JSON = "data_json"

    private const val WAKELOCK_TIMEOUT_MS = 3_000L

    // FLAG_RECEIVER_FOREGROUND: delivered from the foreground queue, so the
    // receiver runs promptly even while the device is idle.
    // FLAG_INCLUDE_STOPPED_PACKAGES: also reaches apps that haven't run since
    // a reboot (still not ones the user force-stopped - Android forbids that).
    private const val FLAGS = Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES

    fun deliver(context: Context, targetPackage: String, token: String, title: String, body: String, dataJson: String) {
        withWakeLock(context) {
            context.sendBroadcast(
                Intent(ACTION_PUSH).apply {
                    setPackage(targetPackage)
                    addFlags(FLAGS)
                    putExtra(EXTRA_PACKAGE_NAME, targetPackage)
                    putExtra(EXTRA_TOKEN, token)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_BODY, body)
                    putExtra(EXTRA_DATA_JSON, dataJson)
                }
            )
        }
    }

    /** Tells an app its registration was accepted (it can stop its own fallback connection). */
    fun notifyRegistered(context: Context, targetPackage: String, token: String) {
        context.sendBroadcast(statusIntent(ACTION_REGISTERED, targetPackage, token))
    }

    /** Tells an app the server rejected its token, so it fetches a new one and re-registers. */
    fun notifyUnregistered(context: Context, targetPackage: String, token: String) {
        context.sendBroadcast(statusIntent(ACTION_UNREGISTERED, targetPackage, token))
    }

    private fun statusIntent(action: String, targetPackage: String, token: String) =
        Intent(action).apply {
            setPackage(targetPackage)
            addFlags(FLAGS)
            putExtra(EXTRA_PACKAGE_NAME, targetPackage)
            putExtra(EXTRA_TOKEN, token)
        }

    // Iris is woken by the incoming packet, but sendBroadcast() only queues the
    // intent - without a wakelock the CPU may go back to sleep before the target
    // process is started. Not released early on purpose: the timeout covers the
    // few seconds the target needs, then it expires on its own.
    private inline fun withWakeLock(context: Context, block: () -> Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iris:deliver").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        block()
    }
}
