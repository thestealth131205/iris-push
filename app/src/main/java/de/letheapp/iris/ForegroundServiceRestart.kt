package de.letheapp.iris

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android 15+ caps a "dataSync"-typed foreground service at 6h cumulative
 * runtime per rolling 24h window (see
 * developer.android.com/about/versions/15/behavior-changes-15#fgs-timeout).
 * [IrisPushService] and [IrisBridgeService] are both dataSync FGS held
 * indefinitely by design (a persistent push connection), so they will
 * eventually hit this limit - that's expected, not a bug. The fix is to shut
 * down cleanly when it happens (via Service.onTimeout, or by catching the
 * exception startForeground() throws once the quota is already exhausted)
 * and retry later, instead of letting the system kill the process
 * (ForegroundServiceDidNotStopInTimeException) or crash-looping via
 * START_STICKY against the same exhausted quota.
 */
object ForegroundServiceRestart {
    // Some of the rolling 24h window will have aged out by then, freeing up
    // enough quota for the next startForeground() attempt to succeed.
    private const val RETRY_DELAY_MS = 30 * 60 * 1000L

    fun scheduleRetry(context: Context) {
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, RestartReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + RETRY_DELAY_MS,
            pendingIntent
        )
    }
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IrisLog.log(context, "RestartReceiver", "Verzoegerter Neustart nach FGS-Zeitlimit")
        IrisDelivery.start(context)
    }
}
