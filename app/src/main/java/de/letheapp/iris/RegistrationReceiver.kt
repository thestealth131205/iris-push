package de.letheapp.iris

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Lets an installed app (e.g. Lethe, com.Lethe.app) hand Iris its push token
 * on its own, with no user step in the Iris UI: after its own /push/register
 * call it broadcasts [ACTION_REGISTER] with its package name and token.
 * Iris stores it ([IrisConfig]), forwards it to irisd if one is running, and
 * (re)starts delivery.
 *
 * Guarded by the signature permission [PERMISSION_REGISTER], so only apps
 * signed with the same key (the ones bundled into the OS image) can call it.
 * On Android 14+ the claimed package must also match the actual sender.
 */
class RegistrationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REGISTER = "de.letheapp.iris.action.REGISTER"
        const val ACTION_UNREGISTER = "de.letheapp.iris.action.UNREGISTER"
        const val PERMISSION_REGISTER = "de.letheapp.iris.permission.REGISTER"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_TOKEN = "token"
        private const val TAG = "RegistrationReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_REGISTER && action != ACTION_UNREGISTER) return

        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() } ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            val sender = sentFromPackage
            if (sender != null && sender != pkg) {
                IrisLog.log(context, TAG, "$sender gibt sich als $pkg aus - verworfen")
                return
            }
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }
        if (action == ACTION_REGISTER && token == null) return

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (action == ACTION_REGISTER) {
                    IrisConfig.upsertRegistration(app, pkg, token!!)
                    IrisLog.log(app, TAG, "Registrierung fuer $pkg empfangen")
                    if (IrisBridgeService.isDaemonAvailable()) IrisDaemonSync.register(app, pkg, token)
                    IrisDelivery.start(app)
                } else {
                    IrisConfig.removeRegistration(app, pkg)
                    IrisLog.log(app, TAG, "Registrierung fuer $pkg entfernt")
                    if (IrisBridgeService.isDaemonAvailable()) IrisDaemonSync.unregister(app, pkg)
                    if (IrisConfig.isConfigured(app)) IrisDelivery.start(app) else IrisDelivery.stop(app)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
