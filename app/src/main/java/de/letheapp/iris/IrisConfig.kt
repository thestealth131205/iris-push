package de.letheapp.iris

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class IrisRegistration(val packageName: String, val token: String)

/**
 * Persisted Iris configuration: which server to connect to, and the list of
 * installed apps (package name + token from /push/register) this device
 * relays pushes to. One device can serve several apps at once - the native
 * irisd daemon already supported this (one config file per app); the Java
 * fallback path (IrisPushService) mirrors it with one WebSocket per app.
 */
object IrisConfig {
    private const val PREFS = "iris_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_REGISTRATIONS = "registrations" // JSON array of {package_name, token}

    // Pre-multi-app single-value keys, kept only to migrate existing installs once.
    private const val KEY_LEGACY_PACKAGE_NAME = "package_name"
    private const val KEY_LEGACY_TOKEN = "token"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "https://letheapp.de") ?: "https://letheapp.de"

    fun setServerUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, value).apply()
    }

    fun getDeviceId(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ID, null)

    fun setDeviceId(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_DEVICE_ID, value).apply()
    }

    fun getRegistrations(context: Context): List<IrisRegistration> {
        migrateLegacyIfNeeded(context)
        val raw = prefs(context).getString(KEY_REGISTRATIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val pkg = obj.optString("package_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val token = obj.optString("token").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                IrisRegistration(pkg, token)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRegistrations(context: Context, regs: List<IrisRegistration>) {
        val array = JSONArray()
        for (reg in regs) {
            array.put(JSONObject().apply {
                put("package_name", reg.packageName)
                put("token", reg.token)
            })
        }
        prefs(context).edit().putString(KEY_REGISTRATIONS, array.toString()).apply()
    }

    /** Adds a new registration or replaces the token of an existing one for the same package. */
    fun upsertRegistration(context: Context, packageName: String, token: String) {
        val regs = getRegistrations(context).filterNot { it.packageName == packageName }
        saveRegistrations(context, regs + IrisRegistration(packageName, token))
    }

    fun removeRegistration(context: Context, packageName: String) {
        saveRegistrations(context, getRegistrations(context).filterNot { it.packageName == packageName })
    }

    fun isConfigured(context: Context): Boolean = getRegistrations(context).isNotEmpty()

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

    private fun migrateLegacyIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_REGISTRATIONS)) return
        val legacyPkg = p.getString(KEY_LEGACY_PACKAGE_NAME, null)
        val legacyToken = p.getString(KEY_LEGACY_TOKEN, null)
        val editor = p.edit()
        if (!legacyPkg.isNullOrBlank() && !legacyToken.isNullOrBlank()) {
            editor.putString(
                KEY_REGISTRATIONS,
                JSONArray().put(JSONObject().apply {
                    put("package_name", legacyPkg)
                    put("token", legacyToken)
                }).toString()
            )
        } else {
            editor.putString(KEY_REGISTRATIONS, JSONArray().toString())
        }
        editor.remove(KEY_LEGACY_PACKAGE_NAME).remove(KEY_LEGACY_TOKEN).apply()
    }
}
