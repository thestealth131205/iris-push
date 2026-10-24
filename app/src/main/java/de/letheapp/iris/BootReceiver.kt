package de.letheapp.iris

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the Iris connection after a reboot, if a token was already configured. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        IrisDelivery.start(context)
    }
}
