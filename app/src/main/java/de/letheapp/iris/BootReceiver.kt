package de.letheapp.iris

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the Iris connection after a reboot, if a token was already configured. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!IrisConfig.isConfigured(context)) return
        ContextCompat.startForegroundService(context, Intent(context, IrisPushService::class.java))
    }
}
