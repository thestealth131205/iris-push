package de.letheapp.iris

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { IrisScreen() }
        }
    }

    @Composable
    fun IrisScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var serverUrl by remember { mutableStateOf(IrisConfig.getServerUrl(context)) }
        var packageName by remember { mutableStateOf(IrisConfig.getTargetPackageName(context) ?: "") }
        var token by remember { mutableStateOf(IrisConfig.getToken(context) ?: "") }
        var status by remember { mutableStateOf<String?>(null) }
        var serviceRunning by remember { mutableStateOf(IrisConfig.isConfigured(context)) }
        var daemonAvailable by remember { mutableStateOf(false) }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* if denied, the foreground service still runs, just without a visible notification */ }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            daemonAvailable = withContext(Dispatchers.IO) { IrisBridgeService.isDaemonAvailable() }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Iris Push", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Offener, FCM-unabhängiger Push-Relay-Dienst. Registriert dieses Gerät beim " +
                    "angegebenen Server und stellt eingehende Pushes per Broadcast an die unten " +
                    "genannte App zu (sie benötigt die Permission de.letheapp.iris.RECEIVE_PUSH, " +
                    "mit demselben Signaturschlüssel wie dieser Build).",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server-URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it },
                label = { Text("Ziel-App Package-Name") },
                placeholder = { Text("de.letheapp.lethe") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    scope.launch {
                        status = "Registriere…"
                        val result = withContext(Dispatchers.IO) {
                            registerDevice(serverUrl, packageName)
                        }
                        if (result != null) {
                            IrisConfig.setServerUrl(context, serverUrl)
                            IrisConfig.setTargetPackageName(context, packageName)
                            IrisConfig.setToken(context, result)
                            token = result
                            status = "Registriert ✓"
                        } else {
                            status = "Registrierung fehlgeschlagen"
                        }
                    }
                },
                enabled = serverUrl.isNotBlank() && packageName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Registrieren / Token erzeugen") }

            if (token.isNotBlank()) {
                Text("Token: $token", style = MaterialTheme.typography.bodySmall)
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()

            Text(
                if (daemonAvailable)
                    "Nativer irisd-Systemdienst erkannt – Zustellung läuft über diesen (kein eigener Dauer-Socket in dieser App)."
                else
                    "Kein irisd-Systemdienst erkannt – diese App hält die Verbindung selbst (Normalmodus, z.B. Sideload ohne OS-Integration).",
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { IrisDelivery.start(context) }
                            serviceRunning = true
                        }
                    },
                    enabled = IrisConfig.isConfigured(context)
                ) { Text("Dienst starten") }

                OutlinedButton(onClick = {
                    IrisDelivery.stop(context)
                    serviceRunning = false
                }) { Text("Dienst stoppen") }
            }

            OutlinedButton(
                onClick = { requestBatteryOptimizationExemption() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Von Akku-Optimierung ausnehmen") }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    /** POST {serverUrl}/push/register, returns the token on success. */
    private fun registerDevice(serverUrl: String, targetPackageName: String): String? {
        return try {
            val deviceId = IrisConfig.getDeviceId(this) ?: UUID.randomUUID().toString().also {
                IrisConfig.setDeviceId(this, it)
            }
            val body = JSONObject().apply {
                put("package_name", targetPackageName)
                put("device_id", deviceId)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/push/register")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string() ?: return null)
                json.getString("token")
            }
        } catch (_: Exception) {
            null
        }
    }
}
