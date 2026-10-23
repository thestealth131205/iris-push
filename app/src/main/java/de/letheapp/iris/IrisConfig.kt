package de.letheapp.iris

import android.content.Context

/**
 * Persisted Iris configuration: which server to connect to, which token
 * identifies this device/app, and which installed app's package name the
 * received pushes should be broadcast to.
 */
object IrisConfig {
    private const val PREFS = "iris_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_PACKAGE_NAME = "package_name"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "token"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "https://letheapp.de") ?: "https://letheapp.de"

    fun setServerUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, value).apply()
    }

    fun getTargetPackageName(context: Context): String? =
        prefs(context).getString(KEY_PACKAGE_NAME, null)

    fun setTargetPackageName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PACKAGE_NAME, value).apply()
    }

    fun getDeviceId(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ID, null)

    fun setDeviceId(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_DEVICE_ID, value).apply()
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun setToken(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_TOKEN, value).apply()
    }

    fun isConfigured(context: Context): Boolean =
        !getToken(context).isNullOrBlank() && !getTargetPackageName(context).isNullOrBlank()

    /** wss:// counterpart of the configured HTTP(S) server URL, pointed at the given token. */
    fun websocketUrl(context: Context, token: String): String {
        val base = getServerUrl(context).trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        }
        return "$wsBase/iris/connect/$token"
    }
}
