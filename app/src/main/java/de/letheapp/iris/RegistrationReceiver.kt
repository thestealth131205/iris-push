package de.letheapp.iris

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Lets an installed app (e.g. Lethe, com.Lethe.app) hand Iris its push token
 * on its own, with no user step in the Iris UI: after its own token request it
 * broadcasts [ACTION_REGISTER] with its package name and token. Iris stores it
 * ([IrisConfig]), forwards it to irisd if one is running, (re)starts delivery
 * and confirms with [PushDelivery.ACTION_REGISTERED].
 *
 * Open to every app (no signature permission - apps from F-Droid/Play are
 * signed with other keys than Iris), so the sender has to prove which package
 * it is: it attaches a PendingIntent of its own as [EXTRA_APP]. The creator
 * package of a PendingIntent is filled in by the system and can't be forged,
 * the same trick GCM/FCM and UnifiedPush use. Without that proof one app could
 * overwrite another app's registration.
 */
class RegistrationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REGISTER = "de.letheapp.iris.action.REGISTER"
        const val ACTION_UNREGISTER = "de.letheapp.iris.action.UNREGISTER"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_APP = "app"
        private const val TAG = "RegistrationReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_REGISTER && action != ACTION_UNREGISTER) return

        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() } ?: return
        val sender = senderPackage(intent)
        if (sender != pkg) {
            IrisLog.log(context, TAG, "Registrierung fuer $pkg verworfen: Absender ${sender ?: "unbekannt"}")
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }
        if (action == ACTION_REGISTER && token == null) return

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (action == ACTION_REGISTER) {
                    val unchanged = IrisConfig.getRegistrations(app).any { it.packageName == pkg && it.token == token }
                    IrisConfig.upsertRegistration(app, pkg, token!!)
                    if (!unchanged) IrisLog.log(app, TAG, "Registrierung fuer $pkg empfangen")
                    if (IrisBridgeService.isDaemonAvailable()) IrisDaemonSync.register(app, pkg, token)
                    IrisDelivery.start(app)
                    PushDelivery.notifyRegistered(app, pkg, token)
                } else {
                    IrisConfig.removeRegistration(app, pkg)
                    IrisLog.log(app, TAG, "Registrierung fuer $pkg entfernt")
                    if (IrisBridgeService.isDaemonAvailable()) IrisDaemonSync.unregister(app, pkg)
                    if (IrisConfig.isConfigured(app)) IrisDelivery.start(app) else IrisDelivery.stop(app)
                }
            } catch (e: Exception) {
                IrisLog.log(app, TAG, "Registrierung fuer $pkg fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun senderPackage(intent: Intent): String? {
        val pi: PendingIntent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_APP, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_APP)
        }
        pi?.creatorPackage?.let { return it }
        // Android 14+: set when the sender opted in via BroadcastOptions.setShareIdentityEnabled.
        return if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
    }
}
