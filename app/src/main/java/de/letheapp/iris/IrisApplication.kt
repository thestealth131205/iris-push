package de.letheapp.iris

import android.app.Application

/**
 * Installs a global uncaught-exception handler so crashes get written to
 * [IrisLog] (visible via the "Log" menu entry) before the process dies -
 * this app runs unattended as a background service most of the time, so
 * without this a crash leaves no trace the user could ever see.
 */
class IrisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                IrisLog.logCrash(this, thread, throwable)
            } catch (_: Throwable) {
                // never let crash logging itself swallow the original crash
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        IrisLog.log(this, "App", "Prozess gestartet (versionName=${BuildConfig.VERSION_NAME})")
    }
}
