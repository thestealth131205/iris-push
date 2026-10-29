package de.letheapp.iris

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Wakes the device every [INTERVAL_MS] so [IrisPushService] can check its sockets.
 *
 * The 45s ping loop is coroutine-based and stops while the CPU is suspended (Doze,
 * screen off). Without this alarm a connection that died silently while the phone
 * slept - router reconnect with a new IP prefix, NAT mapping dropped by the carrier -
 * goes unnoticed until the user wakes the phone, and every push in between is lost.
 * Each tick only holds a wakelock until all pongs are back (normally < 1s).
 */
object IrisHeartbeat {
    // Android may stretch allow-while-idle alarms to ~9min in deep Doze.
    private const val INTERVAL_MS = 5 * 60 * 1000L

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, HeartbeatReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }
}

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!IrisPushService.heartbeat()) {
            // Service got killed in the meantime - bring it back (it reschedules the alarm).
            if (IrisConfig.isConfigured(context)) IrisDelivery.start(context)
        }
    }
}
