package de.letheapp.iris

import android.content.Context
import android.os.Build

/**
 * Keeps the native daemon in step with this app, so the OS image never has
 * to change for either of the two things that move:
 *
 *  - Registrations: this app owns the token list ([IrisConfig]) and pushes it
 *    into irisd (REGISTER/UNREGISTER). The daemon writes its own config
 *    files; no app needs write access to /data/misc/iris. Replayed on every
 *    bridge (re)connect, so a restarted or updated daemon is repopulated.
 *  - The daemon binary: a newer, signed irisd bundled in this APK's assets
 *    is handed to the running daemon (INSTALL) and activated with RELOAD.
 *    The image only carries a floor version. See daemon/CONTROL_PROTOCOL.md.
 */
object IrisDaemonSync {
    private const val ASSET_DIR = "irisd"
    private const val TAG = "IrisDaemonSync"

    /** Called by [IrisBridgeService] each time it connects to the daemon. */
    fun onBridgeConnected(context: Context) {
        val status = DaemonClient.status() ?: return
        // An update restarts the daemon; the bridge reconnects and runs us again.
        if (status.protocolVersion >= 2 && updateDaemonIfBundledNewer(context, status)) return
        syncRegistrations(context, status)
    }

    fun register(context: Context, packageName: String, token: String) {
        val status = DaemonClient.status() ?: return
        if (status.protocolVersion < 2) {
            IrisLog.log(context, TAG, "irisd ${status.daemonVersion} kann REGISTER nicht (Protokoll ${status.protocolVersion})")
            return
        }
        send(context, packageName, token)
    }

    fun unregister(context: Context, packageName: String) {
        val status = DaemonClient.status() ?: return
        if (status.protocolVersion < 2) return
        DaemonClient.command("UNREGISTER $packageName")?.let {
            IrisLog.log(context, TAG, "UNREGISTER $packageName fehlgeschlagen: $it")
        }
    }

    private fun syncRegistrations(context: Context, status: DaemonStatus) {
        if (status.protocolVersion < 2) return
        for (reg in IrisConfig.getRegistrations(context)) send(context, reg.packageName, reg.token)
    }

    private fun send(context: Context, packageName: String, token: String) {
        val url = IrisConfig.getServerUrl(context)
        DaemonClient.command("REGISTER $packageName $url $token")?.let {
            IrisLog.log(context, TAG, "REGISTER $packageName fehlgeschlagen: $it")
        }
    }

    /** Returns true if an update was installed and the daemon was told to restart. */
    private fun updateDaemonIfBundledNewer(context: Context, status: DaemonStatus): Boolean {
        val assets = context.assets
        val bundledVersion = try {
            assets.open("$ASSET_DIR/VERSION").bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) {
            return false // OS-image-only build, nothing bundled
        }
        if (!isNewer(bundledVersion, status.daemonVersion)) return false

        val abi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
            assets.list("$ASSET_DIR/$abi")?.contains("irisd") == true
        } ?: return false

        val binary: ByteArray
        val sigHex: String
        try {
            binary = assets.open("$ASSET_DIR/$abi/irisd").use { it.readBytes() }
            sigHex = assets.open("$ASSET_DIR/$abi/irisd.sig").use { it.readBytes() }
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            IrisLog.log(context, TAG, "Gebuendeltes irisd unvollstaendig: ${e.message}")
            return false
        }

        val err = DaemonClient.command("INSTALL ${binary.size} $bundledVersion $sigHex", binary)
        if (err != null) {
            IrisLog.log(context, TAG, "irisd-Update $bundledVersion abgelehnt: $err")
            return false
        }
        IrisLog.log(context, TAG, "irisd ${status.daemonVersion} -> $bundledVersion installiert, starte Daemon neu")
        DaemonClient.command("RELOAD")
        return true
    }

    private fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
