package de.letheapp.iris

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Shared helper for the small one-off HTTP calls to the Lethe Iris endpoints
 * (registration happens in MainActivity, this covers the installed-status
 * report sent by [IrisPushService] once a connection is actually up).
 */
object IrisApi {
    private val client = OkHttpClient()

    /**
     * Best-effort POST {serverUrl}/push/installed - tells the server this token
     * is actively served (component="apk" from the Java client here; the native
     * irisd daemon reports the same thing itself with component="daemon" when it
     * holds the connection instead). Failures are swallowed; this is a status
     * signal only, never required for push delivery itself.
     */
    fun reportInstalled(serverUrl: String, token: String, component: String = "apk") {
        try {
            val body = JSONObject().apply {
                put("token", token)
                put("component", component)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/push/installed")
                .post(body)
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // status-only, ignore
        }
    }
}
