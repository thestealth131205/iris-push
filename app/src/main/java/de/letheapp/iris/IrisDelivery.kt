package de.letheapp.iris

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Picks the right delivery path: the native `irisd` daemon's thin bridge
 * ([IrisBridgeService]) if it's running on this device (OS-integrated
 * builds, see /daemon/OS_INTEGRATION_PLAN.md), otherwise the self-contained
 * Java client ([IrisPushService]) used by a normal sideloaded install.
 *
 * The probe is cheap (local socket connect+close) and re-run every time
 * this is called rather than cached, since a user could in principle
 * install this APK before the OS-level daemon is flashed and the daemon
 * could appear later without reinstalling.
 */
object IrisDelivery {

    fun start(context: Context) {
        if (!IrisConfig.isConfigured(context)) return
        if (IrisBridgeService.isDaemonAvailable()) {
            context.stopService(Intent(context, IrisPushService::class.java))
            ContextCompat.startForegroundService(context, Intent(context, IrisBridgeService::class.java))
        } else {
            context.stopService(Intent(context, IrisBridgeService::class.java))
            ContextCompat.startForegroundService(context, Intent(context, IrisPushService::class.java))
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, IrisPushService::class.java))
        context.stopService(Intent(context, IrisBridgeService::class.java))
    }
}
